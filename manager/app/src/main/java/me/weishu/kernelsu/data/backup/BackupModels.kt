package me.weishu.kernelsu.data.backup

import java.io.File

/**
 * The category of user data a backup entry captures.
 *
 * [value] is the stable wire representation used by backup documents and remote paths; it is
 * intentionally decoupled from the enum constant names.
 */
enum class BackupKind(val value: String) {
    MODULE("module"),
    ALLOWLIST("allowlist"),
    BOOT("boot");

    companion object {
        /** Resolves [value] to a kind, returning `null` for unrecognised input. */
        fun fromValue(value: String?): BackupKind? = entries.firstOrNull { it.value == value }
    }
}

/**
 * How a backup artifact was produced.
 *
 * [value] is the stable wire representation used by backup metadata.
 */
enum class BackupOrigin(val value: String) {
    /** The original installer ZIP captured at install time. */
    ARCHIVED_INSTALLER("archived_installer"),

    /** A ZIP packed from an already-installed module directory. */
    SNAPSHOT("snapshot"),

    /** An exported document that is not a module archive (allow-list, boot image). */
    EXPORTED("exported");

    companion object {
        /** Resolves [value] to an origin, returning `null` for unrecognised input. */
        fun fromValue(value: String?): BackupOrigin? = entries.firstOrNull { it.value == value }
    }
}

/**
 * What triggered a backup run.
 *
 * [value] is the stable wire representation used by backup metadata.
 */
enum class BackupTrigger(val value: String) {
    /** The user asked for a backup explicitly. */
    MANUAL("manual"),

    /** A snapshot taken automatically right before a flash overwrites the target. */
    PRE_FLASH("pre_flash");

    companion object {
        /** Resolves [value] to a trigger, returning `null` for unrecognised input. */
        fun fromValue(value: String?): BackupTrigger? = entries.firstOrNull { it.value == value }
    }
}

/** Machine-readable reason why a backup document was rejected. */
enum class BackupFormatError {
    /** The document is not well-formed JSON or has the wrong shape. */
    MALFORMED,

    /** The `schema` field does not match the expected document type. */
    UNSUPPORTED_SCHEMA,

    /** The `version` field is not supported by this build. */
    UNSUPPORTED_VERSION,

    /** The document carries no usable entries. */
    EMPTY,

    /** A present field has the wrong type or value. */
    INVALID_FIELD,
}

/** Raised when a backup document cannot be parsed or fails validation. */
class BackupFormatException(
    val error: BackupFormatError,
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

/**
 * A single backup known to a source or a storage backend.
 *
 * [moduleId] is `null` for kinds that are not module-scoped (allow-list, boot image). [id] is a
 * stable identifier within its [kind]; [relativePath] locates the payload within a storage.
 */
data class BackupEntry(
    val id: String,
    val kind: BackupKind,
    val moduleId: String? = null,
    val displayName: String,
    val versionName: String = "",
    val versionCode: Int = 0,
    val sizeBytes: Long = 0,
    val sha256: String = "",
    val createdAtEpochMs: Long = 0,
    val source: BackupOrigin = BackupOrigin.EXPORTED,
    val relativePath: String = "",
    /** Origin marker carried in the file name: a boot capture (`stock`/`patched`/`current`) or a module archive (`installer`/`snapshot`); blank when unknown. */
    val marker: String = "",
)

/**
 * Payload produced by a [BackupSource] and handed to a [BackupStorage].
 *
 * Exactly one of [bytes] or [file] carries the primary payload: small documents are held in memory,
 * while large payloads (e.g. partition images) are referenced by file and streamed on write.
 * [metaJson] holds the optional sidecar document described by the backup format contract;
 * [sha256] is the lowercase hex hash of the payload.
 */
data class BackupArtifact(
    val kind: BackupKind,
    val moduleId: String?,
    val fileName: String,
    val metaFileName: String?,
    val sha256: String,
    val metaJson: String?,
    val bytes: ByteArray? = null,
    val file: File? = null,
) {
    init {
        require((bytes != null) != (file != null)) {
            "exactly one of bytes or file must be provided"
        }
    }

    /** The streamable payload backing this artifact. */
    val content: BackupContent
        get() = bytes?.let { BackupContent.Bytes(it) }
            ?: BackupContent.FileRef(requireNotNull(file))
}

/** An entry as reported by a [BackupStorage] listing. */
data class RemoteEntry(
    val relativePath: String,
    val sizeBytes: Long,
    val sha256: String? = null,
    val lastModifiedEpochMs: Long? = null,
)

/**
 * Runs [block], converting any failure into a typed [BackupFormatException] result.
 *
 * A [BackupFormatException] raised by [block] is preserved as-is; any other exception is wrapped as
 * [BackupFormatError.MALFORMED] so callers never observe an untyped failure.
 */
internal fun <T> parseBackupDocument(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: BackupFormatException) {
    Result.failure(e)
} catch (e: Exception) {
    Result.failure(
        BackupFormatException(
            BackupFormatError.MALFORMED,
            e.message ?: "malformed backup document",
            e,
        ),
    )
}
