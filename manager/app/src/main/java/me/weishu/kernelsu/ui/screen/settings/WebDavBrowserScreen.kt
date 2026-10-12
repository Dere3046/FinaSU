package me.weishu.kernelsu.ui.screen.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch
import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.backup.WebDavPaths
import me.weishu.kernelsu.data.backup.WEBDAV_PASSWORD_SECRET_KEY
import me.weishu.kernelsu.data.backup.webdav.OkHttpWebDavTransport
import me.weishu.kernelsu.data.backup.webdav.WebDavBrowser
import me.weishu.kernelsu.data.backup.webdav.WebDavEntry
import me.weishu.kernelsu.data.repository.SettingsRepositoryImpl
import me.weishu.kernelsu.ksuApp
import me.weishu.kernelsu.ui.component.material.ExpressiveDialog
import me.weishu.kernelsu.ui.component.material.ExpressiveScaffold
import me.weishu.kernelsu.ui.component.material.SegmentedColumn
import me.weishu.kernelsu.ui.component.material.SegmentedListItem
import me.weishu.kernelsu.ui.component.material.TopBarBackButton
import me.weishu.kernelsu.ui.component.material.expressiveTopAppBarColors
import me.weishu.kernelsu.ui.component.settings.SettingsContentMaxWidth
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import me.weishu.kernelsu.ui.navigation3.Route
import okhttp3.Credentials

/** How a remote file can be previewed. */
private enum class WebDavPreviewKind { TEXT, IMAGE, BINARY }

/** At most 128 KiB is pulled for a text preview; enough for JSON/scripts without janking the UI. */
private const val PREVIEW_LIMIT = 128L * 1024

private val BrowserGroupModifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)

private val PREVIEW_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/**
 * Browses the configured WebDAV collection.
 *
 * Each directory is a navigation entry, so the system back gesture walks up the tree. Files open a
 * preview: text and JSON are shown inline, images are loaded with the same credentials, and a
 * partition image surfaces its `.meta.json` sidecar when one exists.
 */
@Composable
fun WebDavBrowserScreen(path: String) {
    val navigator = LocalNavigator.current
    val settings = remember { SettingsRepositoryImpl() }
    val password = remember { ksuApp.backupSecrets.get(WEBDAV_PASSWORD_SECRET_KEY).orEmpty() }
    val baseUrl = remember { WebDavPaths.joinBase(settings.webDavUrl, settings.webDavPath) }
    val authorization = remember { Credentials.basic(settings.webDavUsername, password, Charsets.UTF_8) }
    val browser = remember {
        WebDavBrowser(OkHttpWebDavTransport(ksuApp.okhttpClient, settings.webDavUsername, password), baseUrl)
    }
    val scope = rememberCoroutineScope()

    var refresh by remember { mutableIntStateOf(0) }
    var entries by remember(path) { mutableStateOf<List<WebDavEntry>?>(null) }
    var error by remember(path) { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<WebDavEntry?>(null) }
    var pendingDelete by remember { mutableStateOf<WebDavEntry?>(null) }

    LaunchedEffect(path, refresh) {
        entries = null
        browser.list(path).fold(
            onSuccess = { entries = it; error = null },
            onFailure = { entries = emptyList(); error = it.message ?: it.toString() },
        )
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    ExpressiveScaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(folderTitle(path, stringResource(R.string.backup_webdav_browse))) },
                navigationIcon = { TopBarBackButton(onClick = { navigator.pop() }) },
                colors = expressiveTopAppBarColors(),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .padding(innerPadding)
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = SettingsContentMaxWidth)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(8.dp))
            SegmentedColumn(modifier = BrowserGroupModifier) {
                val current = entries
                when {
                    current == null -> item {
                        SegmentedListItem(headlineContent = { Text(stringResource(R.string.backup_loading)) })
                    }

                    error != null -> {
                        item {
                            SegmentedListItem(
                                headlineContent = { Text(stringResource(R.string.backup_webdav_error)) },
                                supportingContent = { Text(error.orEmpty()) },
                            )
                        }
                        item {
                            SegmentedListItem(
                                onClick = { refresh++ },
                                headlineContent = { Text(stringResource(R.string.backup_retry)) },
                            )
                        }
                    }

                    current.isEmpty() -> item {
                        SegmentedListItem(
                            headlineContent = { Text(stringResource(R.string.backup_webdav_empty)) },
                        )
                    }

                    else -> current.forEach { entry ->
                        item(key = entry.relativePath) {
                            SegmentedListItem(
                                onClick = {
                                    if (entry.isCollection) {
                                        navigator.push(Route.WebDavBrowser(entry.relativePath))
                                    } else {
                                        preview = entry
                                    }
                                },
                                headlineContent = { Text(entry.name) },
                                supportingContent = if (entry.isCollection) null else {
                                    { Text(describe(entry)) }
                                },
                                leadingContent = {
                                    Icon(
                                        imageVector = if (entry.isCollection) Icons.Rounded.Folder else Icons.AutoMirrored.Rounded.InsertDriveFile,
                                        contentDescription = null,
                                    )
                                },
                                trailingContent = if (entry.isCollection) null else {
                                    {
                                        TextButton(onClick = { pendingDelete = entry }) {
                                            Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.backup_delete))
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    preview?.let { entry ->
        WebDavPreviewDialog(
            entry = entry,
            browser = browser,
            authorization = authorization,
            onDelete = {
                preview = null
                pendingDelete = entry
            },
            onDismiss = { preview = null },
        )
    }

    pendingDelete?.let { entry ->
        ExpressiveDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.backup_delete)) },
            text = { Text(stringResource(R.string.backup_delete_confirm, entry.name)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch {
                        browser.delete(entry.relativePath).onSuccess { refresh++ }
                    }
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.close)) }
            },
        )
    }
}

