package me.weishu.kernelsu.ui.screen.plugin

import android.content.Context
import android.net.Uri
import java.io.File

/** The name of the package copied out of its source before installing it. */
internal const val PLUGIN_PACKAGE_NAME = "plugin-package.zip"

/**
 * Copies a package out of the given source into the app cache so the manager
 * daemon can read it by path, and answers `null` when it cannot be opened.
 */
internal fun stagePluginPackage(context: Context, uri: Uri): String? = runCatching {
    val target = File(context.cacheDir, PLUGIN_PACKAGE_NAME)
    val input = context.contentResolver.openInputStream(uri) ?: return null
    input.use { source ->
        target.outputStream().use { sink -> source.copyTo(sink) }
    }
    target.absolutePath
}.getOrNull()
