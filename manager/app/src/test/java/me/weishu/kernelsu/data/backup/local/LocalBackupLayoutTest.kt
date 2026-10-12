package me.weishu.kernelsu.data.backup.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalBackupLayoutTest {

    @Test
    fun `display name is the last segment`() {
        assertEquals("a.zip", LocalBackupLayout.displayName("modules/example/a.zip"))
        assertEquals("a.zip", LocalBackupLayout.displayName("a.zip"))
    }

    @Test
    fun `media relative dir keeps nested folders`() {
        assertEquals(
            "Download/FinaSU/modules/example/",
            LocalBackupLayout.mediaRelativeDir("modules/example/a.zip"),
        )
    }

    @Test
    fun `media relative dir of a root file is the backup root`() {
        assertEquals("Download/FinaSU/", LocalBackupLayout.mediaRelativeDir("a.zip"))
    }

    @Test
    fun `round trips a nested path`() {
        val relative = "modules/example/a.zip"
        val directory = LocalBackupLayout.mediaRelativeDir(relative)
        assertEquals(relative, LocalBackupLayout.toRelativePath(directory, "a.zip"))
    }

    @Test
    fun `round trips a file stored directly in the root`() {
        assertEquals(
            "a.zip",
            LocalBackupLayout.toRelativePath("Download/FinaSU/", "a.zip"),
        )
        assertEquals(
            "a.zip",
            LocalBackupLayout.toRelativePath("Download/FinaSU", "a.zip"),
        )
    }

    @Test
    fun `rejects paths outside the backup root`() {
        assertNull(LocalBackupLayout.toRelativePath("Download/Other/", "a.zip"))
        assertNull(LocalBackupLayout.toRelativePath("Download/FinaSUExtra/", "a.zip"))
        assertNull(LocalBackupLayout.toRelativePath("Pictures/", "a.zip"))
    }
}
