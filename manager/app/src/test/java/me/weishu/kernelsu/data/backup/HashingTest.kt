package me.weishu.kernelsu.data.backup

import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class HashingTest {

    @Test
    fun `sha1 matches known vectors`() {
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", ByteArray(0).sha1())
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", "abc".toByteArray().sha1())
    }

    @Test
    fun `digests stream matches known vectors`() {
        val digests = ByteArrayInputStream("abc".toByteArray()).digests()
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", digests.sha1)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", digests.sha256)
    }

    @Test
    fun `file digests agree with in-memory digests`() = runBlocking {
        val file = File.createTempFile("digests", ".bin").apply {
            writeBytes("partition-image".toByteArray())
            deleteOnExit()
        }

        val digests = file.digests()

        assertEquals("partition-image".toByteArray().sha1(), digests.sha1)
        assertEquals("partition-image".toByteArray().sha256(), digests.sha256)
    }
}
