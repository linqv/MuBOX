package com.example.comicdav.security

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface CredentialCipher {
    fun encrypt(plainText: String): String
    fun decrypt(storedValue: String): String
}

class CredentialDecryptionException(
    field: String,
    cause: Throwable? = null,
) : RuntimeException("Failed to decrypt credential field: $field", cause)

class AesGcmCredentialCipher(private val key: SecretKey) : CredentialCipher {

    override fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return "v1:${encoder.encodeToString(iv)}:${encoder.encodeToString(ciphertext)}"
    }

    override fun decrypt(storedValue: String): String {
        if (!storedValue.startsWith("v1:")) return storedValue
        val parts = storedValue.removePrefix("v1:").split(":", limit = 2)
        if (parts.size != 2) {
            throw CredentialDecryptionException("credential")
        }
        return try {
            val decoder = Base64.getUrlDecoder()
            val iv = decoder.decode(parts[0])
            val ciphertext = decoder.decode(parts[1])
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: CredentialDecryptionException) {
            throw e
        } catch (e: Exception) {
            throw CredentialDecryptionException("credential", e)
        }
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
    }
}
