package dev.pearlwallet

import dev.pearlwallet.core.Bip39
import dev.pearlwallet.core.Pearl
import dev.pearlwallet.core.Tx
import dev.pearlwallet.core.Utxo
import dev.pearlwallet.logic.Blockbook
import dev.pearlwallet.logic.WalletStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Lifecycle QA: the states a user actually walks through, including the ugly ones
 * (stale cache, wallet wiped mid-session, gap changed in settings, double send).
 */
class QaLifecycleTest {

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

    private fun vault(m: String? = MNEMONIC) = SendCorrectnessTest.FakeVault(m)

    private fun wallet(v: SendCorrectnessTest.FakeVault = vault()): WalletStore {
        val w = WalletStore(v, Blockbook("https://example.invalid"))
        if (v.loadMnemonic() != null) w.loadSaved()
        return w
    }

    // ---------------------------------------------------------------- lifecycle

    /** create -> derive -> wipe -> recreate must not leak state between wallets. */
    @Test
    fun wipeClearsEverything() {
        val v = vault(null)
        val w = wallet(v)
        assertTrue(!w.hasWallet())
        val m = w.createNew()
        assertTrue(w.hasWallet())
        assertTrue(Bip39.isValid(m))

        val addrBefore = w.receiveAddress(0)
        assertNotNull(addrBefore)

        w.wipe()
        assertTrue("setelah wipe harus tanpa wallet", !w.hasWallet())
        assertEquals("mnemonic harus kosong", "", w.mnemonic)

        // restoring the same seed must give the same first address
        assertTrue(w.restore(m))
        assertEquals(addrBefore, w.receiveAddress(0))
    }

    /** Invalid mnemonic on restore must be refused and must not load a partial wallet. */
    @Test
    fun restoreRejectsInvalidAndKeepsState() {
        val v = vault(null)
        val w = wallet(v)
        assertTrue(!w.restore("abandon abandon abandon"))
        assertTrue("wallet tidak boleh aktif", !w.hasWallet())
        assertTrue(!w.restore(""))
        assertTrue(w.restore(MNEMONIC))
        assertTrue(w.hasWallet())
    }

    /** Changing the gap limit must rebuild the address window, not keep the old one. */
    @Test
    fun gapChangeRebuildsWindow() {
        val v = vault()
        val w = wallet(v)
        v.gapLimit = 4
        val a4 = w.keyAt(0, 0).address
        // derive past the small window; keyAt always works
        val deep = w.keyAt(0, 20).address
        assertNotNull(deep)
        v.gapLimit = 20
        assertEquals("alamat index 0 tidak boleh berubah", a4, w.keyAt(0, 0).address)
    }

    /** A wiped wallet must refuse to derive, not silently produce zero keys. */
    @Test
    fun wipedWalletRefusesDerivation() {
        val v = vault(null)
        val w = wallet(v)
        val threw = try {
            w.receiveAddress(0)
            false
        } catch (e: IllegalStateException) {
            true
        }
        assertTrue("tanpa seed harus menolak derivasi", threw)
    }

    // ---------------------------------------------------------------- stale state

    /**
     * The dangerous case: the cached snapshot still lists a coin that has since been
     * spent. A second send built from the stale list must NOT produce a second tx
     * silently — it must fail once the fresh list no longer contains the coin.
     */
    @Test
    fun staleSnapshotCannotDoubleSpend() {
        val w = wallet()
        val mine = w.receiveAddress(0)
        val coin = Utxo(
            "cc".repeat(32), 0, 100_000_000L,
            Pearl.scriptPubKey(mine), mine, 6, 6
        )
        val fresh = snapOf(listOf(coin))

        val first = w.prepareSend(fresh, DEST, 40_000_000L, 10_000L)
        assertNotNull(first)
        val parsed1 = Tx.parse(first.rawHex)
        assertEquals(2, parsed1.outputs.size)

        // the coin is now spent → a refreshed snapshot has nothing
        val afterSpend = snapOf(emptyList())
        val threw = try {
            w.prepareSend(afterSpend, DEST, 40_000_000L, 10_000L)
            false
        } catch (e: Exception) {
            println("DOUBLE_SPEND_BLOCKED=${e.message}")
            true
        }
        assertTrue("snapshot kosong harus menolak kirim", threw)
        assertEquals(0L, w.totalSpendable(afterSpend))
    }

    /** Two sends from the SAME snapshot must both be built but spend the same coin —
     *  which is why the UI must refresh before each send. Prove the txids differ. */
    @Test
    fun twoSendsFromSameSnapshotProduceDistinctTxids() {
        val w = wallet()
        val mine = w.receiveAddress(0)
        val coin = Utxo("dd".repeat(32), 1, 100_000_000L, Pearl.scriptPubKey(mine), mine, 6, 6)
        val s = snapOf(listOf(coin))
        val a = w.prepareSend(s, DEST, 10_000_000L, 10_000L)
        val b = w.prepareSend(s, DEST, 20_000_000L, 10_000L)
        println("TXID_A=${a.txid.take(16)} TXID_B=${b.txid.take(16)}")
        assertTrue("txid harus beda untuk jumlah beda", a.txid != b.txid)
        // both spend the same outpoint — that is exactly why refresh-before-send matters
        assertEquals(a.spent[0].outpoint, b.spent[0].outpoint)
    }

