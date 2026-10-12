package me.weishu.kernelsu.data.backup.source

import kotlinx.coroutines.runBlocking
import me.weishu.kernelsu.data.backup.AllowlistDocument
import me.weishu.kernelsu.data.backup.AllowlistEntryDto
import me.weishu.kernelsu.data.backup.BackupContent
import me.weishu.kernelsu.data.backup.BackupEntry
import me.weishu.kernelsu.data.backup.BackupKind
import me.weishu.kernelsu.data.backup.allowlistFileName
import me.weishu.kernelsu.data.backup.sha256
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AllowlistBackupSourceTest {

    private val epochMs = 1_700_000_000_000L

    private fun document() = AllowlistDocument(
        exportedAt = "2026-01-01T00:00:00Z",
        appVersion = "1",
        kernelUapiVersion = 2,
        entries = listOf(
            AllowlistEntryDto(
                key = "com.example",
                packageName = "com.example",
                uid = 10,
                currentUid = 10,
                allowSu = true,
                flags = 0,
                rootUseDefault = true,
                rootTemplate = null,
                rootUid = 0,
                rootGid = 0,
                groups = emptyList(),
                capabilities = emptyList(),
                context = "",
                namespace = 0,
                nonRootUseDefault = true,
                umountModules = true,
                rules = "",
            ),
        ),
    )

    @Test
    fun `export serialises the document`() = runBlocking {
        val document = document()
        val source = AllowlistBackupSource(FakeAllowlistAccess(document)) { epochMs }

        val artifact = source.export().getOrThrow().single()

        val expected = document.toJson().toByteArray(Charsets.UTF_8)
        assertEquals(allowlistFileName(epochMs), artifact.fileName)
        assertEquals(expected.sha256(), artifact.sha256)
        assertTrue(artifact.bytes!!.contentEquals(expected))
    }

    @Test
    fun `restore parses and applies the document`() = runBlocking {
        val document = document()
        val access = FakeAllowlistAccess(document)
        val source = AllowlistBackupSource(access) { epochMs }
        val entry = BackupEntry(id = "allowlist", kind = BackupKind.ALLOWLIST, displayName = "allowlist")

        val result = source.restore(entry, BackupContent.Bytes(document.toJson().toByteArray()))

        assertTrue(result.isSuccess)
        assertEquals(document, access.lastApplied)
    }

    @Test
    fun `restore rejects a malformed document`() = runBlocking {
        val access = FakeAllowlistAccess(document())
        val source = AllowlistBackupSource(access) { epochMs }
        val entry = BackupEntry(id = "allowlist", kind = BackupKind.ALLOWLIST, displayName = "allowlist")

        val result = source.restore(entry, BackupContent.Bytes("not json".toByteArray()))

        assertTrue(result.isFailure)
        assertNull(access.lastApplied)
    }
}
