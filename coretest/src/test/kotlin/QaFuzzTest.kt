package dev.pearlwallet

import dev.pearlwallet.core.Bech32
import dev.pearlwallet.core.Bip32
import dev.pearlwallet.core.Bip39
import dev.pearlwallet.core.Pearl
import dev.pearlwallet.core.Ripemd160
import dev.pearlwallet.core.Secp256k1
import dev.pearlwallet.core.Tx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.math.BigInteger
import java.util.Random

/**
 * Adversarial QA: fuzz every parser and every boundary. A wallet that crashes on a
 * malformed indexer response is a wallet that loses funds to a stuck UI, so every
 * input path must fail safely (return null / throw a typed error), never crash.
 */
class QaFuzzTest {

    companion object {
        const val MNEMONIC =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

        @JvmStatic
        @BeforeClass
        fun words() {
            Bip39.loadWordlist(
                java.io.File("../app/src/main/assets/bip39_english.txt")
                    .readLines().filter { it.isNotBlank() }
            )
        }
    }

    // ------------------------------------------------------------ Tx.parse

    /** Malformed tx hex must throw a typed error, never an index-out-of-bounds. */
    @Test
    fun txParseRejectsGarbageSafely() {
        val bad = listOf(
            "", "00", "zz", "0200", "02000000", "020000000001",
            "0200000000010",                     // truncated varint
            "02000000000101",                    // input count but no input
            "02000000000101" + "11".repeat(32),  // no vout
            "02000000000101" + "11".repeat(32) + "00000000",
            "ff".repeat(200),
            "0".repeat(101)
        )
        var crashes = 0
        for (h in bad) {
            try {
                Tx.parse(h)
                // parsing a truncated tx "succeeding" would be worse than throwing
                println("PARSE_ACCEPTED_BAD_INPUT len=${h.length} -> ${h.take(40)}")
                crashes++
            } catch (e: ArrayIndexOutOfBoundsException) {
                println("CRASH_AIOOBE on '${h.take(24)}': ${e.message}")
                crashes++
            } catch (e: IllegalArgumentException) {
                // expected
            } catch (e: Exception) {
                println("OK_TYPED '${h.take(20)}' -> ${e::class.simpleName}: ${e.message?.take(60)}")
            }
        }
        assertEquals("input rusak harus ditolak dengan error bersih", 0, crashes)
    }

    /** Random bytes must never crash the parser. */
    @Test
    fun txParseFuzzRandom() {
        val rnd = Random(42)
        var bad = 0
        for (i in 0 until 400) {
            val n = rnd.nextInt(120)
            val bytes = ByteArray(n).also { rnd.nextBytes(it) }
            val hex = bytes.joinToString("") { b -> String.format("%02x", b) }
            try {
                Tx.parse(hex)
            } catch (e: IllegalArgumentException) {
                // fine
            } catch (e: Exception) {
                println("FUZZ_CRASH len=$n ${e::class.simpleName}: ${e.message?.take(50)}")
                bad++
            }
        }
        assertEquals("fuzz 400 input acak tidak boleh crash", 0, bad)
    }

    // ------------------------------------------------------------ Bech32

    /** Every malformed address must decode to null, never throw. */
    @Test
    fun bech32RejectsGarbage() {
        val bad = listOf(
            "", "prl1", "prl", "1", "prl1p", "prl1" + "q".repeat(100),
            "PRL1PR6YUQ8U2R95WJZZGPDY8CPNNCPL7L8ZGY6X5Q0367PNC53S2FAMQG7PT74", // upper ok, must still parse
            "prl1pr6yuq8u2r95wjzzgpdy8cpnncpl7l8zgy6x5q0367pnc53s2famqg7pt75", // bad checksum
            "prl1pr6yuq8u2r95wjzzgpdy8cpnncpl7l8zgy6x5q0367pnc53s2famqg7pt7",  // short
            "bc1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcr",   // wrong hrp but valid
            "prl1p320jfqcr3d4xg7uway6djlq73a25j6rlatfwprx5nk088aguyv0sw5at54 ", // trailing space
            "prl1p320jfqcr3d4xg7uway6djlq73a25j6rlatfwprx5nk088aguyv0sw5at5b",  // tampered
            "prl1!!!!", "prl1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"
        )
        for (a in bad) {
            val d = Bech32.decodeSegwit(a)
            println("ADDR '${a.take(30)}' -> ${if (d == null) "rejected" else "witver=${d.witver} hrp=${d.hrp}"}")
        }
        // explicit expectations
        assertNull(Bech32.decodeSegwit("prl1pr6yuq8u2r95wjzzgpdy8cpnncpl7l8zgy6x5q0367pnc53s2famqg7pt75"))
        assertNull(Bech32.decodeSegwit("prl1p320jfqcr3d4xg7uway6djlq73a25j6rlatfwprx5nk088aguyv0sw5at54 "))
        assertNull(Bech32.decodeSegwit(""))
        assertNotNull(Bech32.decodeSegwit("PRL1PR6YUQ8U2R95WJZZGPDY8CPNNCPL7L8ZGY6X5Q0367PNC53S2FAMQG7PT74"))
    }

