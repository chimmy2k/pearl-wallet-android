package dev.pearlwallet

import dev.pearlwallet.core.Bip32
import dev.pearlwallet.core.Bip39
import dev.pearlwallet.core.Pearl
import dev.pearlwallet.core.Tx
import dev.pearlwallet.core.Utxo
import dev.pearlwallet.logic.Blockbook
import dev.pearlwallet.logic.WalletStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Proves what a send actually puts on the wire.
 *
 * The only question that matters: does the fee vanish into thin air (correct — it is the
 * input/output remainder, collected by the miner) or does it get routed to some address
 * (wrong — that would be a hidden payout)? These tests decode the SIGNED bytes and check
 * every output, so there is no room for a stray destination.
 */
class SendCorrectnessTest {

    companion object {
        const val MNEMONIC =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        const val DEST = "prl1p320jfqcr3d4xg7uway6djlq73a25j6rlatfwprx5nk088aguyv0sw5at54"

        @JvmStatic
        @BeforeClass
        fun words() {
            Bip39.loadWordlist(
                java.io.File("../app/src/main/assets/bip39_english.txt")
                    .readLines().filter { it.isNotBlank() }
            )
        }
    }

    private val seed by lazy { Bip39.seed(MNEMONIC) }
    private val account by lazy { Bip32.derivePath(seed, Pearl.accountPath()) }

    /** Our own address at (chain, index) — what the wallet controls. */
    private fun ours(chain: Int, index: Int): String =
        Pearl.p2trAddress(account.child(chain).child(index).pub)

    private fun oursKey(chain: Int, index: Int) = account.child(chain).child(index).priv

    private fun utxo(txidByte: String, vout: Int, value: Long, owner: String) = Utxo(
        txid = txidByte.repeat(32),
        vout = vout,
        valueSat = value,
        scriptPubKey = Pearl.scriptPubKey(owner),
        address = owner,
        confirmations = 6
    )

    private fun snap(utxos: List<Utxo>) = WalletStore.Snapshot(
        confirmedSat = utxos.sumOf { it.valueSat },
        unconfirmedSat = 0,
        utxos = utxos,
        history = emptyList(),
        addressCount = 24,
        ms = 0
    )

    /** Build a wallet on a fake vault — no Android, no network. */
    private fun wallet(): WalletStore {
        val v = FakeVault(MNEMONIC)
        val w = WalletStore(v, Blockbook("https://example.invalid"))
        w.loadSaved()
        return w
    }

    // ------------------------------------------------------------------ tests

    /**
     * THE test: decode the signed tx and enumerate every output.
     * Exactly two: the requested amount to the destination, and change back to us.
     */
    @Test
    fun feeIsNotRoutedAnywhere() {
        val mine = ours(0, 0)
        val utxos = listOf(utxo("11", 0, 100_000_000L, mine))   // 1 PRL
        val amount = 30_000_000L                                 // 0.3 PRL
        val feePerKb = 10_000L

        val w = wallet()
        val p = w.prepareSend(snap(utxos), DEST, amount, feePerKb)

        val parsed = Tx.parse(p.rawHex)
        println("OUTPUT_COUNT=${parsed.outputs.size}")
        parsed.outputs.forEachIndexed { i, o ->
            println("  out[$i] value=${o.valueSat} kind=${o.kind.take(20)}")
        }
        println("FEE=${p.feeSat}  IN=${utxos.sumOf { it.valueSat }}  OUT=${parsed.totalOut}")

        assertEquals("harus tepat 2 output (tujuan + change)", 2, parsed.outputs.size)

        // output 0 = destination, exact amount, exact script
        assertEquals(amount, parsed.outputs[0].valueSat)
        assertTrue(
            "output 0 bukan alamat tujuan",
            parsed.outputs[0].script.contentEquals(Pearl.scriptPubKey(DEST))
        )

        // output 1 = change, must belong to OUR wallet
        val changeAddr = ours(1, 0)
        assertTrue(
            "output 1 bukan change milik kita",
            parsed.outputs[1].script.contentEquals(Pearl.scriptPubKey(changeAddr))
        )

        // conservation: inputs = outputs + fee, and fee is the remainder (miner takes it)
        assertEquals(
            utxos.sumOf { it.valueSat },
            parsed.totalOut + p.feeSat
        )

        // nothing left over
        assertEquals(0L, utxos.sumOf { it.valueSat } - parsed.totalOut - p.feeSat)

        // no OP_RETURN, no unknown scripts, no second destination
        assertEquals(0, parsed.outputs.count { it.kind == "op_return" })
        assertEquals(0, parsed.outputs.count { it.kind == "unknown" })
        assertEquals(2, parsed.outputs.count { it.kind.startsWith("p2tr:") })
    }

