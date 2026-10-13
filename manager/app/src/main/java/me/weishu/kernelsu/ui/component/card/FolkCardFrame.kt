package me.weishu.kernelsu.ui.component.card

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import me.weishu.kernelsu.modulebanner.ModuleBannerConfig
import me.weishu.kernelsu.ui.component.material.folkPressScale
import me.weishu.kernelsu.ui.component.module.LocalInsideSplicedGroup
import me.weishu.kernelsu.ui.screen.module.ModuleBannerVisual
import me.weishu.kernelsu.ui.theme.ContinuousCornerShape
import me.weishu.kernelsu.wallpaper.WallpaperConfig

/**
 * The shell every card of the module design shares: the animated corner, press scaling, the
 * wallpaper tint and the banner drawn behind the content.
 *
 * Module and plugin cards only differ in what they put inside, so both wrap their content in this
 * frame and inherit the same look and interactions.
 */
@Composable
fun FolkCardFrame(
    banner: ModuleBannerVisual?,
    expanded: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    val cardCorner by animateDpAsState(
        targetValue = when {
            expanded -> 28.dp
            pressed -> 24.dp
            else -> 20.dp
        },
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "cardCorner",
    )
    val cardShape = ContinuousCornerShape(cardCorner)
    val insideSplicedGroup = LocalInsideSplicedGroup.current

    val isWallpaperMode = WallpaperConfig.isActive
    val opacity = cardSurfaceOpacity()
    val cardColor = if (isWallpaperMode) {
        MaterialTheme.colorScheme.surface.copy(alpha = opacity)
    } else {
        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.2f)
    }
    val bannerImageAlpha = bannerImageAlpha(isWallpaperMode, opacity)

    val clickModifier = Modifier
        .fillMaxWidth()
        .animateContentSize(animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec())
        .folkPressScale(interactionSource)
        .combinedClickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            },
            onLongClick = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onLongPress()
            },
        )

    val framed: @Composable () -> Unit = {
        Box(Modifier.fillMaxWidth()) {
            banner?.let { image ->
                val fadeColor = MaterialTheme.colorScheme.surface
                val gradientAlpha = if (isWallpaperMode) 0.5f else 0.8f
                Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                    AsyncImage(
                        model = image.model,
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
            content()
        }
    }

    if (insideSplicedGroup) {
        // The group already draws the surface; a second one would double the corner and the tint.
        Box(modifier = modifier.then(clickModifier)) { framed() }
    } else {
        Surface(
            modifier = modifier.clip(cardShape).then(clickModifier),
            shape = cardShape,
            color = cardColor,
            tonalElevation = 0.dp,
        ) {
            framed()
        }
    }
}

/** Tint of a card, raised over a wallpaper so the content stays readable. */
@Composable
fun cardSurfaceOpacity(): Float {
    if (!WallpaperConfig.isActive) return 1f
    return WallpaperConfig.opacity.coerceAtLeast(0.35f)
}

/** How strongly the banner shows through the card. */
@Composable
private fun bannerImageAlpha(isWallpaperMode: Boolean, opacity: Float): Float = when {
    ModuleBannerConfig.customOpacityEnabled -> ModuleBannerConfig.customOpacity
    isWallpaperMode -> (0.35f + (opacity - 0.2f) * 0.5f).coerceIn(0.25f, 0.6f)
    else -> 0.18f
}
