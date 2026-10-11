package me.weishu.kernelsu.data.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Pure cryptography. Call from a worker dispatcher; no plaintext is persisted. */
class PasswordVault(private val random: SecureRandom = SecureRandom()) {
    fun create(password: CharArray): String {
        require(password.size in MIN_PASSWORD_LENGTH..MAX_PASSWORD_LENGTH)
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val derived = derive(password, salt, ITERATIONS)
        return try {
            listOf(VERSION.toString(), ITERATIONS.toString(), encode(salt), encode(derived)).joinToString(":")
        } finally {
            derived.fill(0)
        }
    }

    fun isValidRecord(record: String): Boolean = parse(record) != null

    fun verify(password: CharArray, record: String): Boolean {
        val parsed = parse(record) ?: return false
        if (password.size !in 1..MAX_PASSWORD_LENGTH) return false
        val derived = derive(password, parsed.salt, parsed.iterations)
        return try {
            MessageDigest.isEqual(parsed.hash, derived)
        } finally {
            derived.fill(0)
            parsed.hash.fill(0)
        }
    }

    private fun derive(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, HASH_BYTES * 8)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun parse(record: String): Record? {
        if (record.length > 256) return null
        val parts = record.split(':')
        if (parts.size != 4 || parts[0] != VERSION.toString()) return null
        val iterations = parts[1].toIntOrNull() ?: return null
        if (iterations !in ITERATIONS..MAX_ITERATIONS) return null
        return try {
            val salt = Base64.getDecoder().decode(parts[2])
            val hash = Base64.getDecoder().decode(parts[3])
            if (salt.size != SALT_BYTES || hash.size != HASH_BYTES) null
            else Record(iterations, salt, hash)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private data class Record(val iterations: Int, val salt: ByteArray, val hash: ByteArray)

    companion object {
        const val MIN_PASSWORD_LENGTH = 8
        const val MAX_PASSWORD_LENGTH = 1024
        private const val VERSION = 1
        private const val ITERATIONS = 600_000
        private const val MAX_ITERATIONS = 1_000_000
        private const val SALT_BYTES = 32
        private const val HASH_BYTES = 32
    }
}