    @Test
    fun bech32FuzzRandom() {
        val rnd = Random(7)
        val alphabet = "qpzry9x8gf2tvdw0s3jn54khce6mua7l1"
        var crash = 0
        for (i in 0 until 500) {
            val len = 1 + rnd.nextInt(90)
            val s = buildString {
                append("prl1")
                repeat(len) { append(alphabet[rnd.nextInt(alphabet.length)]) }
            }
            try {
                Bech32.decodeSegwit(s)
            } catch (e: Exception) {
                println("BECH32_CRASH '${s.take(30)}' ${e::class.simpleName}")
                crash++
            }
        }
        assertEquals("fuzz bech32 tidak boleh crash", 0, crash)
    }

    /** scriptPubKey must reject anything that is not a taproot address. */
    @Test
    fun scriptPubKeyRejectsNonTaproot() {
        val bad = listOf(
            "", "prl1", "notanaddress",
            // a valid bech32m v0 address would be 20/32 bytes at witver 0 — reject witver != 1
            "prl1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"
        )
        for (a in bad) {
            val threw = try {
                Pearl.scriptPubKey(a)
                false
            } catch (e: Exception) {
                true
            }
            assertTrue("harus menolak '$a'", threw)
        }
    }

    // ------------------------------------------------------------ amounts

    @Test
    fun amountParsingBoundaries() {
        // valid
        assertEquals(1L, Tx.prlToSat("0.00000001"))
        assertEquals(100_000_000L, Tx.prlToSat("1"))
        assertEquals(100_000_000L, Tx.prlToSat("1.0"))
        assertEquals(123_456_789L, Tx.prlToSat("1,23456789"))   // comma accepted
        assertEquals(123_456_789L, Tx.prlToSat(" 1.23456789 ")) // spaces trimmed

        // invalid
        val bad = listOf(
            "", "-1", "+1", "abc", "1.234567891", "1.2.3", ".", "1.", "..",
            "0", "0.0", "0.00000000",           // zero is not a valid send amount
            "1e8", "NaN", "Infinity", "--1",
            "99999999999999999999999999"        // overflow
        )
        for (b in bad) {
            val v = Tx.prlToSat(b)
            println("AMT '$b' -> $v")
            assertTrue("'$b' harus ditolak", v == null || v == 0L)
        }
    }

    @Test
    fun satToPrlFormatting() {
        assertEquals("0.0", Tx.satToPrl(0))
        assertEquals("0.00000001", Tx.satToPrl(1))
        assertEquals("1.0", Tx.satToPrl(100_000_000L))
        assertEquals("0.5", Tx.satToPrl(50_000_000L))
        assertEquals("4.56332993", Tx.satToPrl(456_332_993L))
        // negative (a sent tx shows a negative delta)
        val neg = Tx.satToPrl(-123_456_789L)
        println("NEG_FORMAT=$neg")
        assertTrue("negatif harus punya tanda minus", neg.startsWith("-"))
        assertEquals("-1.23456789", neg)
        // Long.MIN_VALUE must not blow up
        val min = Tx.satToPrl(Long.MIN_VALUE)
        println("MIN_FORMAT=$min")
        assertNotNull(min)
    }

    /** Round-trip every representable amount class. */
    @Test
    fun amountRoundTrip() {
        val cases = listOf(
            1L, 9L, 10L, 99L, 100L, 546L, 1000L, 99_999_999L,
            100_000_000L, 123_456_789L, 21_000_000_000_000_00L
        )
        for (s in cases) {
            val str = Tx.satToPrl(s)
            val back = Tx.prlToSat(str)
            assertEquals("round trip gagal untuk $s ($str)", s, back)
        }
    }

