package me.weishu.kernelsu.data.backup.source

import java.io.File
import kotlinx.coroutines.runBlocking
import me.weishu.kernelsu.data.backup.BackupContent
import me.weishu.kernelsu.data.backup.BackupEntry
import me.weishu.kernelsu.data.backup.BackupKind
import me.weishu.kernelsu.data.backup.BackupOrigin
import me.weishu.kernelsu.data.backup.MODULE_MARKER_INSTALLER
import me.weishu.kernelsu.data.backup.MODULE_MARKER_SNAPSHOT
import me.weishu.kernelsu.data.backup.ModuleBackupMeta
import me.weishu.kernelsu.data.backup.moduleArchiveName
import me.weishu.kernelsu.data.backup.moduleMetaFileName
import me.weishu.kernelsu.data.backup.sha256
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleBackupSourceTest {

    private val epochMs = 1_700_000_000_000L

    private val module = InstalledModule(
        id = "example.module",
        name = "Example Module",
        versionName = "1.2",
        versionCode = 7,
        author = "youxi",
        metamodule = false,
    )

    private fun installer(dir: File, name: String = "Example-1.2.zip"): ModuleInstaller =
        ModuleInstaller(File(dir, name).apply { writeText("installer-bytes") }, originalName = name)

    @Test
    fun `export archives the installer and snapshots the tree it overwrites`() = runBlocking {
        val dir = newTempDir()
        val access = FakeModuleAccess(listOf(module), packedBytes = "snapshot-zip".toByteArray())
        val source = ModuleBackupSource(access, dir, listOf(installer(dir))) { epochMs }

        val artifacts = source.export().getOrThrow()

        assertEquals(2, artifacts.size)

        val archived = artifacts.single { it.fileName.contains("-$MODULE_MARKER_INSTALLER-") }
        assertEquals("example.module", archived.moduleId)
        assertEquals(
            moduleArchiveName("example.module", "1.2", 7, epochMs, MODULE_MARKER_INSTALLER),
            archived.fileName,
        )
        assertEquals(moduleMetaFileName(archived.fileName), archived.metaFileName)
        assertEquals("installer-bytes".toByteArray().sha256(), archived.sha256)
        assertEquals("installer-bytes", archived.file!!.readText())
        val archivedMeta = ModuleBackupMeta.fromJson(archived.metaJson!!).getOrThrow()
        assertEquals(BackupOrigin.ARCHIVED_INSTALLER, archivedMeta.source)
        assertEquals("Example-1.2.zip", archivedMeta.originalFileName)
        assertEquals("Example Module", archivedMeta.name)

        val snapshot = artifacts.single { it.fileName.contains("-$MODULE_MARKER_SNAPSHOT-") }
        assertEquals("example.module", snapshot.moduleId)
        assertEquals(access.lastPacked, "example.module")
        assertEquals(
            moduleArchiveName("example.module", "1.2", 7, epochMs, MODULE_MARKER_SNAPSHOT),
            snapshot.fileName,
        )
        assertEquals("snapshot-zip".toByteArray().sha256(), snapshot.sha256)
        assertEquals("snapshot-zip", snapshot.file!!.readText())
        assertEquals(BackupOrigin.SNAPSHOT, ModuleBackupMeta.fromJson(snapshot.metaJson!!).getOrThrow().source)
    }

    @Test
    fun `export without installers archives nothing`() = runBlocking {
        val access = FakeModuleAccess(listOf(module))

        val artifacts = ModuleBackupSource(access, newTempDir()).export().getOrThrow()

        assertTrue(artifacts.isEmpty())
    }

    @Test
    fun `installed modules the flash does not touch are not snapshotted`() = runBlocking {
        val dir = newTempDir()
        val other = module.copy(id = "other.module")
        val access = FakeModuleAccess(listOf(module, other), packedBytes = "snapshot-zip".toByteArray())
        val source = ModuleBackupSource(access, dir, listOf(installer(dir))) { epochMs }

        val artifacts = source.export().getOrThrow()

        assertEquals(2, artifacts.size)
        assertEquals("example.module", access.lastPacked)
        assertTrue(artifacts.none { it.moduleId == "other.module" })
    }

    @Test
    fun `a failed inspection drops the produced snapshots`() = runBlocking {
        val dir = newTempDir()
        val installerDir = newTempDir()
        val access = FakeModuleAccess(listOf(module), inspectFailure = Throwable("bad zip"))
        val source = ModuleBackupSource(access, dir, listOf(installer(installerDir))) { epochMs }

        val result = source.export()

        assertTrue(result.isFailure)
        assertEquals("bad zip", result.exceptionOrNull()!!.message)
        assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".zip") })
        // The caller keeps ownership of the installer it handed over.
        assertTrue(installerDir.listFiles().orEmpty().any { it.name == "Example-1.2.zip" })
    }

    @Test
    fun `list describes installed modules`() = runBlocking {
        val source = ModuleBackupSource(FakeModuleAccess(listOf(module)), newTempDir()) { epochMs }

        val entries = source.list().getOrThrow()

        val entry = entries.single()
        assertEquals("example.module", entry.id)
        assertEquals("Example Module", entry.displayName)
        assertEquals(BackupKind.MODULE, entry.kind)
        assertEquals(7, entry.versionCode)
    }

    @Test
    fun `restore installs the payload`() = runBlocking {
        val access = FakeModuleAccess()
        val source = ModuleBackupSource(access, newTempDir()) { epochMs }
        val entry = BackupEntry(id = "example.module", kind = BackupKind.MODULE, moduleId = "example.module", displayName = "Example")

        val result = source.restore(entry, BackupContent.Bytes("zip-payload".toByteArray()))

        assertTrue(result.isSuccess)
        assertArrayEquals("zip-payload".toByteArray(), access.lastInstalled!!)
    }

    @Test
    fun `a restore failure is reported`() = runBlocking {
        val access = FakeModuleAccess(installFailure = Throwable("install failed"))
        val source = ModuleBackupSource(access, newTempDir()) { epochMs }
        val entry = BackupEntry(id = "example.module", kind = BackupKind.MODULE, moduleId = "example.module", displayName = "Example")

        val result = source.restore(entry, BackupContent.Bytes("zip-payload".toByteArray()))

        assertTrue(result.isFailure)
        assertEquals("install failed", result.exceptionOrNull()!!.message)
    }
}
