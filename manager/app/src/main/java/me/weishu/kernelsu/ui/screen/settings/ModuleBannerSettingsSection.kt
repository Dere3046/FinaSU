package me.weishu.kernelsu.ui.screen.settings

import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.R
import me.weishu.kernelsu.modulebanner.BannerApiClient
import me.weishu.kernelsu.modulebanner.ModuleBannerConfig
import me.weishu.kernelsu.modulebanner.ModuleCardConfig
import me.weishu.kernelsu.ui.component.material.SegmentedColumn
import me.weishu.kernelsu.ui.component.material.SegmentedDropdownItem
import me.weishu.kernelsu.ui.component.material.SegmentedListItem
import me.weishu.kernelsu.ui.component.material.SegmentedSliderItem
import me.weishu.kernelsu.ui.component.material.SegmentedSwitchItem
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import me.weishu.kernelsu.ui.navigation3.Route

private val SectionModifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp)

@Composable
fun ModuleCardSettingsSection() {
    SegmentedColumn(
        modifier = SectionModifier,
        title = stringResource(R.string.settings_module_display),
    ) {
        item {
            SegmentedSwitchItem(
                icon = Icons.Filled.Description,
                title = stringResource(R.string.module_card_show_more_info),
                summary = stringResource(R.string.module_card_show_more_info_summary),
                checked = ModuleCardConfig.showMoreInfo,
                onCheckedChange = ModuleCardConfig::updateShowMoreInfo,
            )
        }
        item {
            SegmentedSwitchItem(
                icon = Icons.Filled.Layers,
                title = stringResource(R.string.module_card_fold_system),
                summary = stringResource(R.string.module_card_fold_system_summary),
                checked = ModuleCardConfig.foldSystemModule,
                onCheckedChange = ModuleCardConfig::updateFoldSystemModule,
            )
        }
        item {
            SegmentedSwitchItem(
                icon = Icons.Filled.GridView,
                title = stringResource(R.string.module_card_spliced),
                summary = stringResource(R.string.module_card_spliced_summary),
                checked = ModuleCardConfig.splicedCardGroup,
                onCheckedChange = ModuleCardConfig::updateSplicedCardGroup,
            )
        }
        item {
            val styleItems = listOf(
                stringResource(R.string.module_card_style_full),
                stringResource(R.string.module_card_style_icon),
            )
            SegmentedDropdownItem(
                icon = Icons.Filled.Palette,
                title = stringResource(R.string.module_card_button_style),
                summary = stringResource(R.string.module_card_button_style_summary),
                items = styleItems,
                selectedIndex = if (ModuleCardConfig.buttonStyle == ModuleCardConfig.STYLE_ICON_ONLY) 1 else 0,
                onItemSelected = { index ->
                    ModuleCardConfig.updateButtonStyle(
                        if (index == 1) ModuleCardConfig.STYLE_ICON_ONLY else ModuleCardConfig.STYLE_FULL
                    )
                },
            )
        }
    }
}

@Composable
fun ModuleBannerSettingsSection() {
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    var showSourceDialog by remember { mutableStateOf(false) }
    val folkActive = ModuleBannerConfig.enabled && ModuleBannerConfig.folkEnabled
    val apiActive = folkActive && ModuleBannerConfig.apiModeEnabled

    SegmentedColumn(
        modifier = SectionModifier,
        title = stringResource(R.string.settings_module_banner),
    ) {
        item {
            SegmentedSwitchItem(
                icon = Icons.Filled.Wallpaper,
                title = stringResource(R.string.module_banner_enabled),
                summary = stringResource(R.string.module_banner_enabled_summary),
                checked = ModuleBannerConfig.enabled,
                onCheckedChange = ModuleBannerConfig::updateEnabled,
            )
        }
        item(visible = ModuleBannerConfig.enabled) {
            SegmentedSwitchItem(
                icon = Icons.Outlined.Cloud,
                title = stringResource(R.string.module_banner_folk),
                summary = stringResource(R.string.module_banner_folk_summary),
                checked = ModuleBannerConfig.folkEnabled,
                onCheckedChange = ModuleBannerConfig::updateFolkEnabled,
            )
        }
        item(visible = folkActive) {
            SegmentedSwitchItem(
                icon = Icons.Outlined.Cloud,
                title = stringResource(R.string.module_banner_api_mode),
                summary = stringResource(R.string.module_banner_api_mode_summary),
                checked = ModuleBannerConfig.apiModeEnabled,
                onCheckedChange = ModuleBannerConfig::updateApiModeEnabled,
            )
        }
        item(visible = apiActive) {
            val configured = ModuleBannerConfig.effectiveApiSource.isNotEmpty()
            SegmentedListItem(
                onClick = { showSourceDialog = true },
                leadingContent = { Icon(Icons.Outlined.Cloud, null) },
                headlineContent = { Text(stringResource(R.string.module_banner_api_source)) },
                supportingContent = {
                    Text(
                        if (configured) ModuleBannerConfig.effectiveApiSource
                        else stringResource(R.string.module_banner_api_source_not_configured)
                    )
                },
            )
        }
        item(visible = apiActive) {
            SegmentedListItem(
                onClick = { navigator.push(Route.ApiMarketplace) },
                leadingContent = { Icon(Icons.Outlined.Cloud, null) },
                headlineContent = { Text(stringResource(R.string.module_banner_marketplace)) },
                supportingContent = { Text(stringResource(R.string.module_banner_marketplace_summary)) },
            )
        }
        item(visible = apiActive) {
            SegmentedListItem(
                onClick = {
                    BannerApiClient.clearAll(context)
                    ModuleBannerConfig.requestReload()
                    Toast.makeText(context, R.string.module_banner_cache_cleared, Toast.LENGTH_SHORT).show()
                },
                leadingContent = { Icon(Icons.Outlined.Cloud, null) },
                headlineContent = { Text(stringResource(R.string.module_banner_clear_cache)) },
            )
        }
        item(visible = ModuleBannerConfig.enabled) {
            SegmentedSwitchItem(
                icon = Icons.Filled.Wallpaper,
                title = stringResource(R.string.module_banner_custom_opacity),
                summary = stringResource(R.string.module_banner_custom_opacity_summary),
                checked = ModuleBannerConfig.customOpacityEnabled,
                onCheckedChange = ModuleBannerConfig::updateCustomOpacityEnabled,
            )
        }
        item(visible = ModuleBannerConfig.enabled && ModuleBannerConfig.customOpacityEnabled) {
            SegmentedSliderItem(
                icon = Icons.Filled.Wallpaper,
                title = stringResource(R.string.module_banner_opacity),
                value = ModuleBannerConfig.customOpacity,
                valueRange = 0.1f..1f,
                valueText = { "${(it * 100).toInt()}%" },
                onValueChangeFinished = ModuleBannerConfig::updateCustomOpacity,
            )
        }
    }

    if (showSourceDialog) {
        BannerApiSourceDialog(onDismiss = { showSourceDialog = false })
    }
}

@Composable
private fun BannerApiSourceDialog(onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(ModuleBannerConfig.apiSource) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.module_banner_api_source_dialog_title)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(stringResource(R.string.module_banner_api_source_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    ModuleBannerConfig.updateApiSource(value.trim())
                    onDismiss()
                }
            ) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}
