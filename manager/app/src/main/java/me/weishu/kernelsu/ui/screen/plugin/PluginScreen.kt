package me.weishu.kernelsu.ui.screen.plugin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.weishu.kernelsu.ui.viewmodel.PluginViewModel

@Composable
fun PluginPager(
    bottomInnerPadding: Dp,
    isCurrentPage: Boolean = true,
) {
    val viewModel = viewModel<PluginViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(isCurrentPage) {
        if (isCurrentPage) viewModel.refresh()
    }

    val actions = remember(viewModel) {
        PluginActions(
            onRefresh = viewModel::refresh,
            onToggleEnabled = viewModel::setEnabled,
            onOpenLog = viewModel::loadLog,
            onDismissLog = viewModel::dismissLog,
        )
    }

    PluginPagerMaterial(
        uiState = uiState,
        actions = actions,
        bottomInnerPadding = bottomInnerPadding,
    )
}
