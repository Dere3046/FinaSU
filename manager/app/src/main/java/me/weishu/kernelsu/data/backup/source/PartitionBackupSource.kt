package me.weishu.kernelsu.data.backup.source

import java.io.File
import me.weishu.kernelsu.data.backup.backupResult
import me.weishu.kernelsu.data.backup.BackupArtifact
import me.weishu.kernelsu.data.backup.BackupContent
import me.weishu.kernelsu.data.backup.BackupEntry
import me.weishu.kernelsu.data.backup.BackupKind
import me.weishu.kernelsu.data.backup.BackupOrigin
import me.weishu.kernelsu.data.backup.BackupSource
import me.weishu.kernelsu.data.backup.BackupStorage
import me.weishu.kernelsu.data.backup.BackupTrigger
import me.weishu.kernelsu.data.backup.BackupPaths
import me.weishu.kernelsu.data.backup.BOOT_MARKER_CURRENT
import me.weishu.kernelsu.data.backup.BOOT_MARKER_PATCHED
import me.weishu.kernelsu.data.backup.BOOT_MARKER_STOCK
import me.weishu.kernelsu.data.backup.BootBackupMeta
import me.weishu.kernelsu.data.backup.backupIsoTimestamp
import me.weishu.kernelsu.data.backup.bootBackupName
import me.weishu.kernelsu.data.backup.bootMetaFileName
import me.weishu.kernelsu.data.backup.digests
import me.weishu.kernelsu.data.backup.sanitizeSegment

/** Best-effort identity of the device a partition image was captured from. */
data class DeviceInfo(val device: String, val fingerprint: String) {
    companion object {
        /** Placeholder used when the device identity cannot be read. */
        val UNKNOWN = DeviceInfo(device = "", fingerprint = "")
    }
}

/**
 * Reads and writes raw block partitions on behalf of a [PartitionBackupSource].
 *
 * The real implementation runs privileged shell commands; keeping it behind this interface lets the
 * source logic (naming, hashing, sidecars) be exercised without a rooted device.
 */
interface PartitionAccess {

    /** Partitions present on this device, in a stable order. */
    suspend fun availablePartitions(): Result<List<String>>

    /** Copies the raw contents of [partition] into [target]. */
    suspend fun readPartition(partition: String, target: File): Result<Unit>

    /** Overwrites [partition] with the raw contents of [source]. */
    suspend fun writePartition(partition: String, source: File): Result<Unit>

    /** Reads the device identity written into the boot sidecar. */
    suspend fun deviceInfo(): Result<DeviceInfo>
}

/** Exports available selected partitions as raw images with [BootBackupMeta] sidecars. */
class PartitionBackupSource(
    private val access: PartitionAccess,
    private val partitions: List<String>,
    private val tempDir: File,
    private val trigger: BackupTrigger = BackupTrigger.MANUAL,
    private val knownStockSha1: Set<String> = emptySet(),
    private val clock: () -> Long = System::currentTimeMillis,
) : BackupSource {

    override val kind: BackupKind = BackupKind.BOOT

    override suspend fun list(): Result<List<BackupEntry>> = access.availablePartitions().map { available ->
        available.filter { it in partitions }.map { partition ->
            BackupEntry(
                id = partition,
                kind = kind,
                displayName = partition,
                source = BackupOrigin.EXPORTED,
            )
        }
    }

    override suspend fun export(): Result<List<BackupArtifact>> = backupResult {
        val available = access.availablePartitions().getOrThrow()
        val device = access.deviceInfo().getOrElse { DeviceInfo.UNKNOWN }
        val createdAt = backupIsoTimestamp(clock())
        val created = mutableListOf<File>()
        try {
            available.filter { it in partitions }.map { partition ->
                val target = File(tempDir, "partition-${sanitizeSegment(partition)}.img")
                created += target
                access.readPartition(partition, target).getOrThrow()
                val digests = target.digests()
                val marker = resolveMarker(digests.sha1, trigger, knownStockSha1)
                val fileName = bootBackupName(partition, digests.sha1, marker)
                val meta = BootBackupMeta(
                    partition = partition,
                    sha1 = digests.sha1,
                    sizeBytes = target.length(),
                    device = device.device,
                    fingerprint = device.fingerprint,
                    createdAt = createdAt,
                    trigger = trigger,
                )
                BackupArtifact(
                    kind = kind,
                    moduleId = null,
                    fileName = fileName,
                    metaFileName = bootMetaFileName(fileName),
                    sha256 = digests.sha256,
                    metaJson = meta.toJson(),
                    file = target,
                )
            }
        } catch (e: Throwable) {
            // A failed export never hands its artifacts to the engine, so images already produced
            // would leak; drop them before surfacing the error.
            created.forEach { it.delete() }
            throw e
        }
    }

    override suspend fun restore(entry: BackupEntry, content: BackupContent): Result<Unit> = backupResult {
        val temp = File.createTempFile("restore-", ".img", tempDir)
        try {
            content.open().use { input -> temp.outputStream().use { input.copyTo(it) } }
            access.writePartition(entry.id, temp).getOrThrow()
        } finally {
            temp.delete()
        }
    }

    override suspend fun delete(entry: BackupEntry): Result<Unit> = Result.success(Unit)

    companion object {
        /** Pre-flash captures retain the stock marker; manual captures compare saved references. */
        fun resolveMarker(
            sha1: String,
            trigger: BackupTrigger,
            knownStockSha1: Set<String>,
        ): String = when {
            sha1 in knownStockSha1 -> BOOT_MARKER_STOCK
            trigger == BackupTrigger.PRE_FLASH -> BOOT_MARKER_STOCK
            knownStockSha1.isEmpty() -> BOOT_MARKER_CURRENT
            else -> BOOT_MARKER_PATCHED
        }
    }
}

/**
 * Collects the SHA-1 of every stock boot image already present in [storages].
 *
 * Used to label a fresh capture `stock` when it matches an image saved earlier, so the comparison
 * spans every enabled destination. Best-effort: a backend that cannot be listed, or a name that is
 * not a stock capture, simply contributes nothing.
 */
suspend fun collectKnownStockSha1(storages: Collection<BackupStorage>): Set<String> {
    val sha1s = mutableSetOf<String>()
    for (storage in storages) {
        val entries = storage.list(BackupPaths.BOOT_DIR).getOrNull() ?: continue
        for (entry in entries) {
            val fileName = entry.relativePath.substringAfterLast('/')
            if (BackupPaths.markerOf(fileName) != BOOT_MARKER_STOCK) continue
            BackupPaths.bootSha1Of(fileName)?.let { sha1s += it }
        }
    }
    return sha1s
}
