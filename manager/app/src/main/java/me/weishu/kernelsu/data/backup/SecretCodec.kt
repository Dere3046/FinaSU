package me.weishu.kernelsu.data.backup

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM envelope used by [SecretStore] implementations: a 12-byte IV prepended to the ciphertext
 * and tag, Base64-encoded for storage.
 *
 * Kept free of Android APIs so the wire format can be unit tested on the JVM with a plain AES key.
 */
internal object SecretCodec {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    private val random = SecureRandom()

    /**
     * Encrypts with a caller-supplied random IV.
     *
     * Only usable with keys that permit it; an Android Keystore key rejects a caller-provided IV
     * unless it was generated with `setRandomizedEncryptionRequired(false)`. Use
     * [encryptWithGeneratedIv] for those.
     */
    fun encrypt(key: SecretKey, plaintext: String): String {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return frame(iv, cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)))
    }

    /**
     * Encrypts letting the cipher pick the IV, which is what an Android Keystore key requires.
     * The generated IV is framed with the ciphertext so [decrypt] can recover it.
     */
    fun encryptWithGeneratedIv(key: SecretKey, plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        require(iv.size == IV_BYTES) { "Unexpected IV length: ${iv.size}" }
        return frame(iv, cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)))
    }

    /** Decrypts a record produced by either encryption entry point: a keystore key accepts a caller IV for decryption. */
    fun decrypt(key: SecretKey, encoded: String): String {
        val raw = try {
            Base64.getDecoder().decode(encoded)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Malformed secret record", e)
        }
        require(raw.size > IV_BYTES) { "Secret record is too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(TAG_BITS, raw.copyOfRange(0, IV_BYTES)),
        )
        return String(cipher.doFinal(raw.copyOfRange(IV_BYTES, raw.size)), Charsets.UTF_8)
    }

    private fun frame(iv: ByteArray, ciphertext: ByteArray): String =
        Base64.getEncoder().encodeToString(iv + ciphertext)
}
