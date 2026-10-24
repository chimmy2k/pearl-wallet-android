package dev.pearlwallet.core

import java.io.ByteArrayOutputStream
import java.math.BigInteger

data class Utxo(
    val txid: String,
    val vout: Int,
    val valueSat: Long,
    val scriptPubKey: ByteArray? = null,
    val address: String? = null,
    val height: Int? = null,
    val confirmations: Int = 0
) {
    val outpoint: String get() = "$txid:$vout"
    override fun equals(other: Any?): Boolean = other is Utxo && other.outpoint == outpoint
    override fun hashCode(): Int = outpoint.hashCode()
}

data class TxOutput(val valueSat: Long, val script: ByteArray)

/** Pearl/Bitcoin wire format + BIP341 key-path sighash (SIGHASH_DEFAULT). */
object Tx {
    private fun varint(n: Long): ByteArray {
        val out = ByteArrayOutputStream()
        when {
            n < 0xfd -> out.write(n.toInt())
            n <= 0xffff -> {
                out.write(0xfd); out.write((n and 0xff).toInt()); out.write((n ushr 8 and 0xff).toInt())
            }
            n <= 0xffffffffL -> {
                out.write(0xfe)
                for (i in 0 until 4) out.write((n ushr (8 * i) and 0xff).toInt())
            }
            else -> {
                out.write(0xff)
                for (i in 0 until 8) out.write((n ushr (8 * i) and 0xff).toInt())
            }
        }
        return out.toByteArray()
    }

    private fun le32(v: Long): ByteArray = ByteArray(4) { i -> (v ushr (8 * i) and 0xff).toByte() }
    private fun le64(v: Long): ByteArray = ByteArray(8) { i -> (v ushr (8 * i) and 0xff).toByte() }

    fun hexToBytes(hex: String): ByteArray {
        val h = hex.trim()
        require(h.length % 2 == 0)
        return ByteArray(h.length / 2) { i -> h.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    fun bytesToHex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) sb.append(String.format("%02x", x))
        return sb.toString()
    }

    /** txid display form (reversed double-SHA256 of the stripped tx). */
    fun txid(signedHex: String): String {
        val raw = hexToBytes(signedHex)
        // strip marker/flag + witnesses
        val out = ByteArrayOutputStream()
        out.write(raw, 0, 4)
        var i = 4
        val segwit = raw[4].toInt() == 0 && raw[5].toInt() == 1
        if (segwit) i += 2
        val bodyStart = i
        val nin = readVarint(raw, i); i = nin.second
        var n = 0L
        while (n < nin.first) {
            i += 36
            val sl = readVarint(raw, i); i = sl.second + sl.first.toInt() + 4
            n++
        }
        val nout = readVarint(raw, i); i = nout.second
        n = 0
        while (n < nout.first) {
            i += 8
            val sl = readVarint(raw, i); i = sl.second + sl.first.toInt()
            n++
        }
        val bodyEnd = i
        out.write(raw, bodyStart, bodyEnd - bodyStart)
        out.write(raw, raw.size - 4, 4)
        val h = Hashes.sha256d(out.toByteArray())
        return bytesToHex(h.reversedArray())
    }

    private fun readVarint(b: ByteArray, off: Int): Pair<Long, Int> {
        val first = b[off].toInt() and 0xff
        return when (first) {
            0xfd -> Pair(((b[off + 1].toInt() and 0xff) or ((b[off + 2].toInt() and 0xff) shl 8)).toLong(), off + 3)
            0xfe -> {
                var v = 0L
                for (i in 0 until 4) v = v or ((b[off + 1 + i].toLong() and 0xff) shl (8 * i))
                Pair(v, off + 5)
            }
            0xff -> {
                var v = 0L
                for (i in 0 until 8) v = v or ((b[off + 1 + i].toLong() and 0xff) shl (8 * i))
                Pair(v, off + 9)
            }
            else -> Pair(first.toLong(), off + 1)
        }
    }

    /** Serialized scriptPubKey with compact-size length prefix (as used by BIP341 hashes). */
    private fun serScript(script: ByteArray): ByteArray = varint(script.size.toLong()) + script