@Composable
private fun WebDavPreviewDialog(
    entry: WebDavEntry,
    browser: WebDavBrowser,
    authorization: String,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val kind = remember(entry.relativePath) { previewKindOf(entry.name) }
    var loading by remember(entry.relativePath) { mutableStateOf(true) }
    var text by remember(entry.relativePath) { mutableStateOf<String?>(null) }
    var failure by remember(entry.relativePath) { mutableStateOf<String?>(null) }

    LaunchedEffect(entry.relativePath) {
        loading = true
        when (kind) {
            WebDavPreviewKind.IMAGE -> loading = false
            WebDavPreviewKind.TEXT -> browser.read(entry.relativePath, PREVIEW_LIMIT).fold(
                onSuccess = { text = it.toString(Charsets.UTF_8); failure = null; loading = false },
                onFailure = { failure = it.message ?: it.toString(); loading = false },
            )
            WebDavPreviewKind.BINARY -> {
                // A partition image has no preview, but its sidecar metadata is plain JSON.
                if (entry.name.endsWith(".img")) {
                    browser.read(entry.relativePath.removeSuffix(".img") + ".meta.json")
                        .onSuccess { text = it.toString(Charsets.UTF_8) }
                }
                loading = false
            }
        }
    }

    ExpressiveDialog(
        onDismissRequest = onDismiss,
        title = { Text(entry.name) },
        text = {
            when {
                loading -> CircularProgressIndicator()
                failure != null -> Text(failure.orEmpty())
                kind == WebDavPreviewKind.IMAGE -> AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(browser.resolve(entry.relativePath))
                        .addHeader("Authorization", authorization)
                        .build(),
                    contentDescription = entry.name,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                )
                text != null -> Box(
                    modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                ) {
                    Text(text.orEmpty(), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
                else -> Text(stringResource(R.string.backup_preview_unavailable, describe(entry)))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
        dismissButton = { TextButton(onClick = onDelete) { Text(stringResource(R.string.backup_delete)) } },
    )
}

private fun previewKindOf(name: String): WebDavPreviewKind = when (name.substringAfterLast('.', "").lowercase()) {
    "json", "txt", "xml", "md", "log", "sh", "prop", "cfg", "ini", "yaml", "yml", "toml" -> WebDavPreviewKind.TEXT
    "png", "jpg", "jpeg", "webp", "gif", "bmp" -> WebDavPreviewKind.IMAGE
    else -> WebDavPreviewKind.BINARY
}

private fun folderTitle(path: String, root: String): String =
    path.trim('/').substringAfterLast('/').ifEmpty { root }

private fun describe(entry: WebDavEntry): String {
    val size = formatSize(entry.sizeBytes)
    val modified = entry.lastModifiedEpochMs?.let {
        PREVIEW_DATE.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
    }
    return if (modified != null) "$size · $modified" else size
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1 shl 30 -> "%.1f GiB".format(bytes.toDouble() / (1 shl 30))
    bytes >= 1 shl 20 -> "%.1f MiB".format(bytes.toDouble() / (1 shl 20))
    bytes >= 1 shl 10 -> "%.1f KiB".format(bytes.toDouble() / (1 shl 10))
    else -> "$bytes B"
}
