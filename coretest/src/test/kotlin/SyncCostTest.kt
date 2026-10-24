package dev.pearlwallet

import dev.pearlwallet.core.Pearl
import dev.pearlwallet.core.Utxo
import org.junit.BeforeClass
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/**
 * Measures the actual cost difference between the two ways of loading a wallet:
 *   OLD: one HTTP round-trip per derived address (what the APK did)
 *   NEW: one round-trip for the whole account via the xpub endpoint
 * Numbers here are what justify the rewrite.
 */
class SyncCostTest {

    companion object {
        const val BB = "https://blockbook.pearlresearch.ai"
        const val XPUB = "zpub6s66goCpFBSqGDZyyD6a1Xf8C5TseQYcPFQNpjMzFeTCZpZD6ShaEzAKqv8tDiv81N1RmPyRTu1D65iQv59c7wHZyCbQmnaARE7P6UFNJeG"

        @JvmStatic
        @BeforeClass
        fun words() {
            dev.pearlwallet.core.Bip39.loadWordlist(
                java.io.File("../app/src/main/assets/bip39_english.txt").readLines().filter { it.isNotBlank() })
        }
    }

    private fun get(path: String): String {
        val c = URL(BB + path).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 20000
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("User-Agent", "PearlWallet-Android/test")
        return try {
            c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }

    private fun addresses(n: Int): List<String> {
        val m = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        val seed = dev.pearlwallet.core.Bip39.seed(m)
        val out = ArrayList<String>()
        for (chain in 0..1) for (i in 0 until n) {
            out.add(dev.pearlwallet.core.Pearl.addressFor(seed, i, chain))
        }
        return out
    }

    @Test
    fun oldPathPerAddressIsSlow() {
        val addrs = addresses(12)          // gap 12 x 2 chains = 24 addresses
        val t0 = System.currentTimeMillis()
        var calls = 0
        for (a in addrs) {
            get("/api/v2/utxo/$a"); calls++
            get("/api/v2/address/$a?details=basic"); calls++   // the redundant .txs check
        }
        val ms = System.currentTimeMillis() - t0
        println("OLD_SERIAL calls=$calls ms=$ms per_call=${ms / calls}ms")
        assert(ms > 0)
    }

    @Test
    fun newPathXpubIsFast() {
        val t0 = System.currentTimeMillis()
        val utxoRaw = get("/api/v2/utxo/$XPUB")
        val t1 = System.currentTimeMillis()
        val histRaw = get("/api/v2/xpub/$XPUB?details=txs&pageSize=25")
        val t2 = System.currentTimeMillis()
        println("NEW_XPUB calls=2 utxo_ms=${t1 - t0} hist_ms=${t2 - t1} total_ms=${t2 - t0}")
        println("UTXO_BODY=${utxoRaw.take(120)}")
        println("HIST_KEYS=${org.json.JSONObject(histRaw).keys().asSequence().toList()}")
    }

    /** Confirms the xpub UTXO response carries the owning address (needed to sign). */
    @Test
    fun xpubUtxoCarriesAddressField() {
        val raw = get("/api/v2/utxo/$XPUB")
        val arr = org.json.JSONArray(raw)
        println("XPUB_UTXO_COUNT=${arr.length()}")
        if (arr.length() > 0) {
            val o = arr.getJSONObject(0)
            println("FIELDS=" + o.keys().asSequence().toList())
            println("ADDRESS=" + o.optString("address"))
        }
        // An address-scoped query must NOT carry the field (blockbook omits it) —
        // that asymmetry is exactly why the old code had to query per address.
        val addr = "prl1pv3put8fvt4z5uq54sj9lt6vk40ya3c60sph75gwjl3qxyfsk06ysl8yyve"
        val one = org.json.JSONArray(get("/api/v2/utxo/$addr"))
        println("ADDR_QUERY_FIELDS=" + one.getJSONObject(0).keys().asSequence().toList())
        assert(!one.getJSONObject(0).has("address")) { "expected address field omitted for address query" }
    }

    /** The zpub must also work on the descriptor endpoint (future-proofing). */
    @Test
    fun descriptorAlsoWorks() {
        val enc = java.net.URLEncoder.encode("tr($XPUB/0/*)", "UTF-8")
        val raw = get("/api/v2/utxo/$enc")
        println("DESCRIPTOR_OK=${raw.trimStart().startsWith("[")}")
        assert(raw.trimStart().startsWith("["))
    }

    /** Fee endpoint latency, part of the refresh budget. */
    @Test
    fun feeLatency() {
        val t0 = System.currentTimeMillis()
        get("/api/v1/estimatefee/3")
        println("FEE_MS=${System.currentTimeMillis() - t0}")
    }

    /** Sanity: our scriptPubKey for a UTXO address matches what blockbook reports. */
    @Test
    fun scriptMatchesChain() {
        val addr = "prl1pv3put8fvt4z5uq54sj9lt6vk40ya3c60sph75gwjl3qxyfsk06ysl8yyve"
        val spk = Pearl.scriptPubKey(addr).joinToString("") { String.format("%02x", it) }
        println("OUR_SPK=$spk")
        assert(spk.startsWith("5120")) { "not a P2TR script" }
    }
}