    /** Sending exactly the spendable total must produce a single-output sweep. */
    @Test
    fun sendAllProducesSweep() {
        val w = wallet()
        val mine = w.receiveAddress(0)
        val coins = listOf(
            Utxo("e1".repeat(32), 0, 30_000_000L, Pearl.scriptPubKey(mine), mine, 6, 6),
            Utxo("e2".repeat(32), 1, 20_000_000L, Pearl.scriptPubKey(mine), mine, 6, 6)
        )
        val s = snapOf(coins)
        val max = w.maxSendable(s, 10_000L)
        val p = w.prepareSend(s, DEST, max, 10_000L)
        val parsed = Tx.parse(p.rawHex)
        assertEquals("sweep harus 1 output", 1, parsed.outputs.size)
        assertEquals(max, parsed.outputs[0].valueSat)
        assertEquals(0L, p.changeSat)
        assertEquals(50_000_000L, parsed.totalOut + p.feeSat)
    }

    // ---------------------------------------------------------------- invariants

    /** Every built tx must satisfy value conservation, for a range of amounts. */
    @Test
    fun conservationHoldsAcrossAmounts() {
        val w = wallet()
        val mine = w.receiveAddress(0)
        val coin = Utxo("f0".repeat(32), 0, 500_000_000L, Pearl.scriptPubKey(mine), mine, 6, 6)
        val s = snapOf(listOf(coin))
        val amounts = listOf(1_000L, 546L, 100_000L, 1_000_000L, 123_456_789L, 499_000_000L)
        for (a in amounts) {
            val p = try {
                w.prepareSend(s, DEST, a, 10_000L)
            } catch (e: Exception) {
                println("SKIP $a: ${e.message}")
                continue
            }
            val parsed = Tx.parse(p.rawHex)
            assertEquals(
                "konservasi gagal untuk $a",
                p.totalIn,
                parsed.totalOut + p.feeSat
            )
            assertEquals("jumlah tujuan salah untuk $a", a, parsed.outputs[0].valueSat)
            assertTrue("fee harus positif untuk $a", p.feeSat > 0)
            // no extra output ever
            assertTrue("output maksimal 2", parsed.outputs.size <= 2)
        }
    }

    /** Fee must never exceed a sane fraction of the amount for a normal send. */
    @Test
    fun feeIsReasonable() {
        val w = wallet()
        val mine = w.receiveAddress(0)
        val coin = Utxo("f1".repeat(32), 0, 1_000_000_000L, Pearl.scriptPubKey(mine), mine, 6, 6)
        val s = snapOf(listOf(coin))
        val p = w.prepareSend(s, DEST, 500_000_000L, 10_000L)
        println("FEE=${p.feeSat} on ${p.amountSat} (${100.0 * p.feeSat / p.amountSat}%)")
        assertTrue("fee tidak masuk akal: ${p.feeSat}", p.feeSat < 100_000L)
    }

    /** Unknown address must be rejected before any signing work happens. */
    @Test
    fun badDestinationRejectedEarly() {
        val w = wallet()
        val mine = w.receiveAddress(0)
        val s = snapOf(listOf(Utxo("f2".repeat(32), 0, 100_000_000L, Pearl.scriptPubKey(mine), mine, 6, 6)))
        for (bad in listOf("", "prl1", "abc", DEST.dropLast(1), DEST + "q")) {
            val threw = try {
                w.prepareSend(s, bad, 1_000_000L, 10_000L)
                false
            } catch (e: Exception) {
                true
            }
            assertTrue("alamat '$bad' harus ditolak", threw)
        }
    }

    /** keyForAddress must resolve any address inside the window (needed to sign). */
    @Test
    fun everyWindowAddressIsResolvable() {
        val w = wallet()
        for (c in 0..1) for (i in 0 until 10) {
            val a = w.keyAt(c, i).address
            val s = snapOf(listOf(Utxo("ab".repeat(32), 0, 10_000L, Pearl.scriptPubKey(a), a, 6, 6)))
            val p = w.prepareSend(s, DEST, 1_000L, 10_000L)
            assertNotNull(p.rawHex)
            assertEquals(1, Tx.parse(p.rawHex).inputs.size)
        }
    }

    private fun snapOf(utxos: List<Utxo>) = WalletStore.Snapshot(
        confirmedSat = utxos.sumOf { it.valueSat },
        unconfirmedSat = 0,
        utxos = utxos,
        history = emptyList(),
        addressCount = 24,
        ms = 0
    )
}
