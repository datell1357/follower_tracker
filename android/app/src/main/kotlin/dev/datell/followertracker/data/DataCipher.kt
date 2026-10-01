package dev.datell.followertracker.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class DataCipher(private val alias: String = "followertracker.local.v1") {
    private companion object { val keyLock = Any() }

    private fun key(): SecretKey = synchronized(keyLock) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return@synchronized it }
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }

    fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return byteArrayOf(1, cipher.iv.size.toByte()) + cipher.iv + cipher.doFinal(plain)
    }

    fun open(encrypted: ByteArray): ByteArray {
        require(encrypted.size > 18 && encrypted[0].toInt() == 1) { "Invalid local record" }
        val size = encrypted[1].toInt()
        require(size in 12..16 && encrypted.size > 2 + size + 16)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, encrypted.copyOfRange(2, 2 + size)))
            doFinal(encrypted.copyOfRange(2 + size, encrypted.size))
        }
    }
}
