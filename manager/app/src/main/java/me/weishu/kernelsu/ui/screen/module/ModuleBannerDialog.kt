package me.weishu.kernelsu.ui.screen.module

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.model.Module
import me.weishu.kernelsu.modulebanner.ApmBannerSlot
import me.weishu.kernelsu.modulebanner.CustomModuleInfo
import me.weishu.kernelsu.modulebanner.ModuleBannerConfig
import me.weishu.kernelsu.modulebanner.ModuleBannerSlot
import me.weishu.kernelsu.modulebanner.ModuleBannerStorage
import me.weishu.kernelsu.modulebanner.ModuleCustomInfoStorage
import me.weishu.kernelsu.ui.component.material.ExpressiveDialog
import me.weishu.kernelsu.ui.component.material.FolkTextButton
import me.weishu.kernelsu.ui.component.material.SegmentedColumn
import me.weishu.kernelsu.ui.component.material.SegmentedListItem
import me.weishu.kernelsu.ui.component.material.SegmentedTextField

@Composable
fun ModuleBannerDialog(
    module: Module,
    slot: ModuleBannerSlot = ApmBannerSlot,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bannerStorage = remember(slot, context) { ModuleBannerStorage(slot, context) }
    val customStorage = remember(slot, context) { ModuleCustomInfoStorage(slot, context) }
    val savedInfo = remember(module.id) { customStorage.read(module.id) }
    val hasSavedInfo = savedInfo?.hasAnyInfo() == true
    val initialName = remember(module.id) { savedInfo?.name?.takeIf { it.isNotBlank() } ?: module.name }
    val initialVersion = remember(module.id) { savedInfo?.version?.takeIf { it.isNotBlank() } ?: module.version }
    val initialAuthor = remember(module.id) { savedInfo?.author?.takeIf { it.isNotBlank() } ?: module.author }
    val initialDescription = remember(module.id) { savedInfo?.description?.takeIf { it.isNotBlank() } ?: module.description }

    var name by remember(module.id) { mutableStateOf(initialName) }
    var version by remember(module.id) { mutableStateOf(initialVersion) }
    var author by remember(module.id) { mutableStateOf(initialAuthor) }
    var description by remember(module.id) { mutableStateOf(initialDescription) }
    var hasBanner by remember(module.id) { mutableStateOf(bannerStorage.exists(module.id)) }
    val showBannerSection = ModuleBannerConfig.enabled && ModuleBannerConfig.folkEnabled

    val hasChanges = name != initialName || version != initialVersion || author != initialAuthor || description != initialDescription
    val hasNonBlank = name.isNotBlank() || version.isNotBlank() || author.isNotBlank() || description.isNotBlank()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                val bytes = withContext(Dispatchers.IO) { bannerStorage.write(module.id, uri) }
                if (bytes != null) {
                    hasBanner = true
                    ModuleBannerConfig.requestReload()
                }
            }
        }
    }

    ExpressiveDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.module_banner_custom_info)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                SegmentedColumn {
                    item(visible = showBannerSection) {
                        SegmentedListItem(
                            onClick = { picker.launch("image/*") },
                            leadingContent = { Icon(Icons.Default.Image, contentDescription = null) },
                            headlineContent = { Text(stringResource(R.string.module_banner_select)) },
                        )
                    }
                    item(visible = showBannerSection && hasBanner) {
                        SegmentedListItem(
                            onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { bannerStorage.clear(module.id) }
                                    hasBanner = false
                                    ModuleBannerConfig.requestReload()
                                }
                            },
                            leadingContent = {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                            headlineContent = {
                                Text(
                                    text = stringResource(R.string.module_banner_clear),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                        )
                    }
                    item {
                        SegmentedTextField(
                            label = stringResource(R.string.module_banner_name),
                            value = name,
                            onValueChange = { name = it },
                            singleLine = true,
                            placeholder = null,
                        )
                    }
                    item {
                        SegmentedTextField(
                            label = stringResource(R.string.module_banner_version),
                            value = version,
                            onValueChange = { version = it },
                            singleLine = true,
                            placeholder = null,
                        )
                    }
                    item {
                        SegmentedTextField(
                            label = stringResource(R.string.module_banner_author),
                            value = author,
                            onValueChange = { author = it },
                            singleLine = true,
                            placeholder = null,
                        )
                    }
                    item {
                        SegmentedTextField(
                            label = stringResource(R.string.module_banner_description),
                            value = description,
                            onValueChange = { description = it },
                            minLines = 2,
                            placeholder = null,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FolkTextButton(
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { customStorage.clear(module.id) }
                            ModuleBannerConfig.requestReload()
                            onDismiss()
                        }
                    },
                    enabled = hasChanges || hasSavedInfo,
                ) {
                    Text(stringResource(R.string.module_banner_reset))
                }
                FolkTextButton(
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                customStorage.write(
                                    module.id,
                                    CustomModuleInfo(
                                        name = name.takeIf { it.isNotBlank() }.orEmpty(),
                                        version = version.takeIf { it.isNotBlank() }.orEmpty(),
                                        author = author.takeIf { it.isNotBlank() }.orEmpty(),
                                        description = description.takeIf { it.isNotBlank() }.orEmpty(),
                                    ),
                                )
                            }
                            ModuleBannerConfig.requestReload()
                            onDismiss()
                        }
                    },
                    enabled = hasNonBlank && hasChanges,
                ) {
                    Text(stringResource(R.string.module_banner_save))
                }
            }
        },
        dismissButton = {
            FolkTextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}
