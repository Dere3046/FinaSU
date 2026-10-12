package me.weishu.kernelsu.data.backup

/** A single failed storage or source operation during a backup run. */
data class BackupFailure(
    val operation: String,
    val path: String,
    val storage: String,
    val cause: Throwable,
)

/**
 * The outcome of a backup run.
 *
 * [written] and [skipped] list storage-relative paths; [failures] records every operation that did
 * not succeed. A run is [isSuccess] when no operation failed, [isFailure] when nothing was written
 * and at least one operation failed, and [isPartial] when some work succeeded and some failed.
 */
data class BackupRunResult(
    val kind: BackupKind,
    val written: List<String> = emptyList(),
    val skipped: List<String> = emptyList(),
    val failures: List<BackupFailure> = emptyList(),
) {
    /** `true` when every operation succeeded. */
    val isSuccess: Boolean get() = failures.isEmpty()

    /** `true` when some paths were written and at least one operation failed. */
    val isPartial: Boolean get() = failures.isNotEmpty() && written.isNotEmpty()

    /** `true` when nothing was written and at least one operation failed. */
    val isFailure: Boolean get() = failures.isNotEmpty() && written.isEmpty()
}

/**
 * Orchestrates backup and restore across a [BackupSourceRegistry] and a set of [BackupStorage]s.
 *
 * A backup exports the source's artifacts once and then applies the shared duplicate policy per
 * backend: identical content is skipped, changed content at an existing path overwrites, and every
 * failure is recorded so one unhealthy backend never hides a healthy one.
 */
class BackupEngine(
    private val sources: BackupSourceRegistry,
    private val storages: List<BackupStorage>,
) {

    /** Exports [kind] and persists every non-duplicate artifact to every storage. */
    suspend fun backup(kind: BackupKind): BackupRunResult {
        val source = sources.sourceFor(kind)
            ?: return BackupRunResult(
                kind = kind,
                failures = listOf(
                    BackupFailure(
                        operation = "source",
                        path = "",
                        storage = "registry",
                        cause = IllegalStateException("no backup source registered for $kind"),
                    ),
                ),
            )

        val artifacts = source.export().getOrElse { error ->
            return BackupRunResult(
                kind = kind,
                failures = listOf(BackupFailure("export", "", source.toString(), error)),
            )
        }

        val written = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val failures = mutableListOf<BackupFailure>()
        val artifactsByDirectory = artifacts.groupBy { directoryOf(kind, it) }

        try {
            for (storage in storages) {
                val storageName = storage.toString()
                for ((directory, group) in artifactsByDirectory) {
                    val listingResult = storage.list(directory)
                    val listing = listingResult.getOrNull()
                    if (listing == null) {
                        failures += BackupFailure(
                            operation = "list",
                            path = directory,
                            storage = storageName,
                            cause = listingResult.exceptionOrNull()
                                ?: IllegalStateException("listing $directory failed"),
                        )
                        continue
                    }
                    val existingByPath = listing.associate { it.relativePath to it.sha256 }
                    for (artifact in group) {
                        val path = remotePathFor(kind, artifact.moduleId, artifact.fileName)
                        if (DuplicatePolicy.isDuplicate(existingByPath, path, artifact.sha256)) {
                            skipped += path
                            continue
                        }
                        val putResult = storage.put(path, artifact.content)
                        if (putResult.isFailure) {
                            failures += BackupFailure("put", path, storageName, requireNotNull(putResult.exceptionOrNull()))
                            continue
                        }
                        written += path
                        val metaFileName = artifact.metaFileName
                        val metaJson = artifact.metaJson
                        if (metaFileName != null && metaJson != null) {
                            val metaPath = "$directory/$metaFileName"
                            storage.put(metaPath, metaJson.toByteArray(Charsets.UTF_8)).fold(
                                onSuccess = { written += metaPath },
                                onFailure = { failures += BackupFailure("put", metaPath, storageName, it) },
                            )
                        }
                    }
                }
            }
        } finally {
            // The engine owns file-backed artifacts: a source hands its temporary files over for
            // persistence and expects the engine to release them once every backend has read them.
            artifacts.forEach { it.file?.delete() }
        }
        return BackupRunResult(kind = kind, written = written, skipped = skipped, failures = failures)
    }

    /**
     * Restores [entry] from [storage] through the source registered for [kind].
     *
     * The payload is read from the backend the entry was listed from and forwarded to the source, so
     * a caller restoring a remote entry never has to materialise it first.
     */
    suspend fun restore(kind: BackupKind, entry: BackupEntry, storage: BackupStorage): Result<Unit> {
        val source = sources.sourceFor(kind)
            ?: return Result.failure(IllegalStateException("no backup source registered for $kind"))
        val content = storage.open(entry.relativePath).getOrElse { return Result.failure(it) }
        return try {
            if (entry.sha256.isNotBlank() && content.sha256() != entry.sha256) {
                return Result.failure(IllegalStateException("backup checksum mismatch: ${entry.relativePath}"))
            }
            source.restore(entry, content)
        } finally {
            // A streaming backend hands over a temporary file it owns; the engine releases it once
            // the source has consumed the payload.
            if (content is BackupContent.FileRef) content.file().delete()
        }
    }

    private fun directoryOf(kind: BackupKind, artifact: BackupArtifact): String =
        remotePathFor(kind, artifact.moduleId, artifact.fileName).substringBeforeLast('/')
}
