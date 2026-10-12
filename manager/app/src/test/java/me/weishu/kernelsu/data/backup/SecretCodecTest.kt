package me.weishu.kernelsu.data.backup

import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretCodecTest {

    private fun newKey(): SecretKey =
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun `round trips the plaintext`() {
        val key = newKey()
        val encoded = SecretCodec.encrypt(key, "s3cret-password")
        assertEquals("s3cret-password", SecretCodec.decrypt(key, encoded))
    }

    @Test
    fun `ciphertext is randomised per call`() {
        val key = newKey()
        assertNotEquals(SecretCodec.encrypt(key, "same"), SecretCodec.encrypt(key, "same"))
    }

    @Test
    fun `a different key cannot decrypt`() {
        val encoded = SecretCodec.encrypt(newKey(), "secret")
        assertThrows(Exception::class.java) { SecretCodec.decrypt(newKey(), encoded) }
    }

    @Test
    fun `tampered records are rejected`() {
        val key = newKey()
        val encoded = SecretCodec.encrypt(key, "secret")
        val tampered = encoded.dropLast(2) + if (encoded.endsWith("AA")) "BB" else "AA"
        assertThrows(Exception::class.java) { SecretCodec.decrypt(key, tampered) }
    }

    @Test
    fun `non base64 input is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { SecretCodec.decrypt(newKey(), "not base64!!") }
    }

    @Test
    fun `empty plaintext round trips`() {
        val key = newKey()
        assertTrue(SecretCodec.decrypt(key, SecretCodec.encrypt(key, "")).isEmpty())
    }

    @Test
    fun `generated iv records round trip`() {
        // Mirrors the Android Keystore path, which refuses a caller-provided IV.
        val key = newKey()
        val encoded = SecretCodec.encryptWithGeneratedIv(key, "keystore-secret")
        assertEquals("keystore-secret", SecretCodec.decrypt(key, encoded))
        assertNotEquals(encoded, SecretCodec.encryptWithGeneratedIv(key, "keystore-secret"))
    }
}
