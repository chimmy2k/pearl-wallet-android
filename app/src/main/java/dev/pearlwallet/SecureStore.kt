package dev.pearlwallet

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.pearlwallet.core.SeedVault
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Mnemonic at rest: AES-256/GCM with a key that never leaves the Android Keystore.
 * No plaintext seed on disk, no cloud backup (allowBackup=false in the manifest).
 */
class SecureStore(private val ctx: Context) : SeedVault {

    private val prefs = ctx.getSharedPreferences("pearl_wallet", Context.MODE_PRIVATE)
    private val alias = "pearl_wallet_seed_v1"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    override fun saveMnemonic(mnemonic: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val ct = cipher.doFinal(mnemonic.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString("seed_iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            .putString("seed_ct", Base64.encodeToString(ct, Base64.NO_WRAP))
            .apply()
    }

    override fun loadMnemonic(): String? {
        val ivB64 = prefs.getString("seed_iv", null) ?: return null
        val ctB64 = prefs.getString("seed_ct", null) ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE, key(),
                GCMParameterSpec(128, Base64.decode(ivB64, Base64.NO_WRAP))
            )
            String(cipher.doFinal(Base64.decode(ctB64, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    override fun wipe() {
        prefs.edit().clear().apply()
        try {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        } catch (_: Exception) {
        }
    }

    override var indexerUrl: String
        get() = prefs.getString("indexer", DEFAULT_INDEXER) ?: DEFAULT_INDEXER
        set(v) {
            prefs.edit().putString("indexer", v.trim().trimEnd('/')).apply()
        }

    override var gapLimit: Int
        get() = prefs.getInt("gap", 12)
        set(v) {
            prefs.edit().putInt("gap", v.coerceIn(4, 60)).apply()
        }

    companion object {
        const val DEFAULT_INDEXER = "https://blockbook.pearlresearch.ai"
    }
}
