package dev.pearlwallet.logic

import dev.pearlwallet.core.Utxo
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class TxEntry(
    val txid: String,
    val blockTime: Long,
    val height: Int,
    val deltaSat: Long,      // net for this wallet (negative = sent)
    val peer: String,        // counterparty address (first non-ours)
    val feeSat: Long,
    val confirmations: Int = 0
) {
    val isPending: Boolean get() = confirmations <= 0
}

data class BalanceInfo(val confirmedSat: Long, val unconfirmedSat: Long, val txs: Int)

/**
 * Blockbook REST client (Pearl indexer). Read + broadcast only.
 *
 * Uses HttpURLConnection from the platform instead of OkHttp: same behaviour for a
 * handful of JSON calls, and it removes okhttp+okio (~900 KB) from the APK.
 */
class Blockbook(private var base: String) {

    fun setBase(url: String) {
        base = url.trim().trimEnd('/')
    }

    private fun open(path: String): HttpURLConnection {
        val c = URL(base + path).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 25_000
        c.requestMethod = "GET"
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("User-Agent", "PearlWallet-Android/1.0")
        return c
    }

    private fun readAll(c: HttpURLConnection): String {
        val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
        if (stream == null) return ""
        return BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
    }

    /** GET with a small retry: transient blips must not kill the whole refresh. */
    private fun get(path: String, attempts: Int = 3): String {
        var last: String? = null
        for (a in 1..attempts) {
            var c: HttpURLConnection? = null
            try {
                c = open(path)
                val code = c.responseCode
                val body = readAll(c)
                if (code in 200..299) return body
                // 4xx is our fault, do not retry
                if (code in 400..499) throw RuntimeException("HTTP $code: ${body.take(160)}")
                last = "HTTP $code: ${body.take(160)}"
            } catch (e: RuntimeException) {
                throw e
            } catch (e: Exception) {
                last = e.message
            } finally {
                c?.disconnect()
            }
            if (a < attempts) Thread.sleep(300L * a)
        }
        throw RuntimeException(last ?: "gagal menghubungi indexer")
    }

    fun bestHeight(): Int = try {
        JSONObject(get("/api/v2")).getJSONObject("blockbook").getInt("bestHeight")
    } catch (e: Exception) {
        -1
    }

