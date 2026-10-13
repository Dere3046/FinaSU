package me.weishu.kernelsu.data.pluginstore

/**
 * One installable package of the plugin index.
 *
 * The name is only a label taken from the index; the id a package is installed
 * under is read from its own manifest.
 */
data class StorePlugin(
    val name: String,
    val version: String,
    val description: String,
    val downloadUrl: String,
) {
    val fileName: String
        get() = downloadUrl.substringAfterLast('/')
            .substringBefore('?')
            .takeIf { it.isNotBlank() }
            ?: DEFAULT_PACKAGE_NAME
}

internal const val DEFAULT_PACKAGE_NAME = "plugin-package.zip"
