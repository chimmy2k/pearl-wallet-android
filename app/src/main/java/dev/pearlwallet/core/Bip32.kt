package dev.pearlwallet.core

import java.math.BigInteger
import java.security.SecureRandom

/** BIP39: entropy <-> mnemonic, mnemonic -> 64-byte seed. */
object Bip39 {
    private var words: List<String> = emptyList()

    fun loadWordlist(lines: List<String>) {
        require(lines.size == 2048) { "wordlist must be 2048 words, got ${lines.size}" }
        words = lines.map { it.trim().lowercase() }
    }

    fun wordlist(): List<String> = words

    fun generate(strengthBits: Int = 128, rnd: SecureRandom = SecureRandom()): String {
        require(strengthBits % 32 == 0 && strengthBits in 128..256)
        val ent = ByteArray(strengthBits / 8)
        rnd.nextBytes(ent)
        return entropyToMnemonic(ent)
    }

    fun entropyToMnemonic(entropy: ByteArray): String {
        require(entropy.size in listOf(16, 20, 24, 28, 32))
        val csBits = entropy.size * 8 / 32
        val hash = Hashes.sha256(entropy)
        val bits = StringBuilder()
        for (b in entropy) bits.append(String.format("%8s", Integer.toBinaryString(b.toInt() and 0xff)).replace(' ', '0'))
        val cs = String.format("%8s", Integer.toBinaryString(hash[0].toInt() and 0xff)).replace(' ', '0')
        bits.append(cs.substring(0, csBits))
        val out = ArrayList<String>()
        var i = 0
        while (i < bits.length) {
            val idx = Integer.parseInt(bits.substring(i, i + 11), 2)
            out.add(words[idx])
            i += 11
        }
        return out.joinToString(" ")
    }

    fun isValid(mnemonic: String): Boolean = try {
        mnemonicToEntropy(mnemonic)
        true
    } catch (e: Exception) {
        false
    }

    fun mnemonicToEntropy(mnemonic: String): ByteArray {
        val parts = normalize(mnemonic).split(" ")
        require(parts.size in listOf(12, 15, 18, 21, 24)) { "bad word count" }
        val bits = StringBuilder()
        for (w in parts) {
            val idx = words.indexOf(w)
            require(idx >= 0) { "unknown word: $w" }
            bits.append(String.format("%11s", Integer.toBinaryString(idx)).replace(' ', '0'))
        }
        val csBits = bits.length / 33
        val entBits = bits.length - csBits
        val ent = ByteArray(entBits / 8)
        for (i in ent.indices) {
            ent[i] = Integer.parseInt(bits.substring(i * 8, i * 8 + 8), 2).toByte()
        }
        val hash = Hashes.sha256(ent)
        val expect = String.format("%8s", Integer.toBinaryString(hash[0].toInt() and 0xff)).replace(' ', '0').substring(0, csBits)
        require(bits.substring(entBits) == expect) { "checksum mismatch" }
        return ent
    }

    fun normalize(mnemonic: String): String =
        mnemonic.trim().lowercase().split(Regex("\\s+")).joinToString(" ")

    fun seed(mnemonic: String, passphrase: String = ""): ByteArray {
        val pw = normalize(mnemonic).toByteArray(Charsets.UTF_8)
        val salt = ("mnemonic" + passphrase).toByteArray(Charsets.UTF_8)
        return Hashes.pbkdf2Sha512(pw, salt, 2048, 64)
    }
}

/** BIP32 private derivation, plus Pearl's SLIP-132 zpub serialization. */
class Bip32 private constructor(
    val priv: BigInteger,
    val chainCode: ByteArray,
    val depth: Int,
    val parentFingerprint: ByteArray,
    val childNumber: Int
) {
    val pub: Secp256k1.Point get() = Secp256k1.pubkey(priv)

    companion object {
        private val N = Secp256k1.N
        val HARDENED: Int = 0x80000000.toInt()

        fun fromSeed(seed: ByteArray): Bip32 {
            val i = Hashes.hmacSha512("Bitcoin seed".toByteArray(), seed)
            val k = BigInteger(1, i.copyOfRange(0, 32))
            require(k.signum() > 0 && k < N) { "invalid master key" }
            return Bip32(k, i.copyOfRange(32, 64), 0, ByteArray(4), 0)
        }

        fun derivePath(seed: ByteArray, path: String): Bip32 {
            var k = fromSeed(seed)
            for (seg in path.trim().split("/")) {
                if (seg.isEmpty() || seg == "m") continue
                val hardened = seg.endsWith("'") || seg.endsWith("h")
                val idx = seg.removeSuffix("'").removeSuffix("h").toInt()
                k = k.child(if (hardened) idx + HARDENED else idx)
            }
            return k
        }
    }

    fun child(index: Int): Bip32 {
        val data = java.io.ByteArrayOutputStream()
        if (index < 0) {   // hardened: high bit set
            data.write(0x00)
            data.write(priv.toFixed32())
        } else {
            data.write(pub.compressed())
        }
        data.write(byteArrayOf(
            (index ushr 24).toByte(), (index ushr 16).toByte(),
            (index ushr 8).toByte(), index.toByte()
        ))
        val i = Hashes.hmacSha512(chainCode, data.toByteArray())
        val il = BigInteger(1, i.copyOfRange(0, 32))
        require(il < N) { "invalid child" }
        val childKey = il.add(priv).mod(N)
        require(childKey.signum() != 0) { "invalid child key" }
        val fp = Ripemd160.hash160(pub.compressed()).copyOfRange(0, 4)
        return Bip32(childKey, i.copyOfRange(32, 64), depth + 1, fp, index)
    }

    /** SLIP-132 zpub/zprv version bytes used by Pearl (0x04b24746 / 0x04b2430c). */
    fun neuteredString(): String {
        val payload = java.io.ByteArrayOutputStream()
        payload.write(byteArrayOf(0x04, 0xb2.toByte(), 0x47, 0x46))
        payload.write(depth)
        payload.write(parentFingerprint)
        payload.write(byteArrayOf(
            (childNumber ushr 24).toByte(), (childNumber ushr 16).toByte(),
            (childNumber ushr 8).toByte(), childNumber.toByte()
        ))
        payload.write(chainCode)
        payload.write(pub.compressed())
        return Base58.encodeCheck(payload.toByteArray())
    }
}

object Base58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    fun encode(input: ByteArray): String {
        var zeros = 0
        while (zeros < input.size && input[zeros].toInt() == 0) zeros++
        var num = BigInteger(1, input)
        val sb = StringBuilder()
        val n = BigInteger.valueOf(58)
        while (num.signum() > 0) {
            val qr = num.divideAndRemainder(n)
            sb.append(ALPHABET[qr[1].toInt()])
            num = qr[0]
        }
        repeat(zeros) { sb.append('1') }
        return sb.reverse().toString()
    }

    fun encodeCheck(payload: ByteArray): String {
        val checksum = Hashes.sha256d(payload).copyOfRange(0, 4)
        return encode(payload + checksum)
    }
}