    /**
     * Every UTXO of the whole account in ONE request.
     *
     * Blockbook's `Utxo` struct carries `address` (json omitempty) and only populates it
     * for xpub/descriptor queries — address-scoped queries omit it.
     */
    fun utxosForXpub(xpub: String): List<Utxo> {
        val arr = JSONArray(get("/api/v2/utxo/$xpub"))
        val out = ArrayList<Utxo>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val addr = o.optString("address", "")
            out.add(
                Utxo(
                    txid = o.getString("txid"),
                    vout = o.getInt("vout"),
                    valueSat = o.optString("value", "0").toLongOrNull() ?: 0L,
                    scriptPubKey = if (addr.isEmpty()) null else dev.pearlwallet.core.Pearl.scriptPubKey(addr),
                    address = addr.ifEmpty { null },
                    height = o.optInt("height", 0),
                    confirmations = o.optInt("confirmations", 0)
                )
            )
        }
        return out
    }

    /**
     * Balance + history for the account in ONE request (`details=txs`).
     *
     * Blockbook's `AddressDetails` has TWO different fields and they are easy to confuse:
     *   txs          int      -> NUMBER of transactions   (`json:"txs"`)
     *   transactions []*Tx    -> the actual LIST          (`json:"transactions"`)
     * Reading `txs` as an array always yields null, which is why the history used to be
     * permanently empty. The fallback keeps older/newer blockbook variants working.
     */
    fun account(xpub: String, mine: Set<String>, pageSize: Int = 25): Pair<BalanceInfo, List<TxEntry>> =
        parseAccountResponse(get("/api/v2/xpub/$xpub?details=txs&pageSize=$pageSize"), mine)

    /**
     * Parses an `AddressDetails` JSON body. Split out from the network call so the
     * `txs` (count) vs `transactions` (list) contract can be pinned by an offline test.
     */
    fun parseAccountResponse(body: String, mine: Set<String>): Pair<BalanceInfo, List<TxEntry>> {
        val o = JSONObject(body)
        val conf = o.optString("balance", "0").toLongOrNull() ?: 0L
        val unc = o.optString("unconfirmedBalance", "0").toLongOrNull() ?: 0L
        val count = o.optInt("txs", 0)
        val info = BalanceInfo(conf, unc, count)

        val arr = o.optJSONArray("transactions") ?: o.optJSONArray("txs") ?: return info to emptyList()
        val list = ArrayList<TxEntry>(arr.length())
        for (i in 0 until arr.length()) {
            list.add(parseTx(arr.getJSONObject(i), mine))
        }
        // newest first: blockTime 0 means still in the mempool
        list.sortByDescending { if (it.blockTime > 0) it.blockTime else Long.MAX_VALUE }
        return info to list
    }

    fun balance(xpub: String): BalanceInfo {
        val o = JSONObject(get("/api/v2/xpub/$xpub?details=basic"))
        val conf = o.optString("balance", "0").toLongOrNull() ?: 0L
        val unc = o.optString("unconfirmedBalance", "0").toLongOrNull() ?: 0L
        return BalanceInfo(conf, unc, o.optInt("txs", 0))
    }

    /** UTXOs for a single address (fallback path). */
    fun utxos(address: String): List<Utxo> {
        val arr = JSONArray(get("/api/v2/utxo/$address"))
        val out = ArrayList<Utxo>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                Utxo(
                    txid = o.getString("txid"),
                    vout = o.getInt("vout"),
                    valueSat = o.optString("value", "0").toLongOrNull() ?: 0L,
                    scriptPubKey = dev.pearlwallet.core.Pearl.scriptPubKey(address),
                    address = address,
                    height = o.optInt("height", 0),
                    confirmations = o.optInt("confirmations", 0)
                )
            )
        }
        return out
    }

    fun history(xpub: String, mine: Set<String>, pageSize: Int = 25): List<TxEntry> =
        account(xpub, mine, pageSize).second

    /**
     * History for a single address. Same `transactions` field, same parser — used by the
     * fallback path and by the live verification test.
     */
    fun historyForAddress(address: String, mine: Set<String> = setOf(address), pageSize: Int = 25): List<TxEntry> {
        val o = JSONObject(get("/api/v2/address/$address?details=txs&pageSize=$pageSize"))
        val arr = o.optJSONArray("transactions") ?: return emptyList()
        val list = ArrayList<TxEntry>(arr.length())
        for (i in 0 until arr.length()) list.add(parseTx(arr.getJSONObject(i), mine))
        list.sortByDescending { if (it.blockTime > 0) it.blockTime else Long.MAX_VALUE }
        return list
    }

    private fun parseTx(t: JSONObject, mine: Set<String>): TxEntry {
        var inMine = 0L
        var outMine = 0L
        var peer = ""
        val vin = t.optJSONArray("vin") ?: JSONArray()
        for (i in 0 until vin.length()) {
            val v = vin.getJSONObject(i)
            val addrs = v.optJSONArray("addresses") ?: continue
            val value = v.optString("value", "0").toLongOrNull() ?: 0L
            for (j in 0 until addrs.length()) {
                val a = addrs.getString(j)
                if (a in mine) inMine += value else if (peer.isEmpty()) peer = a
            }
        }
        val vout = t.optJSONArray("vout") ?: JSONArray()
        for (i in 0 until vout.length()) {
            val v = vout.getJSONObject(i)
            val addrs = v.optJSONArray("addresses") ?: continue
            val value = v.optString("value", "0").toLongOrNull() ?: 0L
            for (j in 0 until addrs.length()) {
                val a = addrs.getString(j)
                if (a in mine) outMine += value else if (peer.isEmpty()) peer = a
            }
        }
        return TxEntry(
            txid = t.optString("txid"),
            blockTime = t.optLong("blockTime", 0L),
            height = t.optInt("blockHeight", 0),
            deltaSat = outMine - inMine,
            peer = peer,
            feeSat = t.optString("fees", "0").toLongOrNull() ?: 0L,
            confirmations = t.optInt("confirmations", 0)
        )
    }

    /** sat/kB estimate for confirmation within `blocks`. */
    fun feePerKb(blocks: Int): Long {
        val o = JSONObject(get("/api/v1/estimatefee/$blocks"))
        val prlPerKb = o.optString("result", "-1").toDoubleOrNull() ?: -1.0
        if (prlPerKb <= 0) return 10_000L
        val sat = (prlPerKb * 1e8).toLong()
        return sat.coerceIn(1_000L, 5_000_000L)
    }

    /** Broadcast a signed tx; returns the txid on success. */
    fun broadcast(rawHex: String): String {
        var c: HttpURLConnection? = null
        try {
            c = URL(base + "/api/v2/sendtx/").openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "text/plain")
            c.setRequestProperty("User-Agent", "PearlWallet-Android/1.0")
            c.outputStream.use { it.write(rawHex.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val body = readAll(c)
            val json = try {
                JSONObject(body)
            } catch (e: Exception) {
                throw RuntimeException("HTTP $code: ${body.take(160)}")
            }
            if (code in 200..299) {
                json.optString("result").takeIf { it.isNotEmpty() }?.let { return it }
            }
            val err = json.optString("error")
            // "-27: transaction already in block chain" / "already known" == it IS sent
            if (err.contains("already", ignoreCase = true)) {
                throw RuntimeException("Transaksi sudah pernah dikirim: $err")
            }
            throw RuntimeException(err.ifEmpty { "HTTP $code: ${body.take(160)}" })
        } catch (e: java.io.IOException) {
            // Timeout / drop AFTER the request left: the tx may well be accepted.
            throw RuntimeException(
                "koneksi terputus saat mengirim. Transaksi MUNGKIN sudah terkirim — " +
                    "cek riwayat sebelum mencoba lagi. (${e.message})"
            )
        } finally {
            c?.disconnect()
        }
    }
}
