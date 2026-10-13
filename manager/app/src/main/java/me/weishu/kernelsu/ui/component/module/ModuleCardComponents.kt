package me.weishu.kernelsu.ui.component.module

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

data class ModuleButtonConfig(
    val icon: ImageVector,
    val text: String,
    val contentDescription: String,
    val onClick: () -> Unit,
    val onLongClick: (() -> Unit)? = null,
    val enabled: Boolean = true,
    val colors: ButtonColors? = null,
)

@Composable
fun ModuleLabel(text: String, containerColor: Color, contentColor: Color) {
    Surface(color = containerColor, contentColor = contentColor, shape = RoundedCornerShape(8.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun AdaptiveModuleButtonRow(
    buttons: List<ModuleButtonConfig>,
    trailingButton: ModuleButtonConfig? = null,
    iconOnly: Boolean,
    spacing: Int = 8,
    opacity: Float = 1f,
) {
    if (iconOnly) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            buttons.forEach { config -> ModuleActionButton(config, true, opacity) }
            Spacer(Modifier.weight(1f))
            trailingButton?.let { config -> ModuleActionButton(config, true, opacity) }
        }
    } else {
        AdaptiveButtonRowLayout(
            buttons = buttons,
            trailingButton = trailingButton,
            spacing = spacing,
            opacity = opacity,
        )
    }
}

@Composable
private fun AdaptiveButtonRowLayout(
    buttons: List<ModuleButtonConfig>,
    trailingButton: ModuleButtonConfig?,
    spacing: Int,
    opacity: Float,
) {
    var iconOnlyCount by remember(buttons.size) { mutableIntStateOf(0) }
    SubcomposeLayout(modifier = Modifier.fillMaxWidth()) { constraints ->
        val spacingPx = spacing.dp.roundToPx()
        val availableWidth = constraints.maxWidth
        fun measure(tag: String, config: ModuleButtonConfig, iconOnly: Boolean): Int =
            subcompose(tag) { ModuleActionButton(config, iconOnly, opacity) }.first().measure(Constraints()).width

        val buttonWidthsWithText = buttons.mapIndexed { i, c -> measure("measure_text_$i", c, false) }
        val buttonWidthsIconOnly = buttons.mapIndexed { i, c -> measure("measure_icon_$i", c, true) }
        val trailingWidthWithText = trailingButton?.let { measure("measure_trailing_text", it, false) } ?: 0
        val trailingWidthIconOnly = trailingButton?.let { measure("measure_trailing_icon", it, true) } ?: 0
        val totalSpacing = if (buttons.isNotEmpty() || trailingButton != null) {
            (buttons.size + (if (trailingButton != null) 1 else 0) - 1).coerceAtLeast(0) * spacingPx
        } else {
            0
        }
        val minSpacerWidth = 8.dp.roundToPx()
        var currentIconOnlyCount = 0
        for (i in 0..buttons.size) {
            val leftButtonsWidth = buttons.indices.sumOf { index -> if (index < i) buttonWidthsIconOnly[index] else buttonWidthsWithText[index] }
            val trailingWidth = if (trailingButton != null) {
                val totalWithTrailingText = leftButtonsWidth + trailingWidthWithText + totalSpacing + minSpacerWidth
                if (totalWithTrailingText <= availableWidth) trailingWidthWithText else trailingWidthIconOnly
            } else {
                0
            }
            val totalWidth = leftButtonsWidth + trailingWidth + totalSpacing + minSpacerWidth
            if (totalWidth <= availableWidth) {
                currentIconOnlyCount = i
                break
            }
            currentIconOnlyCount = i + 1
        }
        iconOnlyCount = currentIconOnlyCount.coerceAtMost(buttons.size)
        val leftButtonsWidth = buttons.indices.sumOf { index -> if (index < iconOnlyCount) buttonWidthsIconOnly[index] else buttonWidthsWithText[index] }
        val trailingIconOnly = if (trailingButton != null) {
            (leftButtonsWidth + trailingWidthWithText + totalSpacing + minSpacerWidth) > availableWidth
        } else {
            false
        }
        val finalPlaceables = buttons.mapIndexed { i, c ->
            subcompose("button_$i") { ModuleActionButton(c, i < iconOnlyCount, opacity) }.first().measure(Constraints())
        }
        val trailingPlaceable = trailingButton?.let { c ->
            subcompose("trailing_button") { ModuleActionButton(c, trailingIconOnly, opacity) }.first().measure(Constraints())
        }
        val layoutHeight = maxOf(finalPlaceables.maxOfOrNull { it.height } ?: 0, trailingPlaceable?.height ?: 0)
        layout(availableWidth, layoutHeight) {
            var x = 0
            finalPlaceables.forEach {
                it.placeRelative(x, 0)
                x += it.width + spacingPx
            }
            trailingPlaceable?.let { it.placeRelative(availableWidth - it.width, 0) }
        }
    }
}

@Composable
fun ModuleActionButton(config: ModuleButtonConfig, iconOnly: Boolean, opacity: Float, modifier: Modifier = Modifier) {
    val colors = config.colors ?: ButtonDefaults.filledTonalButtonColors(
        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = (opacity + 0.3f).coerceAtMost(1f)),
    )
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        modifier = modifier
            .defaultMinSize(ButtonDefaults.MinWidth, ButtonDefaults.MinHeight)
            .clip(ButtonDefaults.filledTonalShape)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                enabled = config.enabled,
                onLongClick = config.onLongClick,
                onClick = config.onClick,
            ),
        shape = ButtonDefaults.filledTonalShape,
        color = if (config.enabled) colors.containerColor else colors.disabledContainerColor,
        contentColor = if (config.enabled) colors.contentColor else colors.disabledContentColor,
    ) {
        ProvideTextStyle(MaterialTheme.typography.labelLarge) {
            Row(
                modifier = Modifier.padding(if (iconOnly) PaddingValues(12.dp) else ButtonDefaults.TextButtonContentPadding),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(config.icon, config.contentDescription, modifier = Modifier.size(20.dp))
                if (!iconOnly) {
                    Spacer(Modifier.width(8.dp))
                    Text(config.text)
                }
            }
        }
    }
}
