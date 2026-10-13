package me.weishu.kernelsu.ui.screen.module

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Wysiwyg
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import me.weishu.kernelsu.R
import me.weishu.kernelsu.data.model.Module
import me.weishu.kernelsu.modulebanner.ModuleBannerConfig
import me.weishu.kernelsu.ui.component.material.ExpressiveSwitch
import me.weishu.kernelsu.ui.component.material.folkPressScale
import me.weishu.kernelsu.ui.component.module.AdaptiveModuleButtonRow
import me.weishu.kernelsu.ui.component.module.LocalInsideSplicedGroup
import me.weishu.kernelsu.ui.component.module.ModuleButtonConfig
import me.weishu.kernelsu.ui.component.module.ModuleLabel
import me.weishu.kernelsu.ui.theme.ContinuousCornerShape
import me.weishu.kernelsu.ui.theme.LocalModuleDescriptionMaxLines
import me.weishu.kernelsu.wallpaper.WallpaperConfig

enum class ModuleButtonStyle {
    Full,
    IconOnly;

    companion object {
        fun fromKey(key: String): ModuleButtonStyle =
            if (key == me.weishu.kernelsu.modulebanner.ModuleCardConfig.STYLE_ICON_ONLY) IconOnly else Full
    }
}

@Composable
fun ModuleItemCard(
    module: Module,
    visual: ModuleCardVisual,
    updateUrl: String,
    buttonStyle: ModuleButtonStyle,
    showMoreInfo: Boolean,
    foldSystemModule: Boolean,
    expanded: Boolean,
    onExpandToggle: () -> Unit,
    onUninstallClicked: () -> Unit,
    onCheckChanged: (Boolean) -> Unit,
    onUpdate: () -> Unit,
    onAddShortcut: (ShortcutType) -> Unit,
    onOpenWebUi: () -> Unit,
    onExecuteAction: () -> Unit,
    onLongPress: () -> Unit,
    closeSearch: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val iconOnly = buttonStyle == ModuleButtonStyle.IconOnly

    val cardCorner by animateDpAsState(
        targetValue = when {
            expanded -> 28.dp
            pressed -> 24.dp
            else -> 20.dp
        },
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "moduleCardCorner",
    )
    val cardShape = ContinuousCornerShape(cardCorner)
    val insideSplicedGroup = LocalInsideSplicedGroup.current

    val isWallpaperMode = WallpaperConfig.isActive
    val opacity = if (isWallpaperMode) WallpaperConfig.opacity.coerceAtLeast(0.35f) else 1f
    val cardColor = if (isWallpaperMode) {
        MaterialTheme.colorScheme.surface.copy(alpha = opacity)
    } else {
        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.2f)
    }
    val bannerImageAlpha = when {
        ModuleBannerConfig.customOpacityEnabled -> ModuleBannerConfig.customOpacity
        isWallpaperMode -> (0.35f + (opacity - 0.2f) * 0.5f).coerceIn(0.25f, 0.6f)
        else -> 0.18f
    }

    val canOpenWebUi = module.hasWebUi && !module.remove && module.enabled
    val clickModifier = Modifier
        .fillMaxWidth()
        .animateContentSize(animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec())
        .folkPressScale(interactionSource)
        .combinedClickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                if (foldSystemModule) {
                    onExpandToggle()
                } else if (canOpenWebUi) {
                    onOpenWebUi()
                    closeSearch()
                }
            },
            onLongClick = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onLongPress()
            },
        )

    val content: @Composable () -> Unit = {
        Box(Modifier.fillMaxWidth()) {
            visual.banner?.let { banner ->
                val fadeColor = MaterialTheme.colorScheme.surface
                val gradientAlpha = if (isWallpaperMode) 0.5f else 0.8f
                Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                    AsyncImage(
                        model = banner.model,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        alpha = bannerImageAlpha,
                    )
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        fadeColor.copy(alpha = 0f),
                                        fadeColor.copy(alpha = gradientAlpha),
                                    ),
                                    startY = 0f,
                                    endY = Float.POSITIVE_INFINITY,
                                ),
                            ),
                    )
                }
            }
            ModuleCardContent(
                module = module,
                customInfo = visual.customInfo,
                updateUrl = updateUrl,
                showMoreInfo = showMoreInfo,
                foldSystemModule = foldSystemModule,
                expanded = expanded,
                iconOnly = iconOnly,
                opacity = opacity,
                onCheckChanged = onCheckChanged,
                onUninstallClicked = onUninstallClicked,
                onUpdate = onUpdate,
                onAddShortcut = onAddShortcut,
                onOpenWebUi = onOpenWebUi,
                onExecuteAction = onExecuteAction,
                closeSearch = closeSearch,
            )
        }
    }

    if (insideSplicedGroup) {
        Box(modifier = clickModifier) { content() }
    } else {
        Surface(
            modifier = Modifier.clip(cardShape).then(clickModifier),
            shape = cardShape,
            color = cardColor,
            tonalElevation = 0.dp,
        ) {
            content()
        }
    }
}

