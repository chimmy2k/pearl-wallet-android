package dev.pearlwallet

import dev.pearlwallet.core.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.math.BigInteger

/**
 * Vector tests for the pure-Kotlin wallet core.
 *
 * Ground truth sources:
 *  - BIP39 / BIP86 / BIP340 official test vectors
 *  - Pearl's own Go wallet code (hdkeychain + txscript) compiled as `wcheck`
 *  - a real on-chain Pearl transaction (signed, verifies under Pearl's script engine)
 */
class CoreVectorsTest {

    private val mnemonic =
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

    companion object {
        @JvmStatic
        @BeforeClass
        fun loadWords() {
            val candidates = listOf(
                java.io.File("../app/src/main/assets/bip39_english.txt"),
                java.io.File("app/src/main/assets/bip39_english.txt")
            )
            val f = candidates.firstOrNull { it.exists() }
                ?: error("bip39 wordlist not found from ${java.io.File(".").absolutePath}")
            Bip39.loadWordlist(f.readLines().filter { it.isNotBlank() })
        }
    }

    private fun hex(b: ByteArray) = b.joinToString("") { String.format("%02x", it) }

    @Test
    fun bip39SeedMatchesOfficialVector() {
        // BIP39 official vectors: same mnemonic, two passphrases.
        assertEquals(
            "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc1" +
                "9a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4",
            hex(Bip39.seed(mnemonic))
        )
        assertEquals(
            "c55257c360c07c72029aebc1b53c05ed0362ada38ead3e3e9efa3708e5349553" +
                "1f09a6987599d18264c1e1c92f2cf141630c7a3c4ab7c81b2f001698e7463b04",
            hex(Bip39.seed(mnemonic, "TREZOR"))
        )
    }

    @Test
    fun bip39EntropyRoundTrip() {
        val ent = ByteArray(16) { it.toByte() }
        val m = Bip39.entropyToMnemonic(ent)
        assertEquals(12, m.split(" ").size)
        assertTrue(hex(Bip39.mnemonicToEntropy(m)) == hex(ent))
        assertTrue(Bip39.isValid(m))
        assertTrue(!Bip39.isValid("abandon abandon abandon"))
    }

    /** BIP86 spec vector: mnemonic + m/86'/0'/0'/0/0 must give this exact address. */
    @Test
    fun bip86TaprootAddressVector() {
        val seed = Bip39.seed(mnemonic)
        val acct = Bip32.derivePath(seed, "m/86'/0'/0'")
        val leaf = acct.child(0).child(0)
        val addr = Pearl.p2trAddress(leaf.pub, "bc")
        assertEquals("bc1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcr", addr)
    }

    /** BIP340 official test vector 0 (signing + verification). */
    @Test
    fun bip340Vector0() {
        val seckey = BigInteger("3")
        val msg = ByteArray(32)
        val aux = ByteArray(32)
        val sig = Secp256k1.schnorrSign(msg, seckey, aux)
        assertEquals(
            "e907831f80848d1069a5371b402410364bdf1c5f8307b0084c55f1ce2dca8215" +
                "25f66a4a85ea8b71e482a74f382d2ce5ebeee8fdb2172f477df4900d310536c0",
            hex(sig)
        )
        val pubx = Secp256k1.pubkey(seckey).xOnly()
        assertEquals("f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9", hex(pubx))
        assertTrue(Secp256k1.schnorrVerify(msg, sig, pubx))
    }

    /** Pearl mainnet derivation, ground truth produced by Pearl's own Go wallet code. */
    @Test
    fun pearlAddressesMatchGoWallet() {
        val seed = Bip39.seed(mnemonic)
        assertEquals(
            "prl1pr6yuq8u2r95wjzzgpdy8cpnncpl7l8zgy6x5q0367pnc53s2famqg7pt74",
            Pearl.addressFor(seed, index = 0, chain = 0)
        )
        assertEquals(
            "prl1pyx3nlscz8rvsxqhcjtyqt2g5szuk9ss7m5saszu3afwwhvn9zp2sz62rhm",
            Pearl.addressFor(seed, index = 1, chain = 0)
        )
        assertEquals(
            "prl1puwagcza86exfh3cx6ur08wq7njc32e66ep0zje7le6c7cak8lqeq655gqa",
            Pearl.addressFor(seed, index = 0, chain = 1)
        )
        assertEquals(
            "prl1pe39xcj4w8p0w5hj80p053sr0s6qggkw2hzts424yu9vjce4q84fs4cy3fq",
            Pearl.addressFor(seed, index = 5, chain = 0)
        )
    }

    /** zpub for m/86'/808276'/0' must match what Blockbook accepts. */
    @Test
    fun accountXpubMatches() {
        val seed = Bip39.seed(mnemonic)
        val acct = Bip32.derivePath(seed, "m/86'/808276'/0'")
        assertEquals(
            "zpub6s66goCpFBSqGDZyyD6a1Xf8C5TseQYcPFQNpjMzFeTCZpZD6ShaEzAKqv8tDiv81N1RmPyRTu1D65iQv59c7wHZyCbQmnaARE7P6UFNJeG",
            acct.neuteredString()
        )
    }