    /** Single-output sweep: everything goes to the destination, only fee is deducted. */
    @Test
    fun sweepHasExactlyOneOutput() {
        val mine = ours(0, 0)
        val utxos = listOf(utxo("22", 1, 50_000_000L, mine))
        val w = wallet()

        // ask for the max → wallet sweeps, one output, no dust change
        val feePerKb = 10_000L
        val max = w.maxSendable(snap(utxos), feePerKb)
        val p = w.prepareSend(snap(utxos), DEST, max, feePerKb)
        val parsed = Tx.parse(p.rawHex)

        println("SWEEP_OUTPUTS=${parsed.outputs.size} value=${parsed.outputs[0].valueSat} fee=${p.feeSat}")
        assertEquals(1, parsed.outputs.size)
        assertEquals(max, parsed.outputs[0].valueSat)
        assertTrue(parsed.outputs[0].script.contentEquals(Pearl.scriptPubKey(DEST)))
        assertEquals(50_000_000L, parsed.totalOut + p.feeSat)
        assertEquals(0L, p.changeSat)
    }

    /** Dust change (< 546 sat) is absorbed into the fee, never sent to a third party. */
    @Test
    fun dustChangeIsAbsorbedNotRouted() {
        val mine = ours(0, 0)
        // choose an amount that leaves 1..545 sat of change
        val total = 10_000_000L
        val feePerKb = 10_000L
        val fee2 = Tx.estimateFee(1, 2, feePerKb)
        val amount = total - fee2 - 300L          // leaves 300 sat → below dust
        val w = wallet()
        val p = w.prepareSend(snap(listOf(utxo("33", 0, total, mine))), DEST, amount, feePerKb)
        val parsed = Tx.parse(p.rawHex)

        println("DUST_OUTPUTS=${parsed.outputs.size} fee=${p.feeSat} change=${p.changeSat}")
        assertEquals("change dust tidak boleh jadi output", 1, parsed.outputs.size)
        assertEquals(amount, parsed.outputs[0].valueSat)
        assertEquals(0L, p.changeSat)
        // the dust went to the fee (miner), and conservation still holds
        assertEquals(total, parsed.totalOut + p.feeSat)
        assertTrue("dust harus masuk fee", p.feeSat > fee2)
    }

    /** Multiple inputs from different addresses of the same account all sign correctly. */
    @Test
    fun multiAddressInputsSignCorrectly() {
        val utxos = listOf(
            utxo("44", 0, 40_000_000L, ours(0, 0)),
            utxo("55", 1, 35_000_000L, ours(0, 3)),
            utxo("66", 2, 30_000_000L, ours(0, 7))
        )
        val w = wallet()
        val amount = 90_000_000L
        val p = w.prepareSend(snap(utxos), DEST, amount, 10_000L)
        val parsed = Tx.parse(p.rawHex)

        println("MULTI_IN=${parsed.inputs.size} OUT=${parsed.outputs.size} fee=${p.feeSat}")
        assertEquals(3, parsed.inputs.size)
        assertEquals(amount, parsed.outputs[0].valueSat)
        assertEquals(utxos.sumOf { it.valueSat }, parsed.totalOut + p.feeSat)
        // witnesses present (one 64-byte schnorr sig per input)
        assertTrue(parsed.hasWitness)
        assertTrue(p.rawHex.length > 200)
    }

