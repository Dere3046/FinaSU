package me.weishu.kernelsu.data.backup.device

import org.junit.Assert.assertEquals
import org.junit.Test

class RootCommandsTest {

    @Test
    fun `wraps a plain value in single quotes`() {
        assertEquals("'/data/adb/modules/foo'", shellQuote("/data/adb/modules/foo"))
    }

    @Test
    fun `escapes an embedded single quote`() {
        assertEquals("'a'\"'\"'b'", shellQuote("a'b"))
    }

    @Test
    fun `escapes every embedded single quote`() {
        assertEquals("'a'\"'\"'b'\"'\"'c'", shellQuote("a'b'c"))
    }
}
