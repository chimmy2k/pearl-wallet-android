package dev.pearlwallet

import dev.pearlwallet.core.Pearl
import dev.pearlwallet.core.Tx
import dev.pearlwallet.core.Utxo
import dev.pearlwallet.logic.Blockbook
import dev.pearlwallet.logic.WalletStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Locks in the fixes for the operator's report:
 *
 *   "I wanted to send 0.01 but everything got transferred"
 *   "luckily I sent it to myself"
 *
 * On-chain truth (block 132629, tx 53703d64…5ff61):
 *   input   886505644 sat
 *   out[0]    100000 sat -> prl1p2fxx…ghs69   (= 0.001, NOT 0.01)
 *   out[1]  886405488 sat -> prl1pp2x8a…wse6h (change, also the operator's)
 *   fee          156 sat
 *
 * So nothing "left": the whole balance stayed on two of the operator's own addresses.
 * What actually failed was visibility — the app never showed the change address, so an
 * explorer view looked like a full-balance transfer. These tests pin the three fixes:
 *   1. change always goes to an address the wallet owns and can prove it
 *   2. a send of X out of one big coin keeps everything else
 *   3. the decimal string -> satoshi mapping is exact (no factor-of-ten)
 */
class AmountRegressionTest {

    companion object {
        const val MNEMONIC =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        const val TXID = "53703d64e1611b89515ddd93209a15880c3b99f96ada26520c46e1aa6ce5ff61"
        const val SELF = "prl1p2fxxvt4g2kxv9zy2uqsdhag9cc9da4sje088cp9w0fzzumfsjejq0ghs69"
        const val CHANGE = "prl1pp2x8akf3h0e6jl4s8skxr2qhr25ku2rzpf2u7f98luk24y28gllqrwse6h"

        @JvmStatic
        @BeforeClass
        fun words() {
            dev.pearlwallet.core.Bip39.loadWordlist(
                java.io.File("../app/src/main/assets/bip39_english.txt")
                    .readLines().filter { it.isNotBlank() }
            )
        }
    }

    private fun wallet(): WalletStore {
        val w = WalletStore(SendCorrectnessTest.FakeVault(MNEMONIC), Blockbook("https://example.invalid"))
        w.loadSaved()
        return w
    }

    private fun oneCoin(w: WalletStore, value: Long): WalletStore.Snapshot {
        val mine = w.receiveAddress(0)
        val u = Utxo("a3".repeat(32), 0, value, Pearl.scriptPubKey(mine), mine, 132_629, 21)
        return WalletStore.Snapshot(value, 0, listOf(u), emptyList(), 24, 0, 1)
    }

    // ---------------------------------------------------------- 1. real numbers

    /** Replay the exact on-chain transaction shape and confirm value conservation. */
    @Test
    fun realTransactionShapeIsReproducible() {
        val w = wallet()
        val total = 886_505_644L
        val p = w.prepareSend(oneCoin(w, total), SELF, 100_000L, 1004L)
        val parsed = Tx.parse(p.rawHex)

        println("REPLAY outputs=${parsed.outputs.map { it.valueSat }} fee=${p.feeSat}")

        assertEquals("2 output seperti di chain", 2, parsed.outputs.size)
        assertEquals("tujuan 0.001 PRL", 100_000L, parsed.outputs[0].valueSat)
        assertEquals("kembalian", 886_405_488L, parsed.outputs[1].valueSat)
        assertEquals("biaya", 156L, p.feeSat)

        // conservation: input == outputs + fee
        assertEquals(total, parsed.totalOut + p.feeSat)

        // the change output is OURS — this is the fact the UI failed to communicate
        assertTrue(
            "kembalian harus ke alamat dompet sendiri",
            w.ownsAddress(p.changeAddress)
        )
        assertTrue(
            "script output[1] harus cocok dengan alamat kembalian",
            parsed.outputs[1].script.contentEquals(Pearl.scriptPubKey(p.changeAddress))
        )
    }

    /** The destination must receive exactly what was asked — never more, never less. */
    @Test
    fun destinationGetsExactAmount() {
        val w = wallet()
        val snap = oneCoin(w, 886_505_644L)
        for (ask in listOf("0.01", "0.001", "1", "0.5", "8")) {
            val sat = Tx.prlToSat(ask)!!
            val p = w.prepareSend(snap, SELF, sat, 1004L)
            val parsed = Tx.parse(p.rawHex)
            println("ASK $ask -> out0=${parsed.outputs[0].valueSat} change=${p.changeSat}")
            assertEquals("tujuan harus tepat $ask PRL", sat, parsed.outputs[0].valueSat)
            // everything else is either change or fee
            assertEquals(886_505_644L, parsed.totalOut + p.feeSat)
        }
    }

    // ---------------------------------------------------------- 2. change ownership

    /** Every change address the wallet generates must be provably ours. */
    @Test
    fun everyChangeAddressIsOwned() {
        val w = wallet()
        val snap = oneCoin(w, 500_000_000L)
        var checked = 0
        for (ask in listOf(1_000_000L, 2_500_000L, 10_000_000L, 99_000_000L)) {
            val p = w.prepareSend(snap, SELF, ask, 1004L)
            if (p.changeSat > 0) {
                assertTrue("change addr harus milik wallet: ${p.changeAddress}", w.ownsAddress(p.changeAddress))
                checked++
            }
        }
        println("CHANGE_ADDRS_CHECKED=$checked")
        assertTrue("harus ada minimal satu kasus dengan kembalian", checked > 0)
    }

    /** A foreign address must not be reported as ours — the check has to be real. */
    @Test
    fun foreignAddressIsNotOwned() {
        val w = wallet()
        // a valid prl1p address that is not derived from this seed
        val foreign = "prl1p320jfqcr3d4xg7uway6djlq73a25j6rlatfwprx5nk088aguyv0sw5at54"
        assertTrue("alamat asing tidak boleh dianggap milik kita", !w.ownsAddress(foreign))
        assertTrue("alamat sendiri harus dikenali", w.ownsAddress(w.receiveAddress(0)))
    }

    /** Self-send must be flagged, because that is what saved the operator here. */
    @Test
    fun selfSendFlagIsSet() {
        val w = wallet()
        val p = w.prepareSend(oneCoin(w, 886_505_644L), SELF, 100_000L, 1004L)
        // SELF is not derived from the test seed, so it must be false here…
        println("SELF_SEND_FLAG=${p.selfSend}")
        assertEquals("alamat uji ini bukan milik seed uji", false, p.selfSend)

        // …but sending to our own receive address must set it
        val mine = w.receiveAddress(0)
        val p2 = w.prepareSend(oneCoin(w, 886_505_644L), mine, 100_000L, 1004L)
        assertTrue("kirim ke alamat sendiri harus terdeteksi", p2.selfSend)
    }

    // ---------------------------------------------------------- 3. amount parsing

    /** The factor-of-ten family: each must map to its own exact satoshi count. */
    @Test
    fun noFactorOfTenConfusion() {
        val table = listOf(
            "0.1" to 10_000_000L,
            "0.01" to 1_000_000L,
            "0.001" to 100_000L,
            "0.0001" to 10_000L,
            "0.00001" to 1_000L
        )
        for ((s, want) in table) {
            assertEquals("parse $s", want, Tx.prlToSat(s))
        }
        // and they are all distinct from one another
        val values = table.map { Tx.prlToSat(it.first)!! }
        assertEquals("semua nilai harus unik", values.size, values.toSet().size)
    }

    /** Round-tripping through the display format must never shift the value. */
    @Test
    fun displayNeverShiftsValue() {
        for (s in listOf("0.01", "0.001", "0.1", "1", "8.86505644", "0.00000001")) {
            val a = Tx.prlToSat(s)!!
            val shown = Tx.satToPrl(a)
            val b = Tx.prlToSat(shown)!!
            println("ROUNDTRIP $s -> $a -> '$shown' -> $b")
            assertEquals("tampilan mengubah nilai untuk $s", a, b)
        }
    }

    /** Amounts below one satoshi must be rejected, not rounded up or down silently. */
    @Test
    fun subSatoshiRejected() {
        assertEquals("0.000000001 harus ditolak", null, Tx.prlToSat("0.000000001"))
        assertEquals("0.000000019 harus ditolak", null, Tx.prlToSat("0.000000019"))
        assertEquals("1 satoshi diterima", 1L, Tx.prlToSat("0.00000001"))
    }

    // ---------------------------------------------------------- 4. totals

    /** Sum of all address balances must equal the wallet total — no coins unaccounted. */
    @Test
    fun addressListSumsToTotal() {
        val w = wallet()
        val a0 = w.receiveAddress(0)
        val c0 = w.keyAt(1, 0).address
        val utxos = listOf(
            Utxo("a1".repeat(32), 0, 8_107_636_65L, Pearl.scriptPubKey(a0), a0, 132_629, 21),
            Utxo("b2".repeat(32), 1, 886_405_488L, Pearl.scriptPubKey(c0), c0, 132_629, 21)
        )
        val total = utxos.sumOf { it.valueSat }
        val snap = WalletStore.Snapshot(total, 0, utxos, emptyList(), 24, 0, 2)

        val rows = w.addressesWithBalance(snap)
        println("ADDR_ROWS=" + rows.map { "${it.role}:${it.balanceSat}" })
        assertEquals("jumlah baris = jumlah alamat berisi", 2, rows.size)
        assertEquals("total semua alamat = saldo dompet", total, rows.sumOf { it.balanceSat })
    }
}