    /** Inputs must be spent in the exact order we signed them (sighash is index-bound). */
    @Test
    fun inputOrderMatchesSignedOrder() {
        // each UTXO is too small to cover the amount alone → both must be used
        val a = utxo("77", 5, 30_000_000L, ours(0, 1))
        val b = utxo("88", 9, 30_000_000L, ours(0, 2))
        val w = wallet()
        val p = w.prepareSend(snap(listOf(a, b)), DEST, 50_000_000L, 10_000L)
        val parsed = Tx.parse(p.rawHex)

        println("ORDER=" + parsed.inputs.joinToString { "${it.first.take(8)}:${it.second}" })
        assertEquals("harus 2 input", 2, parsed.inputs.size)
        assertEquals("77".repeat(32), parsed.inputs[0].first)
        assertEquals(5, parsed.inputs[0].second)
        assertEquals("88".repeat(32), parsed.inputs[1].first)
        assertEquals(9, parsed.inputs[1].second)
        assertEquals(60_000_000L, parsed.totalOut + p.feeSat)
    }

    /** Receive side: index 0 external is stable and matches the Go wallet. */
    @Test
    fun receiveAddressesAreCorrect() {
        assertEquals(
            "prl1pr6yuq8u2r95wjzzgpdy8cpnncpl7l8zgy6x5q0367pnc53s2famqg7pt74",
            ours(0, 0)
        )
        assertEquals(
            "prl1pyx3nlscz8rvsxqhcjtyqt2g5szuk9ss7m5saszu3afwwhvn9zp2sz62rhm",
            ours(0, 1)
        )
        assertEquals(
            "prl1puwagcza86exfh3cx6ur08wq7njc32e66ep0zje7le6c7cak8lqeq655gqa",
            ours(1, 0)
        )
        // external and internal chains must not collide
        assertTrue(ours(0, 0) != ours(1, 0))
        // every address decodes as bech32m p2tr with the prl HRP
        for (c in 0..1) for (i in 0 until 5) {
            val d = dev.pearlwallet.core.Bech32.decodeSegwit(ours(c, i))
            assertNotNull("alamat ${c}/${i} gagal decode", d)
            assertEquals("prl", d!!.hrp)
            assertEquals(1, d.witver)
            assertEquals(32, d.program.size)
        }
    }

    /** Insufficient balance must throw, never build a partial tx. */
    @Test
    fun insufficientBalanceThrows() {
        val w = wallet()
        val s = snap(listOf(utxo("99", 0, 1_000_000L, ours(0, 0))))
        val threw = try {
            w.prepareSend(s, DEST, 5_000_000L, 10_000L)
            false
        } catch (e: Exception) {
            println("THREW=${e.message}")
            true
        }
        assertTrue("harus menolak saldo kurang", threw)
    }

    /** Unconfirmed UTXOs must not be spendable. */
    @Test
    fun unconfirmedNotSpendable() {
        val mine = ours(0, 0)
        val u = Utxo("aa".repeat(32), 0, 100_000_000L, Pearl.scriptPubKey(mine), mine, 0, 0)
        val w = wallet()
        assertEquals(0L, w.totalSpendable(snap(listOf(u))))
        val threw = try {
            w.prepareSend(snap(listOf(u)), DEST, 1_000_000L, 10_000L)
            false
        } catch (e: Exception) {
            true
        }
        assertTrue(threw)
    }

    /** A UTXO that is not ours must be refused (no accidental spending of foreign coins). */
    @Test
    fun foreignUtxoRefused() {
        val foreign = "prl1p320jfqcr3d4xg7uway6djlq73a25j6rlatfwprx5nk088aguyv0sw5at54"
        val u = Utxo("bb".repeat(32), 0, 100_000_000L, Pearl.scriptPubKey(foreign), foreign, 6, 6)
        val w = wallet()
        val threw = try {
            w.prepareSend(snap(listOf(u)), DEST, 1_000_000L, 10_000L)
            false
        } catch (e: Exception) {
            println("FOREIGN_THREW=${e.message}")
            true
        }
        assertTrue("UTXO asing harus ditolak", threw)
    }

    /** Minimal in-memory vault so the wallet can be built without Android. */
    class FakeVault(private var mnemonic: String?) : dev.pearlwallet.core.SeedVault {
        override fun loadMnemonic(): String? = mnemonic
        override fun saveMnemonic(mnemonic: String) {
            this.mnemonic = mnemonic
        }
        override fun wipe() {
            mnemonic = null
        }
        override var indexerUrl: String = "https://example.invalid"
        override var gapLimit: Int = 12
    }
}
