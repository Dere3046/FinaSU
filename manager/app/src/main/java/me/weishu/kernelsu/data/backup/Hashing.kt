package me.weishu.kernelsu.data.backup

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The SHA-1 and SHA-256 digests of a payload.
 *
 * Partition sidecars carry a SHA-1 (matching the kernel/ksud convention) while content addressing
 * uses SHA-256; computing both in one pass keeps a single read of a large image.
 */
data class Digests(val sha1: String, val sha256: String)

private const val HASH_BUFFER_SIZE = 8 * 1024

private const val HEX_DIGITS = "0123456789abcdef"

/** Returns the lowercase hex SHA-1 of these bytes. */
fun ByteArray.sha1(): String = MessageDigest.getInstance("SHA-1").digest(this).toHex()

/** Streams this input to completion and returns its lowercase hex SHA-1. */
fun InputStream.sha1(): String = digestOf(this, "SHA-1")

/** Streams this input once and returns both its SHA-1 and SHA-256. */
fun InputStream.digests(): Digests {
    val sha1 = MessageDigest.getInstance("SHA-1")
    val sha256 = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(HASH_BUFFER_SIZE)
    while (true) {
        val read = read(buffer)
        if (read <= 0) break
        sha1.update(buffer, 0, read)
        sha256.update(buffer, 0, read)
    }
    return Digests(sha1 = sha1.digest().toHex(), sha256 = sha256.digest().toHex())
}

/** Streams the file once and returns both its SHA-1 and SHA-256. */
suspend fun File.digests(): Digests = withContext(Dispatchers.IO) {
    inputStream().use { it.digests() }
}

private fun digestOf(input: InputStream, algorithm: String): String {
    val digest = MessageDigest.getInstance(algorithm)
    val buffer = ByteArray(HASH_BUFFER_SIZE)
    while (true) {
        val read = input.read(buffer)
        if (read <= 0) break
        digest.update(buffer, 0, read)
    }
    return digest.digest().toHex()
}

private fun ByteArray.toHex(): String {
    val chars = CharArray(size * 2)
    for (index in indices) {
        val value = this[index].toInt() and 0xff
        chars[index * 2] = HEX_DIGITS[value ushr 4]
        chars[index * 2 + 1] = HEX_DIGITS[value and 0x0f]
    }
    return String(chars)
}
