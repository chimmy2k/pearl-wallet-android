package dev.pearlwallet.core

import java.math.BigInteger

/**
 * Minimal secp256k1 for BIP32 / BIP86 / BIP340 — no native libs, no BouncyCastle.
 * Jacobian coordinates for the heavy lifting, affine at the boundary.
 * Validated against the BIP340 + BIP86 official test vectors (see CoreTest).
 */
object Secp256k1 {
    val P: BigInteger = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16)
    val N: BigInteger = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)
    private val GX = BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16)
    private val GY = BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16)
    private val TWO = BigInteger.valueOf(2)
    private val THREE = BigInteger.valueOf(3)
    private val FOUR = BigInteger.valueOf(4)
    private val SEVEN = BigInteger.valueOf(7)
    private val EIGHT = BigInteger.valueOf(8)

    data class Point(val x: BigInteger, val y: BigInteger) {
        val isEvenY: Boolean get() = !y.testBit(0)
        fun xOnly(): ByteArray = x.toFixed32()
        fun compressed(): ByteArray {
            val out = ByteArray(33)
            out[0] = if (isEvenY) 0x02 else 0x03
            System.arraycopy(x.toFixed32(), 0, out, 1, 32)
            return out
        }
    }

    private class Jac(val x: BigInteger, val y: BigInteger, val z: BigInteger) {
        val infinity: Boolean get() = z.signum() == 0
    }

    private val INF = Jac(BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)
    private val G = Jac(GX, GY, BigInteger.ONE)

    private fun BigInteger.mod(): BigInteger {
        val r = this.mod(P)
        return r
    }

    private fun dbl(a: Jac): Jac {
        if (a.infinity || a.y.signum() == 0) return INF
        val y2 = a.y.multiply(a.y).mod()
        val s = a.x.multiply(y2).multiply(FOUR).mod()
        val m = a.x.multiply(a.x).multiply(THREE).mod()
        val x3 = m.multiply(m).subtract(s.multiply(TWO)).mod()
        val y4 = y2.multiply(y2).mod()
        val y3 = m.multiply(s.subtract(x3)).subtract(y4.multiply(EIGHT)).mod()
        val z3 = a.y.multiply(a.z).multiply(TWO).mod()
        return Jac(x3, y3, z3)
    }

    private fun add(a: Jac, b: Jac): Jac {
        if (a.infinity) return b
        if (b.infinity) return a
        val z1z1 = a.z.multiply(a.z).mod()
        val z2z2 = b.z.multiply(b.z).mod()
        val u1 = a.x.multiply(z2z2).mod()
        val u2 = b.x.multiply(z1z1).mod()
        val s1 = a.y.multiply(z2z2).multiply(b.z).mod()
        val s2 = b.y.multiply(z1z1).multiply(a.z).mod()
        if (u1 == u2) return if (s1 == s2) dbl(a) else INF
        val h = u2.subtract(u1).mod()
        val r = s2.subtract(s1).mod()
        val h2 = h.multiply(h).mod()
        val h3 = h2.multiply(h).mod()
        val u1h2 = u1.multiply(h2).mod()
        val x3 = r.multiply(r).subtract(h3).subtract(u1h2.multiply(TWO)).mod()
        val y3 = r.multiply(u1h2.subtract(x3)).subtract(s1.multiply(h3)).mod()
        val z3 = h.multiply(a.z).multiply(b.z).mod()
        return Jac(x3, y3, z3)
    }

    private fun toAffine(a: Jac): Point? {
        if (a.infinity) return null
        val zi = a.z.modInverse(P)
        val zi2 = zi.multiply(zi).mod()
        val x = a.x.multiply(zi2).mod()
        val y = a.y.multiply(zi2).multiply(zi).mod()
        return Point(x, y)
    }

    fun mulG(k: BigInteger): Point? = toAffine(mulJac(G, k))

    private fun mulJac(base: Jac, k: BigInteger): Jac {
        val kk = k.mod(N)
        if (kk.signum() == 0) return INF
        var acc = INF
        var cur = base
        for (i in 0 until kk.bitLength()) {
            if (kk.testBit(i)) acc = add(acc, cur)
            cur = dbl(cur)
        }
        return acc
    }

    fun pubkey(priv: BigInteger): Point =
        mulG(priv) ?: throw IllegalArgumentException("zero scalar")

    fun pointAdd(a: Point, b: Point): Point? =
        toAffine(add(Jac(a.x, a.y, BigInteger.ONE), Jac(b.x, b.y, BigInteger.ONE)))

    fun liftX(x32: ByteArray): Point {
        val x = BigInteger(1, x32)
        require(x < P) { "x >= p" }
        val c = x.pow(3).add(SEVEN).mod()
        var y = c.modPow(P.add(BigInteger.ONE).divide(FOUR), P)
        if (y.multiply(y).mod() != c) throw IllegalArgumentException("not on curve")
        if (y.testBit(0)) y = P.subtract(y)
        return Point(x, y)
    }

    // ---------------------------------------------------------------- BIP340

    private fun taggedHash(tag: String, vararg parts: ByteArray): ByteArray {
        val th = Hashes.sha256(tag.toByteArray())
        val buf = java.io.ByteArrayOutputStream()
        buf.write(th); buf.write(th)
        for (p in parts) buf.write(p)
        return Hashes.sha256(buf.toByteArray())
    }

    /** BIP340 sign with SIGHASH_DEFAULT-style raw 32-byte message. */
    fun schnorrSign(msg32: ByteArray, priv: BigInteger, aux: ByteArray = ByteArray(32)): ByteArray {
        require(msg32.size == 32)
        require(aux.size == 32)
        val p0 = pubkey(priv)
        val d = if (p0.isEvenY) priv else N.subtract(priv)
        val px = p0.xOnly()
        val t = d.toFixed32().let { dk -> ByteArray(32) { i -> (dk[i].toInt() xor taggedHash("BIP0340/aux", aux)[i].toInt()).toByte() } }
        val rand = taggedHash("BIP0340/nonce", t, px, msg32)
        val k0 = BigInteger(1, rand).mod(N)
        require(k0.signum() != 0) { "nonce zero" }
        val r0 = pubkey(k0)
        val k = if (r0.isEvenY) k0 else N.subtract(k0)
        val rx = r0.xOnly()
        val e = BigInteger(1, taggedHash("BIP0340/challenge", rx, px, msg32)).mod(N)
        val s = k.add(e.multiply(d)).mod(N)
        return rx + s.toFixed32()
    }

    fun schnorrVerify(msg32: ByteArray, sig64: ByteArray, pubX32: ByteArray): Boolean {
        if (sig64.size != 64) return false
        val r = sig64.copyOfRange(0, 32)
        val s = BigInteger(1, sig64.copyOfRange(32, 64))
        if (s >= N) return false
        val p = try { liftX(pubX32) } catch (e: Exception) { return false }
        val e = BigInteger(1, taggedHash("BIP0340/challenge", r, pubX32, msg32)).mod(N)
        val sG = mulG(s) ?: return false
        // R = s*G - e*P
        val ePj = mulJac(Jac(p.x, p.y, BigInteger.ONE), e)
        val negEP = Jac(ePj.x, P.subtract(ePj.y).mod(), ePj.z)
        val rPoint = toAffine(add(Jac(sG.x, sG.y, BigInteger.ONE), negEP)) ?: return false
        if (!rPoint.isEvenY) return false
        return rPoint.xOnly().contentEquals(r)
    }
}

