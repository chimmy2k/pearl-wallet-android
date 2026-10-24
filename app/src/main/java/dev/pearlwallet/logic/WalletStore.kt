package dev.pearlwallet.logic

import dev.pearlwallet.core.Bip32
import dev.pearlwallet.core.Bip39
import dev.pearlwallet.core.Pearl
import dev.pearlwallet.core.Secp256k1
import dev.pearlwallet.core.Tx
import dev.pearlwallet.core.TxOutput
import dev.pearlwallet.core.Utxo
import java.math.BigInteger

/** One derived address plus its index/chain. */
data class AddrKey(val address: String, val index: Int, val chain: Int, val priv: BigInteger)

data class PreparedSend(
    val rawHex: String,
    val txid: String,
    val feeSat: Long,
    val amountSat: Long,
    val changeSat: Long,
    val inputs: Int,
    /** The exact coins being spent, in tx input order (largest first). */
    val spent: List<Utxo> = emptyList(),
    /** Destination and change scripts, in output order, as the network will see them. */
    val outputs: List<dev.pearlwallet.core.Tx.ParsedOutput> = emptyList(),
    /** Full destination address the operator typed. */
    val destAddress: String = "",
    /** Full address the change returns to — MUST be shown, or the operator thinks funds left. */
    val changeAddress: String = "",
    /** True when the destination is one of our own addresses (net effect = fee only). */
    val selfSend: Boolean = false
) {
    val totalIn: Long get() = spent.sumOf { it.valueSat }

    /** Virtual size of the signed tx — what the fee is actually priced against. */
    val vsize: Long get() = dev.pearlwallet.core.Tx.estimateVsize(spent.size.coerceAtLeast(inputs), outputs.size)

    /** Effective fee rate in sat/vB, so the operator can judge confirmation speed. */
    val satPerVb: Double get() = if (vsize > 0) feeSat.toDouble() / vsize else 0.0
}

/**
 * Wallet logic: seed -> account -> account-level chain query -> UTXO selection -> sign.
 *
 * Sync is 2 HTTP requests, not one per address:
 *   GET /api/v2/utxo/<xpub>                     -> all UTXOs, each with its address
 *   GET /api/v2/xpub/<xpub>?details=txs         -> balance + history
 * Measured: 0.7 s vs 19.6 s for the old 48-call per-address scan.
 */
