package me.weishu.kernelsu.data.backup.webdav

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException
import me.weishu.kernelsu.data.backup.BackupContent
import me.weishu.kernelsu.data.backup.readBytes
import me.weishu.kernelsu.data.backup.sha256
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavBackupStorageTest {

    private val baseUrl = "https://example.com/dav/FinaSU/"
    private val basePath = "/dav/FinaSU"

    private fun storage(transport: TestWebDavTransport) = WebDavBackupStorage(transport, baseUrl)

    @Test
    fun `test succeeds when the base is reachable`() {
        assertTrue(runBlocking { storage(TestWebDavTransport(basePath)).test() }.isSuccess)
    }

    @Test
    fun `put creates parent collections then writes the file`() {
        val transport = TestWebDavTransport(basePath)

        val result = runBlocking { storage(transport).put("modules/example/a.zip", "zip".toByteArray()) }

        assertTrue(result.isSuccess)
        assertEquals("zip", String(transport.files.getValue("/dav/FinaSU/modules/example/a.zip")))
        assertTrue(transport.collections.contains("/dav/FinaSU/modules"))
        assertTrue(transport.collections.contains("/dav/FinaSU/modules/example"))
    }

    @Test
    fun `put records the payload hash in the manifest so list can dedup`() {
        val transport = TestWebDavTransport(basePath)
        val storage = storage(transport)
        val payload = "module payload".toByteArray()

        runBlocking { storage.put("boot/boot-stock-abc.img", payload) }.getOrThrow()
        val entries = runBlocking { storage.list("boot/") }.getOrThrow()

        val entry = entries.single { it.relativePath == "boot/boot-stock-abc.img" }
        assertEquals(payload.sha256(), entry.sha256)
        assertTrue(transport.files.keys.any { it.endsWith("manifest.json") })
    }

    @Test
    fun `list of a missing directory is empty rather than a failure`() {
        val entries = runBlocking { storage(TestWebDavTransport(basePath)).list("boot/") }.getOrThrow()
        assertTrue(entries.isEmpty())
    }

    @Test
    fun `recursive list visits each collection once`() {
        val transport = TestWebDavTransport(basePath)
        val storage = storage(transport)
        runBlocking { storage.put("modules/example/a.zip", "zip".toByteArray()) }.getOrThrow()
        transport.methods.clear()

        val entries = runBlocking { storage.list("") }.getOrThrow()

        assertEquals(1, entries.count { it.relativePath == "modules/example/a.zip" })
        assertEquals(3, transport.methods.count { it.startsWith("PROPFIND") })
        assertEquals(transport.methods.distinct(), transport.methods)
    }

    @Test
    fun `delete removes both the payload and the manifest entry`() {
        val transport = TestWebDavTransport(basePath)
        val storage = storage(transport)
        runBlocking { storage.put("boot/x.img", "data".toByteArray()) }.getOrThrow()

        runBlocking { storage.delete("boot/x.img") }.getOrThrow()
        val entries = runBlocking { storage.list("boot/") }.getOrThrow()

        assertFalse(transport.files.containsKey("/dav/FinaSU/boot/x.img"))
        assertTrue(entries.none { it.relativePath == "boot/x.img" })
    }

    @Test
    fun `get returns the stored bytes`() {
        val transport = TestWebDavTransport(basePath)
        val storage = storage(transport)
        runBlocking { storage.put("allowlist/allowlist-1.json", "{}".toByteArray()) }.getOrThrow()

        assertEquals("{}", String(runBlocking { storage.get("allowlist/allowlist-1.json") }.getOrThrow()))
    }

    @Test
    fun `open streams the payload into a temporary file`() {
        val transport = TestWebDavTransport(basePath)
        val storage = storage(transport)
        val payload = ByteArray(64 * 1024) { (it % 251).toByte() }
        runBlocking { storage.put("boot/big.img", payload) }.getOrThrow()

        val content = runBlocking { storage.open("boot/big.img") }.getOrThrow()

        assertTrue(content is BackupContent.FileRef)
        assertEquals(payload.size.toLong(), content.sizeBytes)
        assertEquals(payload.sha256(), content.sha256())
        assertArrayEquals(payload, content.readBytes())
        (content as BackupContent.FileRef).file().delete()
    }

    @Test
    fun `a failed download deletes the partial temporary file`() {
        var downloadedFile: File? = null
        val transport = object : WebDavTransport by TestWebDavTransport(basePath) {
            override suspend fun getToFile(url: String, target: File): WebDavResponse {
                downloadedFile = target
                target.writeText("partial image")
                throw IOException("connection lost")
            }
        }

        val result = runBlocking { WebDavBackupStorage(transport, baseUrl).open("boot/big.img") }

        assertTrue(result.isFailure)
        assertFalse(requireNotNull(downloadedFile).exists())
    }

    @Test
    fun `cancelled downloads propagate cancellation and delete their temporary file`() {
        var downloadedFile: File? = null
        val transport = object : WebDavTransport by TestWebDavTransport(basePath) {
            override suspend fun getToFile(url: String, target: File): WebDavResponse {
                downloadedFile = target
                throw CancellationException("cancelled")
            }
        }

        try {
            runBlocking { WebDavBackupStorage(transport, baseUrl).open("boot/big.img") }
            throw AssertionError("Cancellation was swallowed")
        } catch (_: CancellationException) {
            assertFalse(requireNotNull(downloadedFile).exists())
        }
    }
}
