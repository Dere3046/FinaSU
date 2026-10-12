package me.weishu.kernelsu.data.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** PBKDF2 records shared by every credential kind. Callers validate the secret shape first. */
class CredentialVault(private val random: SecureRandom = SecureRandom()) {
    fun create(secret: CharArray): String {
        require(secret.size in 1..MAX_SECRET_LENGTH)
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val derived = derive(secret, salt, ITERATIONS)
        return try {
            listOf(VERSION.toString(), ITERATIONS.toString(), encode(salt), encode(derived)).joinToString(":")
        } finally {
            derived.fill(0)
        }
    }

    fun isValidRecord(record: String): Boolean = parse(record) != null

    fun verify(secret: CharArray, record: String): Boolean {
        val parsed = parse(record) ?: return false
        if (secret.size !in 1..MAX_SECRET_LENGTH) return false
        val derived = derive(secret, parsed.salt, parsed.iterations)
        return try {
            MessageDigest.isEqual(parsed.hash, derived)
        } finally {
            derived.fill(0)
            parsed.hash.fill(0)
        }
    }

    private fun derive(secret: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(secret, salt, iterations, HASH_BYTES * 8)
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
        const val MAX_SECRET_LENGTH = 1024
        private const val VERSION = 1
        private const val ITERATIONS = 600_000
        private const val MAX_ITERATIONS = 1_000_000
        private const val SALT_BYTES = 32
        private const val HASH_BYTES = 32
    }
}
