package me.weishu.kernelsu.data.backup

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class BackupResultTest {
    @Test
    fun `successful operations return their value`() {
        assertEquals(42, backupResult { 42 }.getOrThrow())
    }

    @Test
    fun `failures preserve the original cause`() {
        val cause = Throwable("backup failed")
        assertSame(cause, backupResult<Unit> { throw cause }.exceptionOrNull())
    }

    @Test
    fun `cancellation propagates rather than becoming a failure`() {
        val cause = CancellationException("cancelled")
        try {
            backupResult<Unit> { throw cause }
            throw AssertionError("Cancellation was swallowed")
        } catch (e: CancellationException) {
            assertSame(cause, e)
        }
    }
}