class WalletStore(
    private val vault: dev.pearlwallet.core.SeedVault,
    private val bb: Blockbook
) {
    var mnemonic: String = ""
        private set
    private var seed: ByteArray = ByteArray(0)
    private var account: Bip32? = null
    private var accountXpub: String = ""

    /** address -> key, built once; addresses never change for a given seed. */
    private var derived: Map<String, AddrKey> = emptyMap()
    private var chainNode: Map<Int, Bip32> = emptyMap()

    /** refresh cache: a 30 s TTL makes the refresh button feel instant. */
    private var cached: Snapshot? = null
    private var cacheAt = 0L

    val gap: Int get() = vault.gapLimit

    fun hasWallet(): Boolean = vault.loadMnemonic() != null

    fun createNew(): String {
        val m = Bip39.generate(128)
        load(m)
        vault.saveMnemonic(m)
        return m
    }

    fun restore(mnemonicIn: String): Boolean {
        val norm = Bip39.normalize(mnemonicIn)
        if (!Bip39.isValid(norm)) return false
        load(norm)
        vault.saveMnemonic(norm)
        return true
    }

    fun loadSaved(): Boolean {
        val m = vault.loadMnemonic() ?: return false
        load(m)
        return true
    }

    private fun load(m: String) {
        mnemonic = Bip39.normalize(m)
        seed = Bip39.seed(mnemonic)
        val acct = Bip32.derivePath(seed, Pearl.accountPath())
        account = acct
        accountXpub = acct.neuteredString()
        chainNode = emptyMap()
        derived = emptyMap()
        windowGap = -1
        cached = null
    }

    fun wipe() {
        mnemonic = ""
        seed.fill(0)
        account = null
        accountXpub = ""
        chainNode = emptyMap()
        derived = emptyMap()
        windowGap = -1
        cached = null
        vault.wipe()
    }

    fun xpub(): String = accountXpub

    // ------------------------------------------------------------ derivation

    /** Cached `<account>/<chain>` node so each index costs exactly one EC multiply. */
    private fun chain(chainIdx: Int): Bip32 {
        chainNode[chainIdx]?.let { return it }
        val n = (account ?: throw IllegalStateException("wallet not loaded")).child(chainIdx)
        chainNode = chainNode + (chainIdx to n)
        return n
    }

    fun keyAt(chainIdx: Int, index: Int): AddrKey {
        val leaf = chain(chainIdx).child(index)
        return AddrKey(Pearl.p2trAddress(leaf.pub), index, chainIdx, leaf.priv)
    }

    fun receiveAddress(index: Int = 0): String = keyAt(0, index).address

    /**
     * Build (once) the address -> key map for the derivation window.
     * Pure local math, no network. Cached per gap limit; rebuilt if the limit changes.
     */
    private var windowGap = -1

    private fun ensureWindow(extra: Collection<String> = emptyList()): Map<String, AddrKey> {
        val need = gap * 2
        if (windowGap == gap && derived.size >= need * 2 && extra.all { it in derived }) return derived
        val m = HashMap<String, AddrKey>(derived)
        for (c in listOf(0, 1)) {
            for (i in 0 until need) {
                val k = keyAt(c, i)
                m[k.address] = k
            }
        }
        derived = m
        windowGap = gap
        return derived
    }

    private fun keyForAddress(address: String): AddrKey? {
        derived[address]?.let { return it }
        // widen the window until we find it (bounded — accounts stay well inside this)
        for (c in listOf(0, 1)) {
            for (i in 0 until gap * 8) {
                val k = keyAt(c, i)
                if (k.address == address) {
                    derived = derived + (address to k)
                    return k
                }
            }
        }
        return null
    }

    // ------------------------------------------------------------ sync

    data class Snapshot(
        val confirmedSat: Long,
        val unconfirmedSat: Long,
        val utxos: List<Utxo>,
        val history: List<TxEntry>,
        val addressCount: Int,
        val ms: Long,
        /** How many txs the indexer reports for this account (may exceed the page size). */
        val txsOnChain: Int = 0
    )

    fun refresh(force: Boolean = false): Snapshot {
        val now = System.currentTimeMillis()
        cached?.let { if (!force && now - cacheAt < 30_000) return it }
        val t0 = now

        val utxos = bb.utxosForXpub(accountXpub)
        // If the indexer ever omits the address field we cannot sign those UTXOs,
        // so fall back to the (slower) per-address path only in that case.
        val usable = if (utxos.all { it.address != null }) utxos else utxosViaAddressScan()

        val mine = HashSet<String>()
        usable.forEach { u -> u.address?.let { mine.add(it) } }
        mine.addAll(ensureWindow(mine).keys)

        val (bal, hist) = bb.account(accountXpub, mine, 25)
        val snap = Snapshot(
            confirmedSat = bal.confirmedSat,
            unconfirmedSat = bal.unconfirmedSat,
            utxos = usable,
            history = hist,
            addressCount = mine.size,
            ms = System.currentTimeMillis() - t0,
            txsOnChain = bal.txs
        )
        cached = snap
        cacheAt = System.currentTimeMillis()
        return snap
    }

    /**
     * Fallback only: one request per derived address, run in parallel.
     * Serial scanning of 24 addresses took ~5.7 s on a real device; a small pool brings
     * that down to roughly one round-trip.
     */
    private fun utxosViaAddressScan(): List<Utxo> {
        val addrs = ArrayList<String>(gap * 2)
        for (c in listOf(0, 1)) for (i in 0 until gap) addrs.add(keyAt(c, i).address)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(6)
        return try {
            val futures = addrs.map { a -> pool.submit(java.util.concurrent.Callable {
                try {
                    bb.utxos(a)
                } catch (e: Exception) {
                    emptyList<Utxo>()
                }
            }) }
            futures.flatMap { f ->
                try {
                    f.get(20, java.util.concurrent.TimeUnit.SECONDS)
                } catch (e: Exception) {
                    emptyList<Utxo>()
                }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    fun invalidate() {
        cached = null
        cacheAt = 0
    }

    // ------------------------------------------------------------ send

    private fun spendable(snap: Snapshot): List<Utxo> =
        snap.utxos.filter { it.confirmations > 0 }.sortedByDescending { it.valueSat }

    fun totalSpendable(snap: Snapshot): Long = spendable(snap).sumOf { it.valueSat }

    private fun select(snap: Snapshot, amountSat: Long, feePerKb: Long, outCount: Int): List<Utxo>? {
        val chosen = ArrayList<Utxo>()
        var sum = 0L
        for (u in spendable(snap)) {
            chosen.add(u)
            sum += u.valueSat
            if (sum >= amountSat + Tx.estimateFee(chosen.size, outCount, feePerKb)) return chosen
        }
        return null
    }

    fun maxSendable(snap: Snapshot, feePerKb: Long): Long {
        val pool = spendable(snap)
        if (pool.isEmpty()) return 0
        return (pool.sumOf { it.valueSat } - Tx.estimateFee(pool.size, 1, feePerKb)).coerceAtLeast(0)
    }

    fun prepareSend(snap: Snapshot, toAddress: String, amountSat: Long, feePerKb: Long): PreparedSend {
        require(amountSat > 0) { "jumlah harus > 0" }
        val destScript = try {
            Pearl.scriptPubKey(toAddress)
        } catch (e: Exception) {
            throw IllegalArgumentException("alamat tujuan tidak valid")
        }
        val changeAddr = keyAt(1, 0)

        var chosen = select(snap, amountSat, feePerKb, 2)
        var changeSat = 0L
        val outputs: List<TxOutput>
        if (chosen != null) {
            val sum = chosen.sumOf { it.valueSat }
            val fee = Tx.estimateFee(chosen.size, 2, feePerKb)
            changeSat = sum - amountSat - fee
            outputs = if (changeSat >= 546L) {
                listOf(
                    TxOutput(amountSat, destScript),
                    TxOutput(changeSat, Pearl.scriptPubKey(changeAddr.address))
                )
            } else {
                changeSat = 0
                listOf(TxOutput(amountSat, destScript))
            }
        } else {
            chosen = select(snap, amountSat, feePerKb, 1)
                ?: throw IllegalStateException("saldo tidak cukup")
            val sum = chosen.sumOf { it.valueSat }
            if (sum - Tx.estimateFee(chosen.size, 1, feePerKb) < amountSat) {
                throw IllegalStateException("saldo tidak cukup setelah biaya")
            }
            outputs = listOf(TxOutput(amountSat, destScript))
        }

        val signed = signMulti(chosen, outputs)
        val feeActual = chosen.sumOf { it.valueSat } - outputs.sumOf { it.valueSat }
        val parsed = Tx.parse(signed.hex)
        // self-check: what we are about to broadcast must contain exactly our outputs
        check(parsed.outputs.size == outputs.size) { "output count mismatch" }
        check(parsed.totalOut + feeActual == chosen.sumOf { it.valueSat }) { "value not conserved" }
        return PreparedSend(
            rawHex = signed.hex,
            txid = signed.txid,
            feeSat = feeActual,
            amountSat = amountSat,
            changeSat = changeSat,
            inputs = chosen.size,
            spent = chosen,
            outputs = parsed.outputs,
            destAddress = toAddress,
            changeAddress = if (changeSat > 0) changeAddr.address else "",
            selfSend = ownsAddress(toAddress)
        )
    }

    /** Is this one of our own addresses (either chain, inside the derivation window)? */
    fun ownsAddress(address: String): Boolean = keyForAddress(address) != null

    data class AddressBalance(
        val address: String,
        val chain: Int,
        val index: Int,
        val balanceSat: Long,
        val utxoCount: Int
    ) {
        val role: String get() = if (chain == 0) "terima #$index" else "kembalian #$index"
    }

    /**
     * Every derived address that currently holds something, with its balance.
     * This is what lets the operator confirm that a change address seen in an explorer
     * is actually theirs, instead of assuming the funds left.
     */
    fun addressesWithBalance(snap: Snapshot): List<AddressBalance> {
        val grouped = snap.utxos.groupBy { it.address ?: "" }
        val out = ArrayList<AddressBalance>()
        for (c in listOf(0, 1)) {
            for (i in 0 until gap * 2) {
                val k = keyAt(c, i)
                val us = grouped[k.address] ?: continue
                if (us.isEmpty()) continue
                out.add(AddressBalance(k.address, c, i, us.sumOf { it.valueSat }, us.size))
            }
        }
        return out.sortedWith(compareByDescending<AddressBalance> { it.balanceSat }.thenBy { it.chain }.thenBy { it.index })
    }

    /**
     * Inputs can come from different leaf keys of the same account, so each input is
     * signed with the key that owns its script, then witnesses are assembled.
     */
    private fun signMulti(utxos: List<Utxo>, outputs: List<TxOutput>): Tx.BuiltTx {
        require(utxos.isNotEmpty())
        val version = 2L
        val lockTime = 0L
        val sequences = List(utxos.size) { 0xffffffffL }

        val sigs = ArrayList<ByteArray>(utxos.size)
        for (i in utxos.indices) {
            val addr = utxos[i].address
                ?: throw IllegalStateException("input ${utxos[i].outpoint} tanpa alamat")
            val owner = keyForAddress(addr)
                ?: throw IllegalStateException("input ${utxos[i].outpoint} bukan milik dompet ini")
            val sighash = Tx.taprootSighashDefault(version, lockTime, utxos, sequences, outputs, i)
            sigs.add(Secp256k1.schnorrSign(sighash, Pearl.tweakPrivateKey(owner.priv)))
        }
        val hex = Tx.assemble(version, lockTime, utxos, outputs, sequences, sigs)
        return Tx.BuiltTx(
            hex,
            Tx.txid(hex),
            utxos.sumOf { it.valueSat } - outputs.sumOf { it.valueSat },
            Tx.estimateVsize(utxos.size, outputs.size)
        )
    }
}
