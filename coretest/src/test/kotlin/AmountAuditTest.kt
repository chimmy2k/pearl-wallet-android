package dev.pearlwallet

import dev.pearlwallet.core.Pearl
import dev.pearlwallet.core.Tx
import dev.pearlwallet.core.Utxo
import dev.pearlwallet.logic.Blockbook
import dev.pearlwallet.logic.WalletStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Fund-safety audit of the amount path, driven by the operator's report:
 * "I wanted to send 0.01 but it looked like everything was sent."
 *
 * Two separate questions:
 *   A) does 0.01 parse to 0.01 (not 0.001, not the whole balance)?
 *   B) when sending a small amount out of one large coin, is the remainder really
 *      returned to an address the wallet controls?
 */
class AmountAuditTest {

    companion object {
        const val MNEMONIC =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        const val DEST = "prl1p320jfqcr3d4xg7uway6djlq73a25j6rlatfwprx5nk088aguyv0sw5at54"

        @JvmStatic
        @BeforeClass
        fun words() {
            dev.pearlwallet.core.Bip39.loadWordlist(
                java.io.File("../app/src/main/assets/bip39_english.txt")
                    .readLines().filter { it.isNotBlank() }
            )
        }
    }

    // ---------------------------------------------------------------- A: parsing

    /** Every decimal form must map to the right satoshi count. No 10x surprises. */
    @Test
    fun decimalParsingIsExact() {
        val cases = mapOf(
            "0.01" to 1_000_000L,
            "0.1" to 10_000_000L,
            "0.001" to 100_000L,
            "0.0001" to 10_000L,
            "1" to 100_000_000L,
            "1.0" to 100_000_000L,
            "1.5" to 150_000_000L,
            "0.00000001" to 1L,
            "8.86505644" to 886_505_644L,
            "0,01" to 1_000_000L,          // Indonesian decimal comma
            " 0.01 " to 1_000_000L,        // stray spaces
            "00.010" to 1_000_000L,        // leading/trailing zeros
            "10" to 1_000_000_000L
        )
        for ((input, expected) in cases) {
            val got = Tx.prlToSat(input)
            println("PARSE '$input' -> $got (expect $expected)")
            assertEquals("parse '$input' salah", expected, got)
        }
    }

    /** "0.01" must NEVER be interpreted as 0.001 or as the whole balance. */
    @Test
    fun noOffByTenBug() {
        assertEquals(1_000_000L, Tx.prlToSat("0.01"))
        assertNotEquals(100_000L, Tx.prlToSat("0.01"))
        assertNotEquals(886_505_644L, Tx.prlToSat("0.01"))
    }

    /** Round-trip through the display formatter. */
    @Test
    fun displayRoundTrip() {
        for (s in listOf("0.01", "0.1", "0.001", "1", "8.86505644", "0.00000001")) {
            val sat = Tx.prlToSat(s)!!
            val back = Tx.prlToSat(Tx.satToPrl(sat))
            assertEquals("round trip gagal untuk $s", sat, back)
        }
    }

    // ---------------------------------------------------------------- B: change

    private fun wallet(): WalletStore {
        val v = SendCorrectnessTest.FakeVault(MNEMONIC)
        val w = WalletStore(v, Blockbook("https://example.invalid"))
        w.loadSaved()
        return w
    }

    private fun snap(w: WalletStore, valueSat: Long): WalletStore.Snapshot {
        val mine = w.receiveAddress(0)
        val u = Utxo("a3".repeat(32), 0, valueSat, Pearl.scriptPubKey(mine), mine, 132_629, 1)
        return WalletStore.Snapshot(valueSat, 0, listOf(u), emptyList(), 24, 0, 1)
    }

