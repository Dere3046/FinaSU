package me.weishu.kernelsu.data.backup

import java.io.File
import java.io.InputStream

/**
 * The streamable payload behind a [BackupArtifact].
 *
 * A payload is either held in memory ([Bytes]) or backed by a file ([FileRef]) so that large
 * payloads such as partition images never have to be loaded into the heap. [sizeBytes] is reported
 * without materialising the content.
 */
sealed interface BackupContent {

    /** Size of the payload in bytes. */
    val sizeBytes: Long

    /** Opens a fresh stream over the payload; the caller owns and must close it. */
    fun open(): InputStream

    /** In-memory payload. */
    class Bytes(private val bytes: ByteArray) : BackupContent {
        override val sizeBytes: Long get() = bytes.size.toLong()

        override fun open(): InputStream = bytes.inputStream()

        /** The underlying array, for backends that can hand it to an API directly. */
        fun bytes(): ByteArray = bytes
    }

    /** File-backed payload, streamed without buffering the whole file. */
    class FileRef(private val file: File) : BackupContent {
        override val sizeBytes: Long get() = file.length()

        override fun open(): InputStream = file.inputStream()

        /** The underlying file, for backends that can hand it to an API directly. */
        fun file(): File = file
    }
}

/** Buffers the whole payload into memory. Prefer the streaming [BackupStorage.put] overload. */
fun BackupContent.readBytes(): ByteArray = open().use { it.readBytes() }

/** Streams the payload to completion and returns its lowercase hex SHA-256. */
fun BackupContent.sha256(): String = open().use { it.sha256() }