    private fun taggedHash(tag: String, vararg parts: ByteArray): ByteArray {
        val th = Hashes.sha256(tag.toByteArray())
        val buf = ByteArrayOutputStream()
        buf.write(th); buf.write(th)
        for (p in parts) buf.write(p)
        return Hashes.sha256(buf.toByteArray())
    }

    /**
     * BIP341 signature message for a key-path spend, SIGHASH_DEFAULT.
     * `utxos` must be in the same order as the tx inputs.
     */
    fun taprootSighashDefault(
        version: Long,
        lockTime: Long,
        utxos: List<Utxo>,
        sequences: List<Long>,
        outputs: List<TxOutput>,
        inputIndex: Int
    ): ByteArray {
        val prevouts = ByteArrayOutputStream()
        val amounts = ByteArrayOutputStream()
        val scripts = ByteArrayOutputStream()
        val seqs = ByteArrayOutputStream()
        for (u in utxos) {
            prevouts.write(hexToBytes(u.txid).reversedArray())
            prevouts.write(le32(u.vout.toLong()))
            amounts.write(le64(u.valueSat))
            val spk = u.scriptPubKey ?: throw IllegalArgumentException("utxo missing scriptPubKey")
            scripts.write(serScript(spk))
        }
        for (s in sequences) seqs.write(le32(s))

        val shaPrevouts = Hashes.sha256(prevouts.toByteArray())
        val shaAmounts = Hashes.sha256(amounts.toByteArray())
        val shaScripts = Hashes.sha256(scripts.toByteArray())
        val shaSeqs = Hashes.sha256(seqs.toByteArray())

        val outs = ByteArrayOutputStream()
        for (o in outputs) {
            outs.write(le64(o.valueSat))
            outs.write(serScript(o.script))
        }
        val shaOutputs = Hashes.sha256(outs.toByteArray())

        val msg = ByteArrayOutputStream()
        msg.write(0x00)                 // hash_type = SIGHASH_DEFAULT
        msg.write(le32(version))
        msg.write(le32(lockTime))
        msg.write(shaPrevouts)
        msg.write(shaAmounts)
        msg.write(shaScripts)
        msg.write(shaSeqs)
        msg.write(shaOutputs)
        msg.write(0x00)                 // spend_type: key path, no annex
        msg.write(le32(inputIndex.toLong()))
        return taggedHash("TapSighash", byteArrayOf(0x00), msg.toByteArray())
    }

    data class BuiltTx(val hex: String, val txid: String, val feeSat: Long, val vsize: Long)

    /** Rough vsize for taproot key-spend inputs (matches the iOS wallet's model). */
    fun estimateVsize(inputs: Int, outputs: Int): Long = 11L + inputs * 58L + outputs * 43L

    fun estimateFee(inputs: Int, outputs: Int, feePerKb: Long): Long {
        val vsize = estimateVsize(inputs, outputs)
        return (vsize * feePerKb) / 1000L + 1
    }

    data class ParsedOutput(val valueSat: Long, val script: ByteArray) {
        /** "p2tr:<xonly hex>" or "op_return"/"unknown" for anything else. */
        val kind: String get() = when {
            script.size == 34 && script[0] == 0x51.toByte() && script[1] == 0x20.toByte() ->
                "p2tr:" + bytesToHex(script.copyOfRange(2, 34))
            script.isNotEmpty() && script[0] == 0x6a.toByte() -> "op_return"
            script.size == 22 && script[0] == 0x00.toByte() && script[1] == 0x14.toByte() ->
                "p2wpkh"
            else -> "unknown"
        }
    }

    data class Parsed(
        val version: Long,
        val lockTime: Long,
        val inputs: List<Pair<String, Int>>,   // (txid, vout)
        val outputs: List<ParsedOutput>,
        val hasWitness: Boolean
    ) {
        val totalOut: Long get() = outputs.sumOf { it.valueSat }
    }

