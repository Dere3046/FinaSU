package me.weishu.kernelsu.ui.screen.wallpaper

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.R
import me.weishu.kernelsu.wallpaper.gallery.WallpaperDevice

/** Bottom sheet holding the gallery's display scheme: device pool + layout style. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WallpaperGalleryDisplaySheet(
    device: WallpaperDevice,
    layoutIndex: Int,
    onDeviceSelected: (WallpaperDevice) -> Unit,
    onLayoutSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    val haptics = LocalHapticFeedback.current
    val devices = WallpaperDevice.entries
    val layoutLabels = listOf(
        stringResource(R.string.wallpaper_gallery_layout_waterfall),
        stringResource(R.string.wallpaper_gallery_layout_stack),
    )

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.wallpaper_gallery_display_options),
                style = MaterialTheme.typography.titleMedium,
            )
            GallerySegmentedControl(
                items = devices.map { stringResource(it.labelRes) },
                selectedIndex = devices.indexOf(device),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                onItemSelection = { index ->
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onDeviceSelected(devices[index])
                },
            )
            Text(
                text = stringResource(R.string.wallpaper_gallery_display_layout),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            GallerySegmentedControl(
                items = layoutLabels,
                selectedIndex = layoutIndex,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                onItemSelection = { index ->
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onLayoutSelected(index)
                },
            )
        }
    }
}

/**
 * A capsule picking one of a few labels; the selected segment is filled with the
 * primary color. Local to the gallery because the app has no shared control for it.
 */
@Composable
private fun GallerySegmentedControl(
    items: List<String>,
    selectedIndex: Int,
    color: Color,
    contentColor: Color,
    onItemSelection: (Int) -> Unit,
) {
    val cornerRadius = 50
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(color, shape = RoundedCornerShape(cornerRadius))
            .border(1.dp, color.copy(alpha = 0.1f), RoundedCornerShape(cornerRadius))
            .clip(RoundedCornerShape(cornerRadius)),
        horizontalArrangement = Arrangement.Center,
    ) {
        items.forEachIndexed { index, item ->
            val isSelected = selectedIndex == index
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(cornerRadius))
                    .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .clickable { onItemSelection(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = item,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else contentColor,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