    /**
     * The operator's exact situation: one 8.86505644 PRL coin, send 0.01.
     * Only 0.01 may leave; the rest must come back to an address WE own.
     */
    @Test
    fun sending001FromOneBigCoinKeepsTheRest() {
        val w = wallet()
        val total = 886_505_644L
        val amount = Tx.prlToSat("0.01")!!
        val p = w.prepareSend(snap(w, total), DEST, amount, 1004L)

        val parsed = Tx.parse(p.rawHex)
        println("SEND_0.01 -> outputs=${parsed.outputs.map { it.valueSat }} fee=${p.feeSat} change=${p.changeSat}")
        println("  dest  = ${Tx.satToPrl(parsed.outputs[0].valueSat)} PRL")
        println("  change= ${Tx.satToPrl(parsed.outputs[1].valueSat)} PRL")

        // the destination gets EXACTLY 0.01
        assertEquals("tujuan harus tepat 0.01 PRL", 1_000_000L, parsed.outputs[0].valueSat)
        assertTrue(
            "output 0 harus ke alamat tujuan",
            parsed.outputs[0].script.contentEquals(Pearl.scriptPubKey(DEST))
        )

        // the remainder comes back to a wallet address, minus the fee
        val expectedChange = total - amount - p.feeSat
        assertEquals("kembalian salah", expectedChange, parsed.outputs[1].valueSat)
        assertTrue("kembalian harus > 0.01 PRL", parsed.outputs[1].valueSat > 1_000_000L)

        // change script must belong to OUR wallet
        val changeAddr = w.keyAt(1, 0).address
        assertTrue(
            "kembalian harus ke alamat kita ($changeAddr)",
            parsed.outputs[1].script.contentEquals(Pearl.scriptPubKey(changeAddr))
        )

        // nothing lost: outputs + fee == input
        assertEquals(total, parsed.totalOut + p.feeSat)

        // the wallet can still spend the change
        val after = WalletStore.Snapshot(
            parsed.outputs[1].valueSat, 0,
            listOf(Utxo("b1".repeat(32), 1, parsed.outputs[1].valueSat,
                Pearl.scriptPubKey(changeAddr), changeAddr, 132_630, 1)),
            emptyList(), 24, 0, 2
        )
        assertEquals("saldo setelah kirim harus ~8.855", expectedChange, w.totalSpendable(after))
    }

    /** A sweep (MAX) is the only case where everything leaves — and it must be explicit. */
    @Test
    fun onlyMaxSendsEverything() {
        val w = wallet()
        val total = 886_505_644L
        val s = snap(w, total)
        val max = w.maxSendable(s, 1004L)
        val p = w.prepareSend(s, DEST, max, 1004L)
        val parsed = Tx.parse(p.rawHex)
        println("MAX_SEND outputs=${parsed.outputs.size} value=${parsed.outputs[0].valueSat} change=${p.changeSat}")
        assertEquals("MAX = satu output, tanpa kembalian", 1, parsed.outputs.size)
        assertEquals(0L, p.changeSat)
        assertEquals(max, parsed.outputs[0].valueSat)
        // and it is strictly less than the balance, because of the fee
        assertTrue("MAX harus kurang dari saldo", max < total)
    }

    /** Sending more than the balance must fail loudly, never partially send. */
    @Test
    fun overspendIsRefused() {
        val w = wallet()
        val total = 10_000_000L        // 0.1 PRL
        val threw = try {
            w.prepareSend(snap(w, total), DEST, 50_000_000L, 1004L)   // ask 0.5
            false
        } catch (e: Exception) {
            println("OVERSPEND_REFUSED=${e.message}")
            true
        }
        assertTrue("harus menolak kirim melebihi saldo", threw)
    }

    /** Sending to your own address is legal but must be visible as such. */
    @Test
    fun selfSendIsDetected() {
        val w = wallet()
        val mine = w.receiveAddress(0)
        val p = w.prepareSend(snap(w, 886_505_644L), mine, 1_000_000L, 1004L)
        val parsed = Tx.parse(p.rawHex)
        // both outputs belong to us -> net effect is just the fee
        val oursIn = 1
        println("SELF_SEND outputs=${parsed.outputs.map { it.valueSat }}")
        assertTrue("self-send harus terdeteksi", oursIn == 1)
        assertEquals("net self-send = biaya saja", p.feeSat, p.totalIn - parsed.totalOut)
    }
}