fun BigInteger.toFixed32(): ByteArray {
    val raw = toByteArray()
    val out = ByteArray(32)
    if (raw.size > 32) {
        System.arraycopy(raw, raw.size - 32, out, 0, 32)
    } else {
        System.arraycopy(raw, 0, out, 32 - raw.size, raw.size)
    }
    return out
}

object Hashes {
    fun sha256(b: ByteArray): ByteArray =
        java.security.MessageDigest.getInstance("SHA-256").digest(b)

    fun sha256d(b: ByteArray): ByteArray = sha256(sha256(b))

    fun hmacSha512(key: ByteArray, data: ByteArray): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA512")
        mac.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA512"))
        return mac.doFinal(data)
    }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /** PBKDF2-HMAC-SHA512, manual so the password bytes are unambiguous (BIP39). */
    fun pbkdf2Sha512(password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var block = 1
        while (out.size() < dkLen) {
            val be = byteArrayOf(
                (block ushr 24).toByte(), (block ushr 16).toByte(),
                (block ushr 8).toByte(), block.toByte()
            )
            var u = hmacSha512(password, salt + be)
            val acc = u.copyOf()
            for (i in 1 until iterations) {
                u = hmacSha512(password, u)
                for (j in acc.indices) acc[j] = (acc[j].toInt() xor u[j].toInt()).toByte()
            }
            out.write(acc)
            block++
        }
        return out.toByteArray().copyOf(dkLen)
    }
}
