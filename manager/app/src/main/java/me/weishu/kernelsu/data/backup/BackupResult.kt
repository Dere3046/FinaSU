package me.weishu.kernelsu.data.backup

import kotlinx.coroutines.CancellationException

/** Unlike runCatching, cancellation must not become a best-effort backup failure. */
internal inline fun <T> backupResult(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}