    /**
     * Parse a signed tx back out of its hex. Used to prove what we are about to
     * broadcast — every output the network will see is enumerated here.
     *
     * Bounds-checked: malformed hex throws IllegalArgumentException, never an
     * index-out-of-bounds, because this runs on data we did not always author.
     */
    fun parse(hex: String): Parsed {
        val h = hex.trim()
        if (h.isEmpty() || h.length % 2 != 0) throw IllegalArgumentException("hex tidak valid")
        val b = try {
            hexToBytes(h)
        } catch (e: Exception) {
            throw IllegalArgumentException("hex tidak valid", e)
        }
        var i = 0
        fun need(n: Int) {
            if (n < 0 || i + n > b.size) {
                throw IllegalArgumentException("tx terpotong di offset $i (butuh $n, ada ${b.size - i})")
            }
        }
        fun u32(): Long {
            need(4)
            var v = 0L
            for (k in 0 until 4) v = v or ((b[i + k].toLong() and 0xff) shl (8 * k))
            i += 4
            return v
        }
        fun varint(): Long {
            need(1)
            val f = b[i].toInt() and 0xff
            i += 1
            return when (f) {
                0xfd -> {
                    need(2)
                    var v = 0L
                    for (k in 0 until 2) v = v or ((b[i + k].toLong() and 0xff) shl (8 * k))
                    i += 2; v
                }
                0xfe -> {
                    need(4)
                    var v = 0L
                    for (k in 0 until 4) v = v or ((b[i + k].toLong() and 0xff) shl (8 * k))
                    i += 4; v
                }
                0xff -> {
                    need(8)
                    var v = 0L
                    for (k in 0 until 8) v = v or ((b[i + k].toLong() and 0xff) shl (8 * k))
                    i += 8; v
                }
                else -> f.toLong()
            }
        }

        need(4)
        val version = u32()

        var segwit = false
        need(2)
        if (b[i].toInt() == 0 && b[i + 1].toInt() == 1) {
            segwit = true
            i += 2
        }

        val nin = varint()
        if (nin < 1 || nin > 100_000) throw IllegalArgumentException("jumlah input tidak masuk akal: $nin")
        val ins = ArrayList<Pair<String, Int>>(nin.toInt())
        for (k in 0 until nin) {
            need(32)
            val prev = b.copyOfRange(i, i + 32).reversedArray()
            i += 32
            val vout = u32().toInt()
            val sl = varint()
            if (sl < 0 || sl > 10_000) throw IllegalArgumentException("scriptSig terlalu panjang")
            need(sl.toInt())
            i += sl.toInt()
            u32() // sequence
            ins.add(bytesToHex(prev) to vout)
        }

        val nout = varint()
        if (nout < 0 || nout > 100_000) throw IllegalArgumentException("jumlah output tidak masuk akal: $nout")
        val outs = ArrayList<ParsedOutput>(nout.toInt())
        for (k in 0 until nout) {
            need(8)
            var v = 0L
            for (m in 0 until 8) v = v or ((b[i + m].toLong() and 0xff) shl (8 * m))
            i += 8
            if (v < 0) throw IllegalArgumentException("nilai output negatif")
            val sl = varint()
            if (sl < 0 || sl > 10_000) throw IllegalArgumentException("scriptPubKey terlalu panjang")
            need(sl.toInt())
            outs.add(ParsedOutput(v, b.copyOfRange(i, i + sl.toInt())))
            i += sl.toInt()
        }

        if (segwit) {
            for (k in 0 until nin) {
                val items = varint()
                if (items < 0 || items > 1_000) throw IllegalArgumentException("witness item terlalu banyak")
                for (m in 0 until items) {
                    val len = varint()
                    if (len < 0 || len > 100_000) throw IllegalArgumentException("witness terlalu panjang")
                    need(len.toInt())
                    i += len.toInt()
                }
            }
        }

        val lockTime = u32()
        if (i != b.size) throw IllegalArgumentException("ada ${b.size - i} byte sisa setelah locktime")
        return Parsed(version, lockTime, ins, outs, segwit)
    }

