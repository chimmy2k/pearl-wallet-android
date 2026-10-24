package dev.pearlwallet.core

/**
 * Where the seed lives. Implemented by the Android Keystore on device and by an
 * in-memory fake in tests, so all wallet logic stays testable off-device.
 */
interface SeedVault {
    fun loadMnemonic(): String?
    fun saveMnemonic(mnemonic: String)
    fun wipe()

    var indexerUrl: String
    var gapLimit: Int
}
