package me.weishu.kernelsu.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupPathsTest {

    @Test
    fun `parses a module archive`() {
        val entry = BackupPaths.parse("modules/my.module/my.module-1.0-2-20240101_000000.zip")
        assertEquals(BackupKind.MODULE, entry?.kind)
        assertEquals("my.module", entry?.moduleId)
        assertEquals("my.module", entry?.id)
        assertEquals("modules/my.module/my.module-1.0-2-20240101_000000.zip", entry?.relativePath)
    }

    @Test
    fun `parses an allowlist document`() {
        val entry = BackupPaths.parse("allowlist/allowlist-20240101_000000.json")
        assertEquals(BackupKind.ALLOWLIST, entry?.kind)
        assertEquals("allowlist-20240101_000000.json", entry?.displayName)
    }

    @Test
    fun `parses a boot image and recovers the partition`() {
        val entry = BackupPaths.parse("boot/init_boot-stock-abcdef.img", sizeBytes = 128, sha256 = "dead")
        assertEquals(BackupKind.BOOT, entry?.kind)
        assertEquals("init_boot", entry?.id)
        assertEquals("init_boot", entry?.displayName)
        assertEquals(128L, entry?.sizeBytes)
        assertEquals("dead", entry?.sha256)
    }

    @Test
    fun `skips metadata sidecars`() {
        assertTrue(BackupPaths.isMeta("boot/boot-stock-abc.img.meta.json"))
        assertNull(BackupPaths.parse("boot/boot-stock-abc.img.meta.json"))
        assertNull(BackupPaths.parse("modules/x/x.zip.meta.json"))
    }

    @Test
    fun `rejects unknown and shallow paths`() {
        assertNull(BackupPaths.parse("manifest.json"))
        assertNull(BackupPaths.parse("modules/onlyid"))
        assertNull(BackupPaths.parse("boot/not-a-stock-image.img"))
        assertNull(BackupPaths.parse("random/file.bin"))
    }

    @Test
    fun `partitionOf recognises every capture marker`() {
        assertEquals("vendor_boot", BackupPaths.partitionOf("vendor_boot-stock-1234.img"))
        assertEquals("boot", BackupPaths.partitionOf("boot-patched-1234.img"))
        assertEquals("init_boot", BackupPaths.partitionOf("init_boot-current-1234.img"))
        assertNull(BackupPaths.partitionOf("boot.img"))
        assertNull(BackupPaths.partitionOf("-stock-abcd.img"))
        assertNull(BackupPaths.partitionOf("boot-unknown-1234.img"))
    }

    @Test
    fun `parse recovers the capture marker and hash`() {
        val entry = BackupPaths.parse("boot/boot-patched-deadbeef.img")
        assertEquals("boot", entry?.id)
        assertEquals("patched", entry?.marker)
        assertEquals("deadbeef", BackupPaths.bootSha1Of("boot-patched-deadbeef.img"))
        assertNull(BackupPaths.markerOf("manifest.json"))
    }

    @Test
    fun `metaPathOf replaces the artifact extension`() {
        assertEquals(
            "boot/boot-stock-1234.meta.json",
            BackupPaths.metaPathOf("boot/boot-stock-1234.img"),
        )
        assertEquals(
            "modules/demo/demo-v1-2-20260101_000000.meta.json",
            BackupPaths.metaPathOf("modules/demo/demo-v1-2-20260101_000000.zip"),
        )
        assertNull(BackupPaths.metaPathOf("allowlist/allowlist-20260101_000000.json"))
    }
}