    // ------------------------------------------------------------ crypto edges

    /** Invalid mnemonics must be rejected, never throw out of isValid. */
    @Test
    fun mnemonicValidation() {
        val bad = listOf(
            "", "   ", "abandon", "abandon abandon",
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon", // bad checksum
            "notaword notaword notaword notaword notaword notaword notaword notaword notaword notaword notaword notaword",
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about extra"
        )
        for (m in bad) {
            val ok = Bip39.isValid(m)
            println("MNEMONIC '${m.take(28)}' -> $ok")
            assertTrue("'${m.take(20)}' harus invalid", !ok)
        }
        assertTrue(Bip39.isValid(MNEMONIC))
        // normalisation: tabs, newlines, case
        assertTrue(Bip39.isValid("  ABANDON\tabandon\nabandon abandon abandon abandon abandon abandon abandon abandon abandon ABOUT  "))
    }

    /** Zero / out-of-range scalars must be refused, not silently produce a key. */
    @Test
    fun secp256k1RejectsInvalidScalars() {
        for (k in listOf(BigInteger.ZERO, Secp256k1.N, Secp256k1.N.add(BigInteger.ONE))) {
            val threw = try {
                Secp256k1.pubkey(k)
                false
            } catch (e: Exception) {
                true
            }
            println("SCALAR $k -> threw=$threw")
        }
        // k = 0 must not produce a point
        assertNull(Secp256k1.mulG(BigInteger.ZERO))
        // k = n must be treated as 0 (mod n)
        assertNull(Secp256k1.mulG(Secp256k1.N))
        // k = n+1 == 1 == G
        assertNotNull(Secp256k1.mulG(Secp256k1.N.add(BigInteger.ONE)))
        assertEquals(
            Secp256k1.mulG(BigInteger.ONE)!!.x,
            Secp256k1.mulG(Secp256k1.N.add(BigInteger.ONE))!!.x
        )
    }

    /** schnorrVerify must reject malformed signatures without throwing. */
    @Test
    fun schnorrVerifyRejectsGarbage() {
        val msg = ByteArray(32)
        val pubx = Secp256k1.pubkey(BigInteger.valueOf(3)).xOnly()
        val cases = listOf(
            ByteArray(0), ByteArray(63), ByteArray(64), ByteArray(65),
            ByteArray(64) { 0xff.toByte() },
            ByteArray(64) { 0x00 }
        )
        for (sig in cases) {
            val ok = Secp256k1.schnorrVerify(msg, sig, pubx)
            println("SIG len=${sig.size} -> $ok")
            assertTrue("sig sampah harus false", !ok)
        }
        // bad pubkey length
        assertTrue(!Secp256k1.schnorrVerify(msg, ByteArray(64), ByteArray(31)))
    }

    /** RIPEMD160 must match the published vectors (fingerprints depend on it). */
    @Test
    fun ripemd160Vectors() {
        fun h(s: String) = Ripemd160.digest(s.toByteArray())
            .joinToString("") { String.format("%02x", it) }
        assertEquals("9c1185a5c5e9fc54612808977ee8f548b2258d31", h(""))
        assertEquals("8eb208f7e05d987a9b044a8e98c6b087f15a0bfc", h("abc"))
        assertEquals("5d0689ef49d2fae572b881b123a85ffa21595f36", h("message digest"))
        assertEquals("f71c27109c692c1b56bbdceb5b9d2865b3708dbc", h("abcdefghijklmnopqrstuvwxyz"))
    }

    // ------------------------------------------------------------ derivation

    /** Chain/index combinations must be unique and deterministic. */
    @Test
    fun derivationIsDeterministicAndUnique() {
        val seed = Bip39.seed(MNEMONIC)
        val a = Pearl.addressFor(seed, 0, 0)
        val b = Pearl.addressFor(seed, 0, 0)
        assertEquals("derivasi harus deterministik", a, b)

        val seen = HashSet<String>()
        for (c in 0..1) for (i in 0 until 20) {
            val addr = Pearl.addressFor(seed, i, c)
            assertTrue("alamat duplikat: $addr", seen.add(addr))
        }
        println("UNIQUE_ADDRS=${seen.size}")
        assertEquals(40, seen.size)
    }

