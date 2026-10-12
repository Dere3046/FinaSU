package me.weishu.kernelsu.ui.screen.plugin

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.ui.viewmodel.PluginViewModel

/** The name of the package copied out of the picked document before installing it. */
private const val PACKAGE_NAME = "plugin-package.zip"

@Composable
fun PluginPager(
    bottomInnerPadding: Dp,
    isCurrentPage: Boolean = true,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val viewModel = viewModel<PluginViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(isCurrentPage) {
        if (isCurrentPage) viewModel.refresh()
    }

    val actions = PluginActions(
        onRefresh = viewModel::refresh,
        onToggleEnabled = viewModel::setEnabled,
        onOpenLog = viewModel::loadLog,
        onDismissLog = viewModel::dismissLog,
        onInstallZip = { uri ->
            scope.launch {
                val path = withContext(Dispatchers.IO) { stagePackage(context, uri) }
                if (path == null) {
                    viewModel.reportError("cannot read the selected package")
                } else {
                    viewModel.install(path)
                }
            }
        },
        onRequestUninstall = viewModel::requestUninstall,
        onConfirmUninstall = viewModel::confirmUninstall,
        onDismissUninstall = viewModel::dismissUninstall,
        onErrorShown = viewModel::clearError,
    )

    PluginPagerMaterial(
        uiState = uiState,
        actions = actions,
        bottomInnerPadding = bottomInnerPadding,
    )
}

/**
 * Copies the picked document into the app cache so the manager daemon can read
 * it, and answers `null` when the document cannot be opened.
 */
private fun stagePackage(context: Context, uri: Uri): String? = runCatching {
    val target = File(context.cacheDir, PACKAGE_NAME)
    val input = context.contentResolver.openInputStream(uri) ?: return null
    input.use { source ->
        target.outputStream().use { sink -> source.copyTo(sink) }
    }
    target.absolutePath
}.getOrNull()
