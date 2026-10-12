package me.weishu.kernelsu.data.backup.device

import android.os.Process
import me.weishu.kernelsu.data.backup.backupResult
import me.weishu.kernelsu.data.backup.source.DeviceInfo
import me.weishu.kernelsu.data.backup.source.PartitionAccess
import me.weishu.kernelsu.ui.util.awaitRootShell
import me.weishu.kernelsu.ui.util.getAvailablePartitions
import me.weishu.kernelsu.ui.util.getSlotSuffix
import java.io.File

/**
 * A [PartitionAccess] backed by the root shell and the KernelSU bundled busybox.
 *
 * Partition images are read and written with `dd` because ksud exposes no command to flash an
 * arbitrary image back to a partition; `blockdev --setrw` is required because block devices are
 * mounted read-only by default. Slot suffixes are honoured so A/B devices target `boot_a` etc.
 */
class RootPartitionAccess : PartitionAccess {

    override suspend fun availablePartitions(): Result<List<String>> =
        backupResult { getAvailablePartitions() }

    override suspend fun readPartition(partition: String, target: File): Result<Unit> = backupResult {
        val device = resolveDevice(partition)
        val result = runRootCommand(
            "busybox dd if=${shellQuote(device)} of=${shellQuote(target.absolutePath)} bs=1048576"
        )
        check(result.isSuccess) {
            "dd failed reading $partition: ${describe(result)}"
        }
        // dd runs as root under a restrictive umask, so the image lands root-owned and mode 600.
        // Hand it to the app user, which is the one that has to hash, upload and later delete it.
        val path = shellQuote(target.absolutePath)
        runRootCommand("busybox chown ${Process.myUid()} $path")
        runRootCommand("busybox chmod 600 $path")
        check(target.length() > 0L) { "empty image read for $partition" }
    }

    override suspend fun writePartition(partition: String, source: File): Result<Unit> = backupResult {
        check(source.length() > 0L) { "empty source image for $partition" }
        val device = resolveDevice(partition)
        val setReadWrite = runRootCommand("busybox blockdev --setrw ${shellQuote(device)}")
        check(setReadWrite.isSuccess) { "blockdev --setrw failed for $partition" }
        val result = runRootCommand(
            "busybox dd if=${shellQuote(source.absolutePath)} of=${shellQuote(device)} " +
                "bs=1048576 conv=notrunc"
        )
        check(result.isSuccess) {
            "dd failed writing $partition: ${describe(result)}"
        }
        runRootCommand("sync")
    }

    override suspend fun deviceInfo(): Result<DeviceInfo> = backupResult {
        DeviceInfo(
            device = getprop("ro.product.device"),
            fingerprint = getprop("ro.build.fingerprint"),
        )
    }

    /** Resolves [partition] to an absolute block device path, preferring the active slot. */
    private suspend fun resolveDevice(partition: String): String {
        val slot = backupResult { getSlotSuffix(false) }.getOrNull()?.trim().orEmpty()
        val names = listOf(partition + slot, partition).distinct()
        for (root in BY_NAME_ROOTS) {
            for (name in names) {
                val path = "$root/$name"
                if (isBlockDevice(path)) return path
            }
        }
        throw IllegalStateException("block device not found for partition: $partition")
    }

    private suspend fun isBlockDevice(path: String): Boolean {
        val out = ArrayList<String>()
        awaitRootShell().newJob()
            .add("[ -b ${shellQuote(path)} ] && echo yes")
            .to(out, null)
            .exec()
        return out.any { it.trim() == "yes" }
    }

    private suspend fun getprop(key: String): String =
        runRootCommand("getprop $key").out.firstOrNull().orEmpty().trim()

    private fun describe(result: com.topjohnwu.superuser.Shell.Result): String =
        result.err.joinToString(" ").ifBlank { "exit ${result.code}" }

    private companion object {
        val BY_NAME_ROOTS = listOf("/dev/block/by-name", "/dev/block/bootdevice/by-name")
    }
}