    /**
     * Assemble a signed segwit tx from pre-computed witnesses (one sig per input).
     * Kept separate from [buildSigned] so multi-key wallets can sign each input
     * with its own leaf key.
     */
    fun assemble(
        version: Long,
        lockTime: Long,
        utxos: List<Utxo>,
        outputs: List<TxOutput>,
        sequences: List<Long>,
        sigs: List<ByteArray>
    ): String {
        require(utxos.size == sigs.size) { "sig count mismatch" }
        val out = ByteArrayOutputStream()
        out.write(le32(version))
        out.write(0x00); out.write(0x01)
        out.write(varint(utxos.size.toLong()))
        for ((i, u) in utxos.withIndex()) {
            out.write(hexToBytes(u.txid).reversedArray())
            out.write(le32(u.vout.toLong()))
            out.write(0x00)
            out.write(le32(sequences[i]))
        }
        out.write(varint(outputs.size.toLong()))
        for (o in outputs) {
            out.write(le64(o.valueSat))
            out.write(serScript(o.script))
        }
        for (sig in sigs) {
            out.write(varint(1))
            out.write(varint(sig.size.toLong()))
            out.write(sig)
        }
        out.write(le32(lockTime))
        return bytesToHex(out.toByteArray())
    }

    /**
     * Build + sign a taproot key-spend transaction.
     * Every input must be spendable by `priv` (same single-key wallet).
     * `priv` is the BIP32 leaf key; the BIP341 tweak is applied here.
     */
    fun buildSigned(
        priv: BigInteger,
        utxos: List<Utxo>,
        outputs: List<TxOutput>,
        version: Long = 2,
        lockTime: Long = 0,
        aux: ByteArray = ByteArray(32)
    ): BuiltTx {
        require(utxos.isNotEmpty())
        require(outputs.isNotEmpty())
        val signKey = Pearl.tweakPrivateKey(priv)
        val sequences = List(utxos.size) { 0xffffffffL }

        val sigs = ArrayList<ByteArray>(utxos.size)
        for (i in utxos.indices) {
            val sighash = taprootSighashDefault(version, lockTime, utxos, sequences, outputs, i)
            sigs.add(Secp256k1.schnorrSign(sighash, signKey, aux))
        }

        val out = ByteArrayOutputStream()
        out.write(le32(version))
        out.write(0x00); out.write(0x01)          // marker + flag (segwit)
        out.write(varint(utxos.size.toLong()))
        for ((i, u) in utxos.withIndex()) {
            out.write(hexToBytes(u.txid).reversedArray())
            out.write(le32(u.vout.toLong()))
            out.write(0x00)                        // empty scriptSig
            out.write(le32(sequences[i]))
        }
        out.write(varint(outputs.size.toLong()))
        for (o in outputs) {
            out.write(le64(o.valueSat))
            out.write(serScript(o.script))
        }
        for (sig in sigs) {
            out.write(varint(1))                   // one witness item
            out.write(varint(sig.size.toLong()))
            out.write(sig)
        }
        out.write(le32(lockTime))
        val hex = bytesToHex(out.toByteArray())
        val fee = utxos.sumOf { it.valueSat } - outputs.sumOf { it.valueSat }
        return BuiltTx(hex, txid(hex), fee, estimateVsize(utxos.size, outputs.size))
    }

    // ------------------------------------------------------------- amounts

    fun prlToSat(prl: String): Long? {
        val s = prl.trim().replace(",", ".")
        if (!Regex("^\\d+(\\.\\d{1,8})?$").matches(s)) return null
        val parts = s.split(".")
        val whole = parts[0].toLongOrNull() ?: return null
        val frac = if (parts.size > 1) parts[1].padEnd(8, '0') else "00000000"
        val fracVal = frac.toLongOrNull() ?: return null
        if (whole > (Long.MAX_VALUE - fracVal) / 100_000_000L) return null
        return whole * 100_000_000L + fracVal
    }

    fun satToPrl(sat: Long): String {
        // BigInteger so Long.MIN_VALUE cannot overflow through abs()
        val b = java.math.BigInteger.valueOf(sat)
        val neg = b.signum() < 0
        val a = b.abs()
        val unit = java.math.BigInteger.valueOf(100_000_000L)
        val whole = a.divide(unit)
        val frac = a.mod(unit).toLong()
        var fs = frac.toString().padStart(8, '0').trimEnd('0')
        if (fs.isEmpty()) fs = "0"
        return (if (neg) "-" else "") + whole + "." + fs
    }
}
