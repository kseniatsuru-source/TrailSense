package com.mayushi.trailsense.network

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureSecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("secure_connection", Context.MODE_PRIVATE)
    private val alias = "trailsense-hermes-key"

    fun save(secret: String) {
        if (secret.isBlank()) { clear(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        prefs.edit()
            .putString("key_cipher", java.util.Base64.getEncoder().encodeToString(cipher.doFinal(secret.toByteArray())))
            .putString("key_iv", java.util.Base64.getEncoder().encodeToString(cipher.iv))
            .apply()
    }

    fun read(): String? = try {
        val encrypted = prefs.getString("key_cipher", null) ?: return null
        val iv = prefs.getString("key_iv", null) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, java.util.Base64.getDecoder().decode(iv)))
        String(cipher.doFinal(java.util.Base64.getDecoder().decode(encrypted)))
    } catch (_: Exception) { null }

    fun clear() { prefs.edit().remove("key_cipher").remove("key_iv").apply() }

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
}