@Composable
private fun ModuleCardContent(
    module: Module,
    customInfo: me.weishu.kernelsu.modulebanner.CustomModuleInfo?,
    updateUrl: String,
    showMoreInfo: Boolean,
    foldSystemModule: Boolean,
    expanded: Boolean,
    iconOnly: Boolean,
    opacity: Float,
    onCheckChanged: (Boolean) -> Unit,
    onUninstallClicked: () -> Unit,
    onUpdate: () -> Unit,
    onAddShortcut: (ShortcutType) -> Unit,
    onOpenWebUi: () -> Unit,
    onExecuteAction: () -> Unit,
    closeSearch: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val textDecoration = if (module.remove) TextDecoration.LineThrough else null
    val maxLinesLimit = LocalModuleDescriptionMaxLines.current
    val hasUpdate = updateUrl.isNotEmpty()

    val displayName = customInfo?.name?.takeIf { it.isNotBlank() } ?: module.name
    val displayVersion = customInfo?.version?.takeIf { it.isNotBlank() } ?: module.version
    val displayAuthor = customInfo?.author?.takeIf { it.isNotBlank() } ?: module.author
    val displayDescription = customInfo?.description?.takeIf { it.isNotBlank() } ?: module.description

    val hasAnyLabel = showMoreInfo || module.remove || (hasUpdate && !module.update) || module.update

    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                if (hasAnyLabel) {
                    Row(
                        modifier = Modifier.padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (showMoreInfo) {
                            ModuleLabel(
                                text = module.id,
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                        if (module.remove) {
                            ModuleLabel(
                                text = stringResource(R.string.uninstall),
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        } else if (hasUpdate && !module.update) {
                            ModuleLabel(
                                text = stringResource(R.string.module_update),
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        } else if (module.update) {
                            ModuleLabel(
                                text = "Updated",
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        }
                        if (showMoreInfo && module.hasWebUi && module.enabled && !module.remove) {
                            ModuleLabel(
                                text = "WebUI",
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                        if (showMoreInfo && module.hasActionScript && module.enabled && !module.remove) {
                            ModuleLabel(
                                text = stringResource(R.string.action),
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                        if (module.metamodule && !module.remove) {
                            ModuleLabel(
                                text = "META",
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    textDecoration = textDecoration,
                )
                if (showMoreInfo) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "${stringResource(R.string.module_version)}: $displayVersion",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textDecoration = textDecoration,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "${stringResource(R.string.module_author)}: $displayAuthor",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textDecoration = textDecoration,
                    )
                }
            }
            ExpressiveSwitch(
                enabled = !module.update,
                checked = module.enabled,
                onCheckedChange = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onCheckChanged(it)
                },
            )
        }

        if (showMoreInfo && displayDescription.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = displayDescription,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textDecoration = textDecoration,
                maxLines = maxLinesLimit,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(16.dp))
        } else {
            Spacer(Modifier.height(12.dp))
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = !foldSystemModule || expanded,
            enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()) +
                expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()),
            exit = shrinkVertically(MaterialTheme.motionScheme.fastSpatialSpec()) +
                fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
        ) {
            ModuleActionRow(
                module = module,
                hasUpdate = hasUpdate,
                iconOnly = iconOnly,
                opacity = opacity,
                onUninstallClicked = onUninstallClicked,
                onUpdate = onUpdate,
                onAddShortcut = onAddShortcut,
                onOpenWebUi = onOpenWebUi,
                onExecuteAction = onExecuteAction,
                closeSearch = closeSearch,
            )
        }
    }
}

@Composable
private fun ModuleActionRow(
    module: Module,
    hasUpdate: Boolean,
    iconOnly: Boolean,
    opacity: Float,
    onUninstallClicked: () -> Unit,
    onUpdate: () -> Unit,
    onAddShortcut: (ShortcutType) -> Unit,
    onOpenWebUi: () -> Unit,
    onExecuteAction: () -> Unit,
    closeSearch: () -> Unit,
) {
    val hasUpdateButton = hasUpdate && !module.remove && !module.update
    val buttons = buildList {
        if (module.hasWebUi && module.enabled && !module.remove) {
            add(
                ModuleButtonConfig(
                    icon = Icons.AutoMirrored.Outlined.Wysiwyg,
                    text = stringResource(R.string.open),
                    contentDescription = stringResource(R.string.open),
                    onClick = {
                        onOpenWebUi()
                        closeSearch()
                    },
                    onLongClick = { onAddShortcut(ShortcutType.WebUI) },
                ),
            )
        }
        if (module.hasActionScript && module.enabled && !module.remove) {
            add(
                ModuleButtonConfig(
                    icon = Icons.Outlined.Terminal,
                    text = stringResource(R.string.action),
                    contentDescription = stringResource(R.string.action),
                    onClick = {
                        onExecuteAction()
                        closeSearch()
                    },
                    onLongClick = { onAddShortcut(ShortcutType.Action) },
                ),
            )
        }
        if (hasUpdateButton) {
            add(
                ModuleButtonConfig(
                    icon = Icons.Outlined.Download,
                    text = stringResource(R.string.module_update),
                    contentDescription = stringResource(R.string.module_update),
                    onClick = onUpdate,
                ),
            )
        }
    }
    val trailing = ModuleButtonConfig(
        icon = if (module.remove) Icons.Outlined.Restore else Icons.Outlined.Delete,
        text = if (module.remove) stringResource(R.string.undo) else stringResource(R.string.uninstall),
        contentDescription = if (module.remove) stringResource(R.string.undo) else stringResource(R.string.uninstall),
        onClick = onUninstallClicked,
        colors = when {
            iconOnly -> ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            )
            module.remove -> ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            else -> ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        },
    )
    AdaptiveModuleButtonRow(
        buttons = buttons,
        trailingButton = trailing,
        iconOnly = iconOnly,
        spacing = if (iconOnly) 12 else 8,
        opacity = opacity,
    )
}
