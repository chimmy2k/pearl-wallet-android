package dev.pearlwallet

import dev.pearlwallet.core.Bip32
import dev.pearlwallet.core.Bip39
import dev.pearlwallet.core.Pearl
import dev.pearlwallet.core.Utxo
import dev.pearlwallet.logic.Blockbook
import dev.pearlwallet.logic.WalletStore
import org.junit.BeforeClass
import org.junit.Test

/**
 * Emits a real signed send for the Go harness to validate with Pearl's own script
 * engine (`wcheck verifyall`). A signature that only round-trips through our own code
 * proves nothing; consensus acceptance is the only proof.
 */
class EmitSendForConsensus {

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

    private fun hex(b: ByteArray) = b.joinToString("") { String.format("%02x", it) }

    @Test
    fun emit() {
        val seed = Bip39.seed(MNEMONIC)
        val acct = Bip32.derivePath(seed, Pearl.accountPath())
        fun ours(c: Int, i: Int) = Pearl.p2trAddress(acct.child(c).child(i).pub)

        // 3 inputs from 3 different addresses of the same account (worst case)
        val owners = listOf(ours(0, 0), ours(0, 4), ours(1, 0))
        val values = listOf(40_000_000L, 25_000_000L, 35_000_000L)
        val utxos = owners.mapIndexed { i, o ->
            Utxo(
                txid = "${i + 1}1".repeat(32),
                vout = i,
                valueSat = values[i],
                scriptPubKey = Pearl.scriptPubKey(o),
                address = o,
                confirmations = 12
            )
        }

        val vault = SendCorrectnessTest.FakeVault(MNEMONIC)
        val w = WalletStore(vault, Blockbook("https://example.invalid"))
        w.loadSaved()

        val snap = WalletStore.Snapshot(
            confirmedSat = values.sum(),
            unconfirmedSat = 0,
            utxos = utxos,
            history = emptyList(),
            addressCount = 24,
            ms = 0
        )
        val p = w.prepareSend(snap, DEST, 90_000_000L, 10_000L)

        println("SEND_TX=" + p.rawHex)
        // emit in the EXACT order the wallet chose (largest-first), which is the tx input order
        println("SEND_UTXOS=" + p.spent.joinToString(",", "[", "]") {
            "{\"script\":\"${hex(it.scriptPubKey!!)}\",\"value\":${it.valueSat}}"
        })
        println("SEND_TXID=" + p.txid)
        println("SEND_FEE=" + p.feeSat + " CHANGE=" + p.changeSat + " INPUTS=" + p.inputs)
        println("SEND_IN=" + p.totalIn + " OUT=" + p.outputs.sumOf { it.valueSat })

        // conservation must hold exactly
        check(p.totalIn == p.outputs.sumOf { it.valueSat } + p.feeSat) { "value leaked" }

        // the change output must be OUR internal-chain address
        val parsed = dev.pearlwallet.core.Tx.parse(p.rawHex)
        println("SEND_CHANGE_SCRIPT=" + hex(parsed.outputs.last().script))
        println("SEND_CHANGE_EXPECT=" + hex(Pearl.scriptPubKey(ours(1, 0))))
        check(hex(parsed.outputs.last().script) == hex(Pearl.scriptPubKey(ours(1, 0)))) {
            "change bukan milik kita"
        }
    }
}