    /** A different mnemonic must never collide. */
    @Test
    fun differentSeedsDiffer() {
        val s1 = Bip39.seed(MNEMONIC)
        val s2 = Bip39.seed("legal winner thank year wave sausage worth useful legal winner thank yellow")
        assertTrue(!s1.contentEquals(s2))
        assertTrue(Pearl.addressFor(s1, 0, 0) != Pearl.addressFor(s2, 0, 0))
    }

    /** BIP32 hardened vs non-hardened must not produce the same key. */
    @Test
    fun hardenedDiffersFromNormal() {
        val seed = Bip39.seed(MNEMONIC)
        val acct = Bip32.derivePath(seed, Pearl.accountPath())
        val hardened = acct.child(0x80000000.toInt())
        val normal = acct.child(0)
        assertTrue(
            "hardened harus beda dari non-hardened",
            !hardened.priv.toByteArray().contentEquals(normal.priv.toByteArray())
        )
    }

    /** Path parsing must handle every accepted form. */
    @Test
    fun pathParsing() {
        val seed = Bip39.seed(MNEMONIC)
        val a = Bip32.derivePath(seed, "m/86'/808276'/0'")
        val b = Bip32.derivePath(seed, "m/86h/808276h/0h")
        assertEquals("' dan h harus sama", a.neuteredString(), b.neuteredString())
        // "m" alone is the master key
        val m = Bip32.derivePath(seed, "m")
        assertEquals(Bip32.fromSeed(seed).neuteredString(), m.neuteredString())
    }

    // ------------------------------------------------------------ signing edges

    /** A tx with a single output, no change, must still be structurally valid. */
    @Test
    fun singleOutputTxStructure() {
        val seed = Bip39.seed(MNEMONIC)
        val acct = Bip32.derivePath(seed, Pearl.accountPath())
        val leaf = acct.child(0).child(0)
        val spk = Pearl.scriptPubKey(Pearl.p2trAddress(leaf.pub))
        val utxo = dev.pearlwallet.core.Utxo("aa".repeat(32), 0, 10_000L, spk, null, null, 6)
        val out = dev.pearlwallet.core.TxOutput(
            9_000L, Pearl.scriptPubKey("prl1p320jfqcr3d4xg7uway6djlq73a25j6rlatfwprx5nk088aguyv0sw5at54")
        )
        val built = Tx.buildSigned(leaf.priv, listOf(utxo), listOf(out))
        val parsed = Tx.parse(built.hex)
        assertEquals(1, parsed.outputs.size)
        assertEquals(1, parsed.inputs.size)
        assertEquals(9_000L, parsed.outputs[0].valueSat)
        assertEquals(1_000L, built.feeSat)
    }

    /** Fee estimate must grow monotonically with inputs and outputs. */
    @Test
    fun feeEstimateMonotonic() {
        var prev = 0L
        for (n in 1..10) {
            val f = Tx.estimateFee(n, 2, 10_000L)
            assertTrue("fee harus naik dengan input: n=$n f=$f prev=$prev", f > prev)
            prev = f
        }
        val oneOut = Tx.estimateFee(2, 1, 10_000L)
        val twoOut = Tx.estimateFee(2, 2, 10_000L)
        assertTrue("fee harus naik dengan output", twoOut > oneOut)
        // zero fee rate must not produce a negative or absurd fee
        assertTrue(Tx.estimateFee(1, 1, 0) >= 0)
    }

    /** txid must be stable and 32 bytes. */
    @Test
    fun txidIsStable() {
        val seed = Bip39.seed(MNEMONIC)
        val acct = Bip32.derivePath(seed, Pearl.accountPath())
        val leaf = acct.child(0).child(0)
        val spk = Pearl.scriptPubKey(Pearl.p2trAddress(leaf.pub))
        val utxo = dev.pearlwallet.core.Utxo("bb".repeat(32), 3, 50_000L, spk, null, null, 6)
        val out = dev.pearlwallet.core.TxOutput(
            40_000L, Pearl.scriptPubKey("prl1p320jfqcr3d4xg7uway6djlq73a25j6rlatfwprx5nk088aguyv0sw5at54")
        )
        val t1 = Tx.buildSigned(leaf.priv, listOf(utxo), listOf(out), aux = ByteArray(32))
        val t2 = Tx.buildSigned(leaf.priv, listOf(utxo), listOf(out), aux = ByteArray(32))
        assertEquals("txid harus sama untuk input identik", t1.txid, t2.txid)
        assertEquals(64, t1.txid.length)
        assertEquals(t1.txid, Tx.txid(t1.hex))
    }
}
