package com.example.comicdav.security

import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class CredentialCipherTest {

    private lateinit var cipher: CredentialCipher
    private lateinit var key: SecretKey

    @Before
    fun setUp() {
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256)
        key = keyGen.generateKey()
        cipher = AesGcmCredentialCipher(key)
    }

    @Test
    fun `encrypt produces v1 prefix`() {
        val encrypted = cipher.encrypt("hello")
        assertTrue("Expected v1: prefix, got: $encrypted", encrypted.startsWith("v1:"))
    }

    @Test
    fun `encrypt then decrypt returns original plaintext`() {
        val plaintext = "mySecretPassword123"
        val encrypted = cipher.encrypt(plaintext)
        val decrypted = cipher.decrypt(encrypted)
        assertEquals(plaintext, decrypted)
    }

    @Test
    fun `encrypt then decrypt empty string`() {
        val encrypted = cipher.encrypt("")
        val decrypted = cipher.decrypt(encrypted)
        assertEquals("", decrypted)
    }

    @Test
    fun `encrypt then decrypt chinese characters`() {
        val plaintext = "密码测试中文"
        val encrypted = cipher.encrypt(plaintext)
        val decrypted = cipher.decrypt(encrypted)
        assertEquals(plaintext, decrypted)
    }

    @Test
    fun `encrypt then decrypt special characters`() {
        val plaintext = "p@ss!w0rd#\$%^&*()_+-=[]{}|;':\",./<>?"
        val encrypted = cipher.encrypt(plaintext)
        val decrypted = cipher.decrypt(encrypted)
        assertEquals(plaintext, decrypted)
    }

    @Test
    fun `same plaintext produces different ciphertext due to random IV`() {
        val plaintext = "samePassword"
        val encrypted1 = cipher.encrypt(plaintext)
        val encrypted2 = cipher.encrypt(plaintext)
        assertNotEquals(encrypted1, encrypted2)
    }

    @Test
    fun `decrypt non-v1 value returns original value`() {
        val legacy = "plainTextPassword"
        val result = cipher.decrypt(legacy)
        assertEquals(legacy, result)
    }

    @Test
    fun `decrypt empty string returns empty string`() {
        assertEquals("", cipher.decrypt(""))
    }

    @Test
    fun `decrypt malformed v1 ciphertext throws exception without password content`() {
        val malformed = "v1:badiv:badciphertext"
        try {
            cipher.decrypt(malformed)
            fail("Expected exception for malformed ciphertext")
        } catch (e: CredentialDecryptionException) {
            assertTrue(
                "Exception message should not contain ciphertext content",
                !e.message!!.contains("badciphertext"),
            )
        }
    }

    @Test
    fun `decrypt v1 with wrong number of parts throws exception`() {
        val malformed = "v1:onlyonepart"
        try {
            cipher.decrypt(malformed)
            fail("Expected exception for malformed ciphertext")
        } catch (e: CredentialDecryptionException) {
            // expected
        }
    }
}
