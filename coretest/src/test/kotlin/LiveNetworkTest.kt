package dev.pearlwallet

import dev.pearlwallet.core.Bip32
import dev.pearlwallet.core.Bip39
import dev.pearlwallet.core.Pearl
import dev.pearlwallet.core.Tx
import dev.pearlwallet.core.Utxo
import org.json.JSONObject
import org.junit.BeforeClass
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/**
 * Live end-to-end: derive from a mnemonic, then ask the real Pearl indexer about the
 * resulting zpub / addresses. Proves derivation + address encoding + indexer agree.
 */
class LiveNetworkTest {

    companion object {
        const val BB = "https://blockbook.pearlresearch.ai"
        @JvmStatic
        @BeforeClass
        fun loadWords() {
            Bip39.loadWordlist(java.io.File("../app/src/main/assets/bip39_english.txt")
                .readLines().filter { it.isNotBlank() })
        }
    }

    private fun get(path: String): String {
        val c = URL(BB + path).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 20000
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("User-Agent", "PearlWallet-Android/test")
        return c.inputStream.bufferedReader().readText().also { c.disconnect() }
    }

    @Test
    fun indexerAcceptsOurXpub() {
        val m = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        val seed = Bip39.seed(m)
        val acct = Bip32.derivePath(seed, "m/86'/808276'/0'")
        val xpub = acct.neuteredString()

        val o = JSONObject(get("/api/v2/xpub/$xpub?details=basic"))
        println("XPUB_BALANCE=" + o.optString("balance"))
        println("XPUB_TXS=" + o.optString("txs"))
        // A 200 with our address echoed back means Blockbook parsed the zpub fine.
        assert(o.optString("address") == xpub) { "indexer did not echo our xpub" }
    }

    @Test
    fun derivedAddressIsRecognisedByIndexer() {
        val m = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        val seed = Bip39.seed(m)
        val addr = Pearl.addressFor(seed, 0, 0)
        println("DERIVED=$addr")

        // utxo endpoint: [] for an unused address, but a valid 200 either way
        val utxoRaw = get("/api/v2/utxo/$addr")
        println("UTXO_JSON=$utxoRaw")
        assert(utxoRaw.trimStart().startsWith("[")) { "unexpected utxo response: $utxoRaw" }

        // address endpoint must resolve (not 404) => bech32m decoded correctly
        val addrRaw = get("/api/v2/address/$addr?details=basic")
        val a = JSONObject(addrRaw)
        println("ADDR_BALANCE=" + a.optString("balance"))
        assert(a.optString("address") == addr) { "indexer rejected our address" }
    }

    @Test
    fun feeEstimateAndTip() {
        val tip = JSONObject(get("/api/v2")).getJSONObject("blockbook").getInt("bestHeight")
        val fee = JSONObject(get("/api/v1/estimatefee/3")).optString("result")
        println("TIP=$tip FEE_PRLL_PER_KB=$fee")
        assert(tip > 100_000) { "tip too low: $tip" }
        assert(fee.toDouble() > 0) { "bad fee: $fee" }
    }

    /** Real UTXOs from a live address, then confirm the fee math is sane. */
    @Test
    fun realUtxoFeeMath() {
        // a known busy Pearl address taken from on-chain history
        val addr = "prl1pv3put8fvt4z5uq54sj9lt6vk40ya3c60sph75gwjl3qxyfsk06ysl8yyve"
        val arr = org.json.JSONArray(get("/api/v2/utxo/$addr"))
        println("REAL_UTXOS=" + arr.length())
        assert(arr.length() > 0) { "expected live utxos" }
        var total = 0L
        for (i in 0 until arr.length()) {
            total += arr.getJSONObject(i).optString("value", "0").toLong()
        }
        val spk = Pearl.scriptPubKey(addr)
        println("SPK=" + spk.joinToString("") { String.format("%02x", it) })
        println("TOTAL_SAT=$total  PRL=" + Tx.satToPrl(total))
        val fee = Tx.estimateFee(1, 2, 100_000L)
        println("FEE_1IN_2OUT_100satkB=$fee")
        assert(fee in 1..100_000) { "implausible fee: $fee" }
    }
}
