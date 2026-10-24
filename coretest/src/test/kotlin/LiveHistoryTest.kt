package dev.pearlwallet

import dev.pearlwallet.logic.Blockbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Live verification against the operator's real confirmed transaction.
 *
 * The bug: blockbook's AddressDetails carries `txs` as an INT (count) and the list in
 * `transactions`. Reading `txs` as an array always returned null, so the history was
 * permanently empty ("Belum ada transaksi") even with 18 on-chain txs.
 *
 * Ground truth tx (block 132629):
 *   53703d64e1611b89515ddd93209a15880c3b99f96ada26520c46e1aa6ce5ff61
 *   in  886505644 sat
 *   out   100000 sat -> prl1p2fxx...ghs69   (self-send, same as input address)
 *   out 886405488 sat -> prl1pp2x8a...wse6h (change, internal chain)
 *   fee      156 sat, vsize 154
 */
class LiveHistoryTest {

    private val bb = Blockbook("https://blockbook.pearlresearch.ai")

    private val inputAddr = "prl1p2fxxvt4g2kxv9zy2uqsdhag9cc9da4sje088cp9w0fzzumfsjejq0ghs69"
    private val changeAddr = "prl1pp2x8akf3h0e6jl4s8skxr2qhr25ku2rzpf2u7f98luk24y28gllqrwse6h"
    private val txid = "53703d64e1611b89515ddd93209a15880c3b99f96ada26520c46e1aa6ce5ff61"

    @Test
    fun historyIsNotEmpty() {
        val h = bb.historyForAddress(inputAddr, pageSize = 25)
        println("HISTORY_COUNT=${h.size}")
        assertTrue("history tidak boleh kosong", h.isNotEmpty())
        // the address has 18 txs on chain; the page holds up to 25
        assertTrue("harus >= 15 tx", h.size >= 15)
    }

    @Test
    fun ourTransactionAppears() {
        val h = bb.historyForAddress(inputAddr, pageSize = 25)
        val ours = h.firstOrNull { it.txid == txid }
        println("FOUND=${ours != null}")
        assertTrue("tx kita harus muncul di history", ours != null)
        println("  txid=${ours!!.txid.take(20)}…")
        println("  height=${ours.height} confs=${ours.confirmations} pending=${ours.isPending}")
        println("  delta=${ours.deltaSat} fee=${ours.feeSat}")
        assertTrue("harus sudah terkonfirmasi", !ours.isPending)
        assertEquals(132629, ours.height)
    }

    @Test
    fun changeAddressHistoryHasOurTx() {
        val h = bb.historyForAddress(changeAddr, pageSize = 10)
        println("CHANGE_HISTORY=${h.size}")
        assertTrue("alamat change harus punya history", h.isNotEmpty())
        val ours = h.firstOrNull { it.txid == txid }
        println("CHANGE_FOUND=${ours != null}")
        assertTrue("tx kita harus muncul juga di alamat change", ours != null)
        // from the change address's point of view this is an inflow
        println("CHANGE_DELTA=${ours!!.deltaSat}")
        assertEquals(886405488L, ours.deltaSat)
    }

    @Test
    fun sortingIsNewestFirst() {
        val h = bb.historyForAddress(inputAddr, pageSize = 25)
        val times = h.map { it.blockTime }
        println("TIMES=$times")
        for (i in 1 until times.size) {
            assertTrue("harus urut terbaru dulu: ${times[i - 1]} >= ${times[i]}", times[i - 1] >= times[i])
        }
    }
}
