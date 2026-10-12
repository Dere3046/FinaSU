package me.weishu.kernelsu.data.backup.source

import kotlinx.coroutines.runBlocking
import me.weishu.kernelsu.data.backup.BackupContent
import me.weishu.kernelsu.data.backup.BackupEntry
import me.weishu.kernelsu.data.backup.BackupKind
import me.weishu.kernelsu.data.backup.BackupTrigger
import me.weishu.kernelsu.data.backup.BOOT_MARKER_CURRENT
import me.weishu.kernelsu.data.backup.BOOT_MARKER_PATCHED
import me.weishu.kernelsu.data.backup.BOOT_MARKER_STOCK
import me.weishu.kernelsu.data.backup.BootBackupMeta
import me.weishu.kernelsu.data.backup.bootBackupName
import me.weishu.kernelsu.data.backup.bootMetaFileName
import me.weishu.kernelsu.data.backup.sha1
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PartitionBackupSourceTest {

    private val epochMs = 1_700_000_000_000L

    @Test
    fun `export captures only requested and available partitions`() = runBlocking {
        val access = FakePartitionAccess(partitions = listOf("boot", "vendor_boot")).apply {
            partitionBytes["boot"] = "boot-image".toByteArray()
            partitionBytes["vendor_boot"] = "vendor-image".toByteArray()
        }
        val source = PartitionBackupSource(
            access = access,
            partitions = listOf("boot", "init_boot", "vendor_boot"),
            tempDir = newTempDir(),
            clock = { epochMs },
        )

        val artifacts = source.export().getOrThrow()

        assertEquals(2, artifacts.size)
        val boot = artifacts.first { BootBackupMeta.fromJson(it.metaJson!!).getOrThrow().partition == "boot" }
        assertEquals(bootBackupName("boot", "boot-image".toByteArray().sha1(), BOOT_MARKER_CURRENT), boot.fileName)
        assertEquals(bootMetaFileName(boot.fileName), boot.metaFileName)
        assertEquals("boot-image", boot.file!!.readText())
        val meta = BootBackupMeta.fromJson(boot.metaJson!!).getOrThrow()
        assertEquals("device", meta.device)
        assertEquals("fingerprint", meta.fingerprint)
        assertEquals("boot-image".toByteArray().size.toLong(), meta.sizeBytes)
    }

    @Test
    fun `export ignores partitions outside the configured set`() = runBlocking {
        val access = FakePartitionAccess(partitions = listOf("boot", "dtbo")).apply {
            partitionBytes["boot"] = "boot-image".toByteArray()
            partitionBytes["dtbo"] = "dtbo-image".toByteArray()
        }
        val source = PartitionBackupSource(access, listOf("boot"), newTempDir()) { epochMs }

        val artifacts = source.export().getOrThrow()

        assertEquals(1, artifacts.size)
        assertEquals("boot", BootBackupMeta.fromJson(artifacts.single().metaJson!!).getOrThrow().partition)
    }

    @Test
    fun `list reports the configured partitions that exist`() = runBlocking {
        val access = FakePartitionAccess(partitions = listOf("boot", "vendor_boot"))
        val source = PartitionBackupSource(access, listOf("boot", "init_boot", "vendor_boot"), newTempDir()) { epochMs }

        val entries = source.list().getOrThrow()

        assertEquals(listOf("boot", "vendor_boot"), entries.map { it.id })
        assertTrue(entries.all { it.kind == BackupKind.BOOT })
    }

    @Test
    fun `restore writes the payload back to the partition`() = runBlocking {
        val access = FakePartitionAccess()
        val source = PartitionBackupSource(access, listOf("boot"), newTempDir()) { epochMs }
        val entry = BackupEntry(id = "boot", kind = BackupKind.BOOT, displayName = "boot")

        val result = source.restore(entry, BackupContent.Bytes("restored-image".toByteArray()))

        assertTrue(result.isSuccess)
        assertEquals("boot", access.lastWritten!!.first)
        assertArrayEquals("restored-image".toByteArray(), access.lastWritten!!.second)
    }

    @Test
    fun `a read failure aborts the export`() = runBlocking {
        val access = FakePartitionAccess().apply { readFailure = Throwable("dd failed") }
        val source = PartitionBackupSource(access, listOf("boot"), newTempDir()) { epochMs }

        val result = source.export()

        assertTrue(result.isFailure)
        assertEquals("dd failed", result.exceptionOrNull()!!.message)
    }

    @Test
    fun `a pre-flash capture is labelled stock`() = runBlocking {
        val access = FakePartitionAccess().apply { partitionBytes["boot"] = "boot-image".toByteArray() }
        val source = PartitionBackupSource(
            access = access,
            partitions = listOf("boot"),
            tempDir = newTempDir(),
            trigger = BackupTrigger.PRE_FLASH,
            clock = { epochMs },
        )

        val artifact = source.export().getOrThrow().single()

        assertEquals(bootBackupName("boot", "boot-image".toByteArray().sha1(), BOOT_MARKER_STOCK), artifact.fileName)
        assertEquals(BackupTrigger.PRE_FLASH, BootBackupMeta.fromJson(artifact.metaJson!!).getOrThrow().trigger)
    }

    @Test
    fun `a manual capture matching a known stock image is labelled stock`() = runBlocking {
        val sha1 = "boot-image".toByteArray().sha1()
        val access = FakePartitionAccess().apply { partitionBytes["boot"] = "boot-image".toByteArray() }
        val source = PartitionBackupSource(
            access = access,
            partitions = listOf("boot"),
            tempDir = newTempDir(),
            knownStockSha1 = setOf(sha1),
            clock = { epochMs },
        )

        val artifact = source.export().getOrThrow().single()

        assertEquals(bootBackupName("boot", sha1, BOOT_MARKER_STOCK), artifact.fileName)
        assertEquals(BackupTrigger.MANUAL, BootBackupMeta.fromJson(artifact.metaJson!!).getOrThrow().trigger)
    }

    @Test
    fun `a manual capture differing from a known stock image is labelled patched`() = runBlocking {
        val access = FakePartitionAccess().apply { partitionBytes["boot"] = "boot-image".toByteArray() }
        val source = PartitionBackupSource(
            access = access,
            partitions = listOf("boot"),
            tempDir = newTempDir(),
            knownStockSha1 = setOf("deadbeef"),
            clock = { epochMs },
        )

        val artifact = source.export().getOrThrow().single()

        assertEquals(
            bootBackupName("boot", "boot-image".toByteArray().sha1(), BOOT_MARKER_PATCHED),
            artifact.fileName,
        )
    }
}