    /** Real on-chain tx: txid + bech32m scriptPubKey decoding. */
    @Test
    fun realTxParsing() {
        val txHex = "01000000000101e81047d98fce07a8c0df863f715c8b0f15ccdc1a2912b8897be27bdf655c48f5" +
            "4301000000ffffffff0249fa8a00000000002251208a9f2483038b6a647b8ee934d97c1e8f5549" +
            "687fead2e08cd49d9e73f51c231fdbc046110000000022512053e71242e7e00be6519783160185" +
            "4b375cf92ff1c730527ba0b26d28c193c50101408b7b26806770924a7b77b2d1b1f72c9036313e" +
            "9ae3efa5104aa2a3decdc5af3dded0616ecc681e02d90acee67838b77962b520129d66f6e64403" +
            "715c78aefe1400000000"
        assertEquals("998578dbea941fbe90fbc5335c314300bf07af568230d0239f29127688ad4e3e", Tx.txid(txHex))
        assertEquals(
            "51208a9f2483038b6a647b8ee934d97c1e8f5549687fead2e08cd49d9e73f51c231f",
            hex(Pearl.scriptPubKey("prl1p320jfqcr3d4xg7uway6djlq73a25j6rlatfwprx5nk088aguyv0sw5at54"))
        )
    }

    /** Address validation must reject garbage and wrong HRP. */
    @Test
    fun addressValidation() {
        val ok = Bech32.decodeSegwit("prl1pr6yuq8u2r95wjzzgpdy8cpnncpl7l8zgy6x5q0367pnc53s2famqg7pt74")
        assertNotNull(ok)
        assertEquals("prl", ok!!.hrp)
        assertEquals(1, ok.witver)
        assertEquals(32, ok.program.size)
        assertEquals(null, Bech32.decodeSegwit("prl1pr6yuq8u2r95wjzzgpdy8cpnncpl7l8zgy6x5q0367pnc53s2famqg7pt75"))
        assertEquals(null, Bech32.decodeSegwit("bc1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcs"))
    }

    @Test
    fun amountMath() {
        assertEquals(100_000_000L, Tx.prlToSat("1"))
        assertEquals(1L, Tx.prlToSat("0.00000001"))
        assertEquals(123_456_789L, Tx.prlToSat("1.23456789"))
        assertEquals(null, Tx.prlToSat("1.234567891"))
        assertEquals(null, Tx.prlToSat("abc"))
        assertEquals("1.23456789", Tx.satToPrl(123_456_789L))
        assertEquals("0.5", Tx.satToPrl(50_000_000L))
    }

    /**
     * Full pipeline: build + sign a 1-input/1-output tx, print it so the Go harness can
     * validate the signature with Pearl's own consensus engine (wcheck verifyall).
     */
    @Test
    fun signAndEmitTxForConsensusCheck() {
        val seed = Bip39.seed(mnemonic)
        val acct = Bip32.derivePath(seed, "m/86'/808276'/0'")
        val leaf = acct.child(0).child(0)
        val myAddr = Pearl.p2trAddress(leaf.pub)
        val dest = Pearl.addressFor(seed, index = 1, chain = 0)

        val utxo = Utxo(
            txid = "11".repeat(32),
            vout = 0,
            valueSat = 100_000L,
            scriptPubKey = Pearl.scriptPubKey(myAddr),
            address = myAddr
        )
        val out = TxOutput(90_000L, Pearl.scriptPubKey(dest))
        val built = Tx.buildSigned(leaf.priv, listOf(utxo), listOf(out))
        println("SIGNED_TX=${built.hex}")
        println("UTXOS_JSON=[{\"script\":\"${hex(utxo.scriptPubKey!!)}\",\"value\":${utxo.valueSat}}]")
        println("TXID=${built.txid}")
        assertEquals(10_000L, built.feeSat)
        assertTrue(built.hex.length > 100)
    }

    /** Two-input spend (exercises multi-input sighash + witness ordering). */
    @Test
    fun signTwoInputs() {
        val seed = Bip39.seed(mnemonic)
        val acct = Bip32.derivePath(seed, "m/86'/808276'/0'")
        val leaf = acct.child(0).child(0)
        val myAddr = Pearl.p2trAddress(leaf.pub)
        val spk = Pearl.scriptPubKey(myAddr)
        val utxos = listOf(
            Utxo("22".repeat(32), 1, 60_000L, spk, myAddr),
            Utxo("33".repeat(32), 0, 50_000L, spk, myAddr)
        )
        val out = TxOutput(100_000L, Pearl.scriptPubKey(Pearl.addressFor(seed, 2, 0)))
        val built = Tx.buildSigned(leaf.priv, utxos, listOf(out))
        println("SIGNED_TX2=${built.hex}")
        println("UTXOS_JSON2=[{\"script\":\"${hex(spk)}\",\"value\":60000},{\"script\":\"${hex(spk)}\",\"value\":50000}]")
        assertEquals(10_000L, built.feeSat)
    }
}
