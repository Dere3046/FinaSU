package me.weishu.kernelsu.ui.screen.wallpaper

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.material.ExpressiveScaffold
import me.weishu.kernelsu.ui.component.material.SnackBarHost
import me.weishu.kernelsu.ui.component.material.TopBarBackButton
import me.weishu.kernelsu.ui.component.material.expressiveTopAppBarColors
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import me.weishu.kernelsu.ui.theme.FolkShape
import me.weishu.kernelsu.ui.viewmodel.WallpaperGalleryViewModel
import me.weishu.kernelsu.wallpaper.gallery.WallpaperDevice
import me.weishu.kernelsu.wallpaper.gallery.WallpaperDownloader
import me.weishu.kernelsu.wallpaper.gallery.WallpaperItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WallpaperGalleryScreen() {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val viewModel = viewModel<WallpaperGalleryViewModel>(factory = WallpaperGalleryViewModel.Factory(app))
    val state by viewModel.state.collectAsState()

    var layoutIndex by rememberSaveable { mutableIntStateOf(0) }
    var preview by remember { mutableStateOf<WallpaperItem?>(null) }
    var showDisplayOptions by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val savedMessage = stringResource(R.string.wallpaper_gallery_saved)
    val failedMessage = stringResource(R.string.wallpaper_gallery_download_failed)

    if (showDisplayOptions) {
        WallpaperGalleryDisplaySheet(
            device = state.device,
            layoutIndex = layoutIndex,
            onDeviceSelected = viewModel::selectDevice,
            onLayoutSelected = { layoutIndex = it },
            onDismiss = { showDisplayOptions = false },
        )
    }

    ExpressiveScaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { TopBarBackButton(onClick = { navigator.pop() }) },
                title = { Text(stringResource(R.string.settings_wallpaper_gallery)) },
                actions = {
                    IconButton(onClick = { showDisplayOptions = true }) {
                        Icon(
                            imageVector = Icons.Outlined.Tune,
                            contentDescription = stringResource(R.string.wallpaper_gallery_display_options),
                        )
                    }
                },
                colors = expressiveTopAppBarColors(),
            )
        },
        snackbarHost = { SnackBarHost(hostState = snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = { preview = null; viewModel.refresh() },
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                when {
                    state.loading || state.refreshing -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        GalleryLoading(stringResource(R.string.wallpaper_gallery_loading))
                    }

                    state.items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        GalleryEmpty(
                            title = state.error ?: stringResource(R.string.wallpaper_gallery_empty),
                            retryLabel = stringResource(R.string.wallpaper_gallery_retry),
                            onRetry = { viewModel.retry() },
                        )
                    }

                    layoutIndex == 0 -> WallpaperWaterfall(
                        items = state.items,
                        contentPadding = PaddingValues(bottom = 24.dp),
                        onTap = { preview = it },
                        onLoadMore = viewModel::loadMore,
                        onImageSize = viewModel::recordImageSize,
                    )

                    else -> WallpaperStack(
                        items = state.items,
                        onTap = { preview = it },
                        onLoadMore = viewModel::loadMore,
                        onImageSize = viewModel::recordImageSize,
                    )
                }
            }
        }
    }

    preview?.let { item ->
        WallpaperPreviewDialog(
            item = item,
            onDismiss = { preview = null },
            onDownload = {
                scope.launch {
                    when (val result = WallpaperDownloader.download(context, item.imageUri, item.fileName)) {
                        is WallpaperDownloader.Result.Success ->
                            snackbarHostState.showSnackbar(savedMessage)
                        is WallpaperDownloader.Result.Failure ->
                            snackbarHostState.showSnackbar("$failedMessage: ${result.message}")
                    }
                }
            },
        )
    }
}

@Composable
private fun GalleryLoading(text: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LoadingIndicator()
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun GalleryEmpty(title: String, retryLabel: String, onRetry: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.padding(horizontal = 24.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onRetry) {
            Text(retryLabel)
        }
    }
}

@Composable
private fun WallpaperPreviewDialog(
    item: WallpaperItem,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
) {
    val imageMaxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.65f
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = FolkShape.Dialog,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column {
                AsyncImage(
                    model = item.imageUri,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = imageMaxHeight),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.wallpaper_gallery_cancel))
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onDownload) {
                        Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.wallpaper_gallery_download))
                    }
                }
            }
        }
    }
}
