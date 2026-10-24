package dev.pearlwallet.core

/** BIP173/350 bech32 + bech32m. */
object Bech32 {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val GEN = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
    const val BECH32M_CONST = 0x2bc830a3
    private const val BECH32_CONST = 1

    private fun polymod(values: IntArray): Int {
        var chk = 1
        for (v in values) {
            val top = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in 0 until 5) if ((top ushr i) and 1 == 1) chk = chk xor GEN[i]
        }
        return chk
    }

    private fun hrpExpand(hrp: String): IntArray {
        val out = IntArray(hrp.length * 2 + 1)
        for (i in hrp.indices) out[i] = hrp[i].code ushr 5
        out[hrp.length] = 0
        for (i in hrp.indices) out[hrp.length + 1 + i] = hrp[i].code and 31
        return out
    }

    private fun convertBits(data: ByteArray, from: Int, to: Int, pad: Boolean): ByteArray {
        var acc = 0
        var bits = 0
        val out = java.io.ByteArrayOutputStream()
        val maxv = (1 shl to) - 1
        for (b in data) {
            val v = b.toInt() and 0xff
            require(v ushr from == 0) { "invalid data range" }
            acc = (acc shl from) or v
            bits += from
            while (bits >= to) {
                bits -= to
                out.write((acc ushr bits) and maxv)
            }
        }
        if (pad) {
            if (bits > 0) out.write((acc shl (to - bits)) and maxv)
        } else {
            require(bits < from && ((acc shl (to - bits)) and maxv) == 0) { "invalid padding" }
        }
        return out.toByteArray()
    }

    private fun encode(hrp: String, data5: ByteArray, constant: Int): String {
        val values = IntArray(data5.size) { data5[it].toInt() and 0xff }
        val chkInput = hrpExpand(hrp) + values + IntArray(6)
        val mod = polymod(chkInput) xor constant
        val sb = StringBuilder(hrp).append('1')
        for (v in values) sb.append(CHARSET[v])
        for (i in 0 until 6) sb.append(CHARSET[(mod ushr (5 * (5 - i))) and 31])
        return sb.toString()
    }

    fun encodeSegwit(hrp: String, witver: Int, program: ByteArray): String {
        require(witver in 0..16)
        val data = byteArrayOf(witver.toByte()) + convertBits(program, 8, 5, true)
        val constant = if (witver == 0) BECH32_CONST else BECH32M_CONST
        return encode(hrp, data, constant)
    }

    data class Decoded(val hrp: String, val witver: Int, val program: ByteArray)

    fun decodeSegwit(addr: String): Decoded? = try {
        val lower = addr.lowercase()
        require(lower == addr || addr.uppercase() == addr) { "mixed case" }
        val sep = lower.lastIndexOf('1')
        require(sep >= 1 && sep + 7 <= lower.length)
        val hrp = lower.substring(0, sep)
        val data = IntArray(lower.length - sep - 1) { CHARSET.indexOf(lower[sep + 1 + it]) }
        require(data.none { it < 0 })
        val chk = polymod(hrpExpand(hrp) + data)
        val payload = data.copyOfRange(0, data.size - 6)
        require(payload.isNotEmpty())
        val witver = payload[0]
        require(witver in 0..16)
        require(chk == (if (witver == 0) BECH32_CONST else BECH32M_CONST)) { "bad checksum" }
        val prog5 = ByteArray(payload.size - 1) { payload[it + 1].toByte() }
        val program = convertBits(prog5, 5, 8, false)
        require(program.size in 2..40)
        if (witver == 0) require(program.size in listOf(20, 32))
        if (witver == 1) require(program.size == 32)
        Decoded(hrp, witver, program)
    } catch (e: Exception) {
        null
    }
}

/** Pearl network constants. */
object Pearl {
    const val HRP_MAIN = "prl"
    const val HRP_TEST = "tprl"
    const val COIN_TYPE_MAIN = 808276
    const val COIN_TYPE_TEST = 1
    const val DECIMALS = 8

    fun accountPath(coinType: Int = COIN_TYPE_MAIN, account: Int = 0): String =
        "m/86'/$coinType'/$account'"

    fun addressFor(seed: ByteArray, index: Int, chain: Int = 0, coinType: Int = COIN_TYPE_MAIN, hrp: String = HRP_MAIN): String {
        val acct = Bip32.derivePath(seed, accountPath(coinType))
        val leaf = acct.child(chain).child(index)
        return p2trAddress(leaf.pub, hrp)
    }

    fun p2trAddress(internalPub: Secp256k1.Point, hrp: String = HRP_MAIN): String {
        val output = taprootOutputKey(internalPub)
        return Bech32.encodeSegwit(hrp, 1, output.xOnly())
    }

    /** BIP341/BIP86 tweak scalar t = H_TapTweak(x-only internal key). */
    fun tweakScalar(internalPub: Secp256k1.Point): java.math.BigInteger {
        val evenY = evenY(internalPub)
        val th = Hashes.sha256("TapTweak".toByteArray())
        val buf = java.io.ByteArrayOutputStream()
        buf.write(th); buf.write(th); buf.write(evenY.xOnly())
        return java.math.BigInteger(1, Hashes.sha256(buf.toByteArray())).mod(Secp256k1.N)
    }

    fun evenY(p: Secp256k1.Point): Secp256k1.Point =
        if (p.isEvenY) p else Secp256k1.Point(p.x, Secp256k1.P.subtract(p.y))

    /** Private key that signs a key-path spend for a P2TR output (BIP341 tweak). */
    fun tweakPrivateKey(priv: java.math.BigInteger): java.math.BigInteger {
        val pub = Secp256k1.pubkey(priv)
        val d = if (pub.isEvenY) priv else Secp256k1.N.subtract(priv)
        val t = tweakScalar(pub)
        require(t.signum() != 0) { "invalid tweak" }
        return d.add(t).mod(Secp256k1.N)
    }

    /** BIP341/BIP86 tweak with no script tree. */
    fun taprootOutputKey(internalPub: Secp256k1.Point): Secp256k1.Point {
        // x-only keys always mean the even-Y point (BIP340 lift_x).
        val evenY = evenY(internalPub)
        val t = tweakScalar(internalPub)
        require(t.signum() != 0) { "invalid tweak" }
        val tp = Secp256k1.mulG(t) ?: throw IllegalStateException("tweak point")
        return Secp256k1.pointAdd(evenY, tp) ?: throw IllegalStateException("tweak sum")
    }

    /** ScriptPubKey hex for a P2TR address. */
    fun scriptPubKey(address: String): ByteArray {
        val d = Bech32.decodeSegwit(address) ?: throw IllegalArgumentException("bad address")
        require(d.witver == 1) { "not a taproot address" }
        return byteArrayOf(0x51, 0x20) + d.program
    }
}
