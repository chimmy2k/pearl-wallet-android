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
 * Replicates the exact transaction shown in the operator's screenshot, so the numbers
 * on screen are pinned by a test instead of eyeballed.
 *
 * Screenshot said:
 *   JUMLAH           0.001 PRL
 *   Kembali ke dompet : 8.86405488 PRL
 *   Biaya ke miner    : 0.00000156 PRL
 *   Total keluar      : 8.86505644 PRL dari 1 koin
 *   OUTPUT: tujuan 0.001 | kembali 8.86405488 | total 8.86505488
 */
class ScreenshotRegressionTest {

    companion object {
        const val MNEMONIC =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

        @JvmStatic
        @BeforeClass
        fun words() {
            dev.pearlwallet.core.Bip39.loadWordlist(
                java.io.File("../app/src/main/assets/bip39_english.txt")
                    .readLines().filter { it.isNotBlank() }
            )
        }
    }

    @Test
    fun reproducesScreenshotNumbers() {
        val v = SendCorrectnessTest.FakeVault(MNEMONIC)
        val w = WalletStore(v, Blockbook("https://example.invalid"))
        w.loadSaved()

        val mine = w.receiveAddress(0)
        // one coin of 8.86505644 PRL
        val coin = Utxo(
            txid = "a1".repeat(32), vout = 0, valueSat = 886_505_644L,
            scriptPubKey = Pearl.scriptPubKey(mine), address = mine,
            height = 130_000, confirmations = 10
        )
        val snap = WalletStore.Snapshot(
            confirmedSat = coin.valueSat, unconfirmedSat = 0,
            utxos = listOf(coin), history = emptyList(), addressCount = 24, ms = 0
        )

        // the screenshot's fee corresponds to the 6-block tier: 1004 sat/kB
        val feePerKb = 1004L
        val amount = 100_000L                      // 0.001 PRL
        val p = w.prepareSend(snap, "prl1p320jfqcr3d4xg7uway6djlq73a25j6rlatfwprx5nk088aguyv0sw5at54", amount, feePerKb)

        println("FEE=${p.feeSat} CHANGE=${p.changeSat} TOTAL_IN=${p.totalIn} VSIZE=${p.vsize} RATE=${"%.2f".format(p.satPerVb)}")
        println("OUTPUTS=" + p.outputs.map { it.valueSat })

        // exactly what the screenshot shows
        assertEquals("biaya harus 156 sat", 156L, p.feeSat)
        assertEquals("kembalian harus 8.86405488 PRL", 886_405_488L, p.changeSat)
        assertEquals("dana terpakai harus 8.86505644 PRL", 886_505_644L, p.totalIn)
        assertEquals("harus 1 koin", 1, p.inputs)
        assertEquals("harus 2 output", 2, p.outputs.size)
        assertEquals("tujuan 0.001 PRL", 100_000L, p.outputs[0].valueSat)
        assertEquals("kembali 8.86405488 PRL", 886_405_488L, p.outputs[1].valueSat)
        assertEquals("total output 8.86505488 PRL", 886_505_488L, p.outputs.sumOf { it.valueSat })

        // conservation: outputs + fee == input, nothing missing
        assertEquals(p.totalIn, p.outputs.sumOf { it.valueSat } + p.feeSat)

        // fee rate is the one we asked for
        assertEquals(155L, p.vsize)
        assertTrue("rate harus ~1 sat/vB", p.satPerVb > 0.9 && p.satPerVb < 1.1)
    }

    /** The same send at each fee tier must only change the fee, never the amount. */
    @Test
    fun feeTiersOnlyChangeFee() {
        val v = SendCorrectnessTest.FakeVault(MNEMONIC)
        val w = WalletStore(v, Blockbook("https://example.invalid"))
        w.loadSaved()
        val mine = w.receiveAddress(0)
        val coin = Utxo("a2".repeat(32), 0, 886_505_644L, Pearl.scriptPubKey(mine), mine, 130_000, 10)
        val snap = WalletStore.Snapshot(coin.valueSat, 0, listOf(coin), emptyList(), 24, 0)

        // live network estimates measured from blockbook.pearlresearch.ai
        val tiers = mapOf("cepat" to 117_721L, "normal" to 81_441L, "lambat" to 1_004L)
        val fees = ArrayList<Long>()
        for ((name, rate) in tiers) {
            val p = w.prepareSend(snap, "prl1p320jfqcr3d4xg7uway6djlq73a25j6rlatfwprx5nk088aguyv0sw5at54", 100_000L, rate)
            println("$name rate=$rate fee=${p.feeSat} rate_satvb=${"%.2f".format(p.satPerVb)}")
            assertEquals("jumlah tidak boleh berubah", 100_000L, p.outputs[0].valueSat)
            assertEquals("konservasi", p.totalIn, p.outputs.sumOf { it.valueSat } + p.feeSat)
            fees.add(p.feeSat)
        }
        // higher tier must cost more
        assertTrue("cepat harus lebih mahal dari normal", fees[0] > fees[1])
        assertTrue("normal harus lebih mahal dari lambat", fees[1] > fees[2])
    }
}
