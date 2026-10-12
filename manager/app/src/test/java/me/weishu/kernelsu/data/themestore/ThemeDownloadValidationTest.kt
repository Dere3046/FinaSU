package me.weishu.kernelsu.data.themestore

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import me.weishu.kernelsu.wallpaper.FptContainer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ThemeDownloadValidationTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun themeWithIv(iv: ByteArray): ByteArray {
        val zipBytes = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("theme.json"))
                zip.write("{\"fontMode\":\"system\"}".toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }.toByteArray()
        val key = SecretKeySpec(MessageDigest.getInstance("SHA-256")
            .digest("FolkPatchThemeSecretKey2025".toByteArray(Charsets.UTF_8)), "AES")
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(iv))
        return iv + cipher.doFinal(zipBytes)
    }

    @Test fun everyIvFirstByteIsAcceptedAndImportsSuccessfully() {
        for (first in 0..255) {
            val iv = ByteArray(16) { it.toByte() }.also { it[0] = first.toByte() }
            val file = temporary.newFile("theme-$first.fpt")
            file.writeBytes(themeWithIv(iv))
            validateDownloadedFile(file, true, file.length())
            val (json, _) = file.inputStream().use {
                FptContainer.read(it, temporary.newFolder())
            }
            assertEquals("system", json.getString("fontMode"))
        }
    }

    @Test fun allZeroIvIsAcceptedAndImportsSuccessfully() {
        val file = temporary.newFile("zero-iv.fpt")
        file.writeBytes(themeWithIv(ByteArray(16)))
        validateDownloadedFile(file, true, -1L)
        val (json, _) = file.inputStream().use {
            FptContainer.read(it, temporary.newFolder())
        }
        assertEquals("system", json.getString("fontMode"))
    }

    @Test fun missingEmptyAndUndersizedFilesAreRejected() {
        assertThrows(IOException::class.java) {
            validateDownloadedFile(File(temporary.root, "missing.fpt"), true, -1L)
        }
        val file = temporary.newFile("small.fpt")
        for (size in listOf(0, 16, 31)) {
            file.writeBytes(ByteArray(size))
            assertThrows(IOException::class.java) {
                validateDownloadedFile(file, true, -1L)
            }
        }
    }

    @Test fun expectedSizeMismatchIsRejected() {
        val file = temporary.newFile("size.fpt")
        file.writeBytes(themeWithIv(ByteArray(16)))
        assertThrows(IOException::class.java) {
            validateDownloadedFile(file, true, file.length() + 16L)
        }
    }

    @Test fun truncatedAndMisalignedThemesAreRejectedWithoutKnownSize() {
        val bytes = themeWithIv(ByteArray(16))
        val file = temporary.newFile("truncated.fpt")
        for (payload in listOf(bytes.copyOf(bytes.size - 1), bytes + ByteArray(8))) {
            file.writeBytes(payload)
            assertThrows(IOException::class.java) {
                validateDownloadedFile(file, true, -1L)
            }
        }
    }

    @Test fun previewsDoNotRequireAesBlockAlignment() {
        val file = temporary.newFile("Theme.webp")
        file.writeBytes(ByteArray(33))
        validateDownloadedFile(file, false, file.length())
    }
}
