package dev.pearlwallet

import dev.pearlwallet.logic.Blockbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the exact response shape that broke the history list.
 *
 * Blockbook `AddressDetails`:
 *   "txs": 18                 <- INT, the count
 *   "transactions": [ ... ]   <- the actual list
 *
 * The old parser did `optJSONArray("txs")`, which is always null, so the UI showed
 * "Belum ada transaksi" no matter how many transactions the account had.
 * These fixtures are trimmed copies of real blockbook.pearlresearch.ai responses.
 */
class HistoryShapeTest {

    private val bb = Blockbook("https://example.invalid")

    /** Real shape: txs is an int, transactions is the array. */
    private val realShape = """
    {
      "page": 1, "totalPages": 1, "itemsOnPage": 5,
      "address": "prl1p2fxxvt4g2kxv9zy2uqsdhag9cc9da4sje088cp9w0fzzumfsjejq0ghs69",
      "balance": "0", "totalReceived": "886505644", "totalSent": "886505644",
      "txs": 18,
      "transactions": [
        {
          "txid": "53703d64e1611b89515ddd93209a15880c3b99f96ada26520c46e1aa6ce5ff61",
          "blockHeight": 132629, "confirmations": 1, "blockTime": 1792800846,
          "fees": "156",
          "vin": [{"addresses": ["prl1p2fxxvt4g2kxv9zy2uqsdhag9cc9da4sje088cp9w0fzzumfsjejq0ghs69"], "value": "886505644"}],
          "vout": [
            {"addresses": ["prl1p2fxxvt4g2kxv9zy2uqsdhag9cc9da4sje088cp9w0fzzumfsjejq0ghs69"], "value": "100000"},
            {"addresses": ["prl1pp2x8akf3h0e6jl4s8skxr2qhr25ku2rzpf2u7f98luk24y28gllqrwse6h"], "value": "886405488"}
          ]
        },
        {
          "txid": "ebc115f3e25d031620dc1111111111111111111111111111111111111111111",
          "blockHeight": 130000, "confirmations": 2600, "blockTime": 1792796491,
          "fees": "200",
          "vin": [{"addresses": ["prl1psender1111111111111111111111111111111111111111111111111"], "value": "900000000"}],
          "vout": [
            {"addresses": ["prl1p2fxxvt4g2kxv9zy2uqsdhag9cc9da4sje088cp9w0fzzumfsjejq0ghs69"], "value": "886505644"}
          ]
        }
      ]
    }
    """.trimIndent()

    private val addr = "prl1p2fxxvt4g2kxv9zy2uqsdhag9cc9da4sje088cp9w0fzzumfsjejq0ghs69"
    private val change = "prl1pp2x8akf3h0e6jl4s8skxr2qhr25ku2rzpf2u7f98luk24y28gllqrwse6h"

    /** The regression: an int `txs` must not wipe out the list. */
    @Test
    fun transactionsArrayIsReadNotTheCount() {
        val (info, list) = bb.parseAccountResponse(realShape, setOf(addr, change))
        println("COUNT=${info.txs} LIST=${list.size}")
        assertEquals("jumlah dari field txs", 18, info.txs)
        assertEquals("daftar dari field transactions", 2, list.size)
        assertTrue("daftar tidak boleh kosong walau txs berupa angka", list.isNotEmpty())
    }

    /** Delta must be computed against OUR address set. */
    @Test
    fun deltaIsComputedFromOwnAddresses() {
        val (_, list) = bb.parseAccountResponse(realShape, setOf(addr, change))
        val ours = list.first { it.txid.startsWith("53703d64") }
        // self-send: 886505644 in, 100000+886405488 out -> net -156 (the fee)
        println("SELF_DELTA=${ours.deltaSat}")
        assertEquals(-156L, ours.deltaSat)

        val incoming = list.first { it.txid.startsWith("ebc115f3") }
        println("INCOMING_DELTA=${incoming.deltaSat}")
        assertEquals(886505644L, incoming.deltaSat)
    }

    /** Confirmations must survive parsing so the UI can show pending vs mined. */
    @Test
    fun confirmationsAreParsed() {
        val (_, list) = bb.parseAccountResponse(realShape, setOf(addr, change))
        val ours = list.first { it.txid.startsWith("53703d64") }
        assertEquals(1, ours.confirmations)
        assertTrue(!ours.isPending)
        assertEquals(132629, ours.height)
        assertEquals(156L, ours.feeSat)
    }

    /** Newest first. */
    @Test
    fun sortedNewestFirst() {
        val (_, list) = bb.parseAccountResponse(realShape, setOf(addr, change))
        assertTrue(list[0].blockTime >= list[1].blockTime)
        assertTrue(list[0].txid.startsWith("53703d64"))
    }

    /** A mempool tx (blockTime 0, confirmations 0) must sort first and read as pending. */
    @Test
    fun pendingTransactionIsDetected() {
        val body = """
        {
          "balance": "0", "txs": 1,
          "transactions": [{
            "txid": "aa11bb22cc33dd44ee55ff6677889900aa11bb22cc33dd44ee55ff6677889900",
            "confirmations": 0, "blockTime": 0, "fees": "100",
            "vin": [{"addresses": ["$addr"], "value": "5000000"}],
            "vout": [{"addresses": ["$change"], "value": "4999900"}]
          }]
        }
        """.trimIndent()
        val (_, list) = bb.parseAccountResponse(body, setOf(addr, change))
        assertEquals(1, list.size)
        assertTrue("harus terdeteksi pending", list[0].isPending)
        assertEquals(0, list[0].confirmations)
    }

    /** An empty account must not throw and must report zero. */
    @Test
    fun emptyAccountIsSafe() {
        val body = """{"balance":"0","txs":0}"""
        val (info, list) = bb.parseAccountResponse(body, emptySet())
        assertEquals(0, info.txs)
        assertEquals(0, info.confirmedSat)
        assertTrue(list.isEmpty())
    }

    /** Some blockbook builds put the list under "txs" — the fallback must still work. */
    @Test
    fun fallbackToTxsArrayStillWorks() {
        val body = """
        {
          "balance": "123", "txs": [
            {"txid": "cc", "confirmations": 3, "blockTime": 100, "fees": "1",
             "vin": [], "vout": [{"addresses": ["$addr"], "value": "123"}]}
          ]
        }
        """.trimIndent()
        val (info, list) = bb.parseAccountResponse(body, setOf(addr))
        println("FALLBACK count=${info.txs} list=${list.size}")
        assertEquals(1, list.size)
        assertEquals(123L, list[0].deltaSat)
    }

    /** Entries without an addresses array must be skipped, not crash. */
    @Test
    fun malformedEntriesDoNotCrash() {
        val body = """
        {
          "balance": "0", "txs": 1,
          "transactions": [{
            "txid": "dd", "confirmations": 1, "blockTime": 5, "fees": "0",
            "vin": [{"value": "1000"}],
            "vout": [{"value": "900"}, {"addresses": [], "value": "50"}]
          }]
        }
        """.trimIndent()
        val (_, list) = bb.parseAccountResponse(body, setOf(addr))
        assertEquals(1, list.size)
        assertEquals("tanpa alamat, delta harus 0", 0L, list[0].deltaSat)
    }
}
