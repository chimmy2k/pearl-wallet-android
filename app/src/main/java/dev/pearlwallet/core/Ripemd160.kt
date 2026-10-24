package dev.pearlwallet.core

/**
 * RIPEMD-160, needed for BIP32 key fingerprints (HASH160 = RIPEMD160(SHA256(x))).
 * Implemented here because the desktop JVM has no RIPEMD160 provider and we want the
 * exact same code path on Android and in the unit tests.
 */
object Ripemd160 {
    private val RL = intArrayOf(
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
        7, 4, 13, 1, 10, 6, 15, 3, 12, 0, 9, 5, 2, 14, 11, 8,
        3, 10, 14, 4, 9, 15, 8, 1, 2, 7, 0, 6, 13, 11, 5, 12,
        1, 9, 11, 10, 0, 8, 12, 4, 13, 3, 7, 15, 14, 5, 6, 2,
        4, 0, 5, 9, 7, 12, 2, 10, 14, 1, 3, 8, 11, 6, 15, 13
    )
    private val RR = intArrayOf(
        5, 14, 7, 0, 9, 2, 11, 4, 13, 6, 15, 8, 1, 10, 3, 12,
        6, 11, 3, 7, 0, 13, 5, 10, 14, 15, 8, 12, 4, 9, 1, 2,
        15, 5, 1, 3, 7, 14, 6, 9, 11, 8, 12, 2, 10, 0, 4, 13,
        8, 6, 4, 1, 3, 11, 15, 0, 5, 12, 2, 13, 9, 7, 10, 14,
        12, 15, 10, 4, 1, 5, 8, 7, 6, 2, 13, 14, 0, 3, 9, 11
    )
    private val SL = intArrayOf(
        11, 14, 15, 12, 5, 8, 7, 9, 11, 13, 14, 15, 6, 7, 9, 8,
        7, 6, 8, 13, 11, 9, 7, 15, 7, 12, 15, 9, 11, 7, 13, 12,
        11, 13, 6, 7, 14, 9, 13, 15, 14, 8, 13, 6, 5, 12, 7, 5,
        11, 12, 14, 15, 14, 15, 9, 8, 9, 14, 5, 6, 8, 6, 5, 12,
        9, 15, 5, 11, 6, 8, 13, 12, 5, 12, 13, 14, 11, 8, 5, 6
    )
    private val SR = intArrayOf(
        8, 9, 9, 11, 13, 15, 15, 5, 7, 7, 8, 11, 14, 14, 12, 6,
        9, 13, 15, 7, 12, 8, 9, 11, 7, 7, 12, 7, 6, 15, 13, 11,
        9, 7, 15, 11, 8, 6, 6, 14, 12, 13, 5, 14, 13, 13, 7, 5,
        15, 5, 8, 11, 14, 14, 6, 14, 6, 9, 12, 9, 12, 5, 15, 8,
        8, 5, 12, 9, 12, 5, 14, 6, 8, 13, 6, 5, 15, 13, 11, 11
    )
    private val KL = intArrayOf(0x00000000, 0x5a827999, 0x6ed9eba1, 0x8f1bbcdc.toInt(), 0xa953fd4e.toInt())
    private val KR = intArrayOf(0x50a28be6, 0x5c4dd124, 0x6d703ef3, 0x7a6d76e9, 0x00000000)

    private fun f(j: Int, x: Int, y: Int, z: Int): Int = when (j / 16) {
        0 -> x xor y xor z
        1 -> (x and y) or (x.inv() and z)
        2 -> (x or y.inv()) xor z
        3 -> (x and z) or (y and z.inv())
        else -> x xor (y or z.inv())
    }

    private fun rol(x: Int, n: Int): Int = (x shl n) or (x ushr (32 - n))

    fun digest(msg: ByteArray): ByteArray {
        val ml = msg.size.toLong() * 8
        val padLen = ((55 - msg.size) % 64 + 64) % 64
        val buf = ByteArray(msg.size + padLen + 9)
        System.arraycopy(msg, 0, buf, 0, msg.size)
        buf[msg.size] = 0x80.toByte()
        for (i in 0 until 8) buf[buf.size - 8 + i] = ((ml ushr (8 * i)) and 0xff).toByte()

        var h0 = 0x67452301
        var h1 = 0xefcdab89.toInt()
        var h2 = 0x98badcfe.toInt()
        var h3 = 0x10325476
        var h4 = 0xc3d2e1f0.toInt()

        val x = IntArray(16)
        var off = 0
        while (off < buf.size) {
            for (i in 0 until 16) {
                x[i] = (buf[off + i * 4].toInt() and 0xff) or
                    ((buf[off + i * 4 + 1].toInt() and 0xff) shl 8) or
                    ((buf[off + i * 4 + 2].toInt() and 0xff) shl 16) or
                    ((buf[off + i * 4 + 3].toInt() and 0xff) shl 24)
            }
            var al = h0; var bl = h1; var cl = h2; var dl = h3; var el = h4
            var ar = h0; var br = h1; var cr = h2; var dr = h3; var er = h4
            for (j in 0 until 80) {
                var t = rol(al + f(j, bl, cl, dl) + x[RL[j]] + KL[j / 16], SL[j]) + el
                al = el; el = dl; dl = rol(cl, 10); cl = bl; bl = t
                t = rol(ar + f(79 - j, br, cr, dr) + x[RR[j]] + KR[j / 16], SR[j]) + er
                ar = er; er = dr; dr = rol(cr, 10); cr = br; br = t
            }
            val t = h1 + cl + dr
            h1 = h2 + dl + er
            h2 = h3 + el + ar
            h3 = h4 + al + br
            h4 = h0 + bl + cr
            h0 = t
            off += 64
        }
        val out = ByteArray(20)
        val hs = intArrayOf(h0, h1, h2, h3, h4)
        for (i in 0 until 5) {
            out[i * 4] = (hs[i] and 0xff).toByte()
            out[i * 4 + 1] = ((hs[i] ushr 8) and 0xff).toByte()
            out[i * 4 + 2] = ((hs[i] ushr 16) and 0xff).toByte()
            out[i * 4 + 3] = ((hs[i] ushr 24) and 0xff).toByte()
        }
        return out
    }

    fun hash160(data: ByteArray): ByteArray = digest(Hashes.sha256(data))
}
