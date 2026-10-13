package me.weishu.kernelsu.ui.screen.bannerapi

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import me.weishu.kernelsu.R
import me.weishu.kernelsu.modulebanner.ApiMarketplaceItem
import me.weishu.kernelsu.ui.component.material.ExpressiveScaffold
import me.weishu.kernelsu.ui.component.material.SearchAppBar
import me.weishu.kernelsu.ui.component.material.SnackBarHost
import me.weishu.kernelsu.ui.component.material.TonalCard
import me.weishu.kernelsu.ui.component.material.TopBarBackButton
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import me.weishu.kernelsu.ui.theme.FolkShape
import me.weishu.kernelsu.ui.theme.FolkType
import me.weishu.kernelsu.ui.viewmodel.ApiMarketplaceViewModel

@Composable
fun ApiMarketplaceScreen() {
    val navigator = LocalNavigator.current
    val viewModel = viewModel<ApiMarketplaceViewModel>()
    val context = LocalContext.current
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    val pullToRefreshState = rememberPullToRefreshState()

    val appliedMessage = stringResource(R.string.api_marketplace_applied)
    val verifyFailedMessage = stringResource(R.string.api_marketplace_verify_failed)

    LaunchedEffect(viewModel.appliedUrl) {
        if (viewModel.appliedUrl != null) {
            snackbarHostState.showSnackbar(appliedMessage)
            viewModel.consumeApplied()
        }
    }
    LaunchedEffect(viewModel.applyError) {
        if (viewModel.applyError != null) {
            snackbarHostState.showSnackbar(verifyFailedMessage)
            viewModel.consumeApplyError()
        }
    }

    var previewItem by remember { mutableStateOf<ApiMarketplaceItem?>(null) }

    ExpressiveScaffold(
        topBar = {
            SearchAppBar(
                title = { Text(stringResource(R.string.api_marketplace_title)) },
                searchText = "",
                onSearchTextChange = {},
                onClearClick = {},
                snackbarHostState = snackbarHostState,
                navigationIcon = { TopBarBackButton(onClick = { navigator.pop() }) },
            )
        },
        snackbarHost = { SnackBarHost(hostState = snackbarHostState) },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            val items = viewModel.items
            when {
                viewModel.isLoading && items.isEmpty() -> {
                    LoadingIndicator(modifier = Modifier.align(Alignment.Center))
                }

                viewModel.errorMessage != null && items.isEmpty() -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = stringResource(R.string.api_marketplace_error),
                            style = FolkType.Summary,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(onClick = viewModel::refresh) {
                            Text(stringResource(R.string.api_marketplace_retry))
                        }
                    }
                }

                items.isEmpty() -> {
                    Text(
                        text = stringResource(R.string.api_marketplace_empty),
                        style = FolkType.Summary,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }

                else -> {
                    PullToRefreshBox(
                        isRefreshing = viewModel.isLoading,
                        onRefresh = viewModel::refresh,
                        state = pullToRefreshState,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(13.dp),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                        ) {
                            items(items, key = { it.url }) { item ->
                                ApiMarketplaceItemCard(
                                    item = item,
                                    isApplying = viewModel.applyingUrl == item.url,
                                    onPreview = { previewItem = item },
                                    onApply = { viewModel.apply(item) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    previewItem?.let { item ->
        AlertDialog(
            onDismissRequest = { previewItem = null },
            title = { Text(item.name.ifBlank { item.url }) },
            text = {
                Column {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data("${item.url}?t=${System.currentTimeMillis()}")
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .clip(FolkShape.Corner16),
                    )
                    val description = item.localizedDescription()
                    if (description.isNotBlank()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(text = description, style = FolkType.Summary)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = item.url,
                        style = FolkType.Caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = viewModel.applyingUrl == null,
                    onClick = {
                        viewModel.apply(item)
                        previewItem = null
                    },
                ) {
                    Text(stringResource(R.string.api_marketplace_apply))
                }
            },
            dismissButton = {
                TextButton(onClick = { previewItem = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun ApiMarketplaceItemCard(
    item: ApiMarketplaceItem,
    isApplying: Boolean,
    onPreview: () -> Unit,
    onApply: () -> Unit,
) {
    val context = LocalContext.current
    TonalCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onPreview,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(item.url).crossfade(true).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(72.dp)
                    .height(72.dp)
                    .clip(FolkShape.Corner12),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name.ifBlank { item.url },
                    style = FolkType.Title,
                )
                val description = item.localizedDescription()
                if (description.isNotBlank()) {
                    Text(
                        text = description,
                        style = FolkType.Summary,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Button(enabled = !isApplying, onClick = onApply) {
                Text(stringResource(R.string.api_marketplace_apply))
            }
        }
    }
}
