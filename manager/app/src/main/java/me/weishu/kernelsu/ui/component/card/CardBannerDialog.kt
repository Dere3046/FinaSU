package me.weishu.kernelsu.ui.component.card

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
import androidx.compose.runtime.Immutable
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

/**
 * The text a card shows, independent of the family it comes from.
 *
 * The banner dialog edits these fields, so a module and a plugin only differ in how they build the
 * subject and which [ModuleBannerSlot] they store it under.
 */
@Immutable
data class CardSubject(
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val description: String,
)

/** Picks a banner image and edits the visible text of one card. */
@Composable
fun CardBannerDialog(
    subject: CardSubject,
    slot: ModuleBannerSlot = ApmBannerSlot,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bannerStorage = remember(slot, context) { ModuleBannerStorage(slot, context) }
    val customStorage = remember(slot, context) { ModuleCustomInfoStorage(slot, context) }
    val savedInfo = remember(subject.id) { customStorage.read(subject.id) }
    val hasSavedInfo = savedInfo?.hasAnyInfo() == true
    val initialName = remember(subject.id) { savedInfo?.name?.takeIf { it.isNotBlank() } ?: subject.name }
    val initialVersion = remember(subject.id) { savedInfo?.version?.takeIf { it.isNotBlank() } ?: subject.version }
    val initialAuthor = remember(subject.id) { savedInfo?.author?.takeIf { it.isNotBlank() } ?: subject.author }
    val initialDescription =
        remember(subject.id) { savedInfo?.description?.takeIf { it.isNotBlank() } ?: subject.description }

    var name by remember(subject.id) { mutableStateOf(initialName) }
    var version by remember(subject.id) { mutableStateOf(initialVersion) }
    var author by remember(subject.id) { mutableStateOf(initialAuthor) }
    var description by remember(subject.id) { mutableStateOf(initialDescription) }
    var hasBanner by remember(subject.id) { mutableStateOf(bannerStorage.exists(subject.id)) }
    val showBannerSection = ModuleBannerConfig.enabled && ModuleBannerConfig.folkEnabled

    val hasChanges = name != initialName || version != initialVersion ||
        author != initialAuthor || description != initialDescription
    val hasNonBlank = name.isNotBlank() || version.isNotBlank() || author.isNotBlank() || description.isNotBlank()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                val bytes = withContext(Dispatchers.IO) { bannerStorage.write(subject.id, uri) }
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
                                    withContext(Dispatchers.IO) { bannerStorage.clear(subject.id) }
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
                            withContext(Dispatchers.IO) { customStorage.clear(subject.id) }
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
                                    subject.id,
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
