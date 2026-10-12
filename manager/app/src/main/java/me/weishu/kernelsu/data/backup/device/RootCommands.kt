package me.weishu.kernelsu.data.backup.device

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.ui.util.awaitRootShell

/**
 * Wraps [value] in single quotes for a shell command line.
 *
 * The embedded `'` is closed, a literal `"'"` is spliced in and the quote is reopened, which is the
 * only safe way to nest a single quote inside a single-quoted shell token.
 */
internal fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

/**
 * Runs [command] on a freshly awaited root shell and returns its result.
 *
 * Standard output and errors are collected explicitly: the app's [me.weishu.kernelsu.ui.util.RootShell]
 * only fills [Shell.Result.getOut] when a callback or sink is attached, so a bare `exec()` would
 * report empty output for otherwise successful commands.
 *
 * This throws when no root shell is available, so privileged backup work can never silently run on a
 * non-root fallback shell.
 */
internal suspend fun runRootCommand(command: String): Shell.Result = withContext(Dispatchers.IO) {
    val stdout = ArrayList<String>()
    val stderr = ArrayList<String>()
    awaitRootShell().newJob().add(command).to(stdout, stderr).exec()
}
