package me.weishu.kernelsu.wallpaper

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance

/**
 * The dim actually painted over the wallpaper in wallpaper mode (0..1).
 *
 * The theme's contrast guard may raise this above the user's requested dim; the stored preference
 * itself is never rewritten. Null outside wallpaper mode, where callers fall back to
 * [WallpaperConfig.effectiveDim].
 */
val LocalWallpaperDim = compositionLocalOf<Float?> { null }

// Intrinsic perceived luminance of the wallpaper below which light-mode content switches to
// dark-neutral roles. The user's dim is deliberately NOT part of this decision: the scrim only
// darkens the wallpaper, so letting it count would make the slider — the very control the user
// reaches for to *improve* dark-text contrast — the cause of a full-app flip to light text.
// Polarity follows the image itself; dark themes always stay dark.
private const val WALLPAPER_DARK_THRESHOLD = 0.5f

// Minimum contrast ratio between normal text and its background (WCAG AA).
private const val MIN_CONTRAST = 4.5f

/**
 * Adapts [base] for wallpaper mode: the app background becomes transparent and the neutral roles
 * are taken from [neutral] so their text stays legible against the wallpaper.
 *
 * The surface family stays opaque here and acts as the semantic *source color* for the wallpaper
 * materials ([me.weishu.kernelsu.wallpaper.resolveFolkWallpaperTokens]); translucency is applied per
 * component, not to the whole scheme, so there is a single material owner and no double veil.
 *
 * Accent colors and their opaque containers are left untouched. When [active] is false the base
 * scheme is returned unchanged, so non-wallpaper mode is byte-for-byte identical to before.
 */
fun adaptColorScheme(
    base: ColorScheme,
    neutral: ColorScheme,
    active: Boolean,
): ColorScheme {
    if (!active) return base
    return base.copy(
        background = Color.Transparent,
        surface = neutral.surface,
        surfaceDim = neutral.surfaceDim,
        surfaceBright = neutral.surfaceBright,
        surfaceContainer = neutral.surfaceContainer,
        surfaceContainerLow = neutral.surfaceContainerLow,
        surfaceContainerLowest = neutral.surfaceContainerLowest,
        surfaceContainerHigh = neutral.surfaceContainerHigh,
        surfaceContainerHighest = neutral.surfaceContainerHighest,
        surfaceVariant = neutral.surfaceVariant,
        onBackground = neutral.onBackground,
        onSurface = neutral.onSurface,
        onSurfaceVariant = neutral.onSurfaceVariant,
        outline = neutral.outline,
        outlineVariant = neutral.outlineVariant,
        inverseSurface = neutral.inverseSurface,
        inverseOnSurface = neutral.inverseOnSurface,
    )
}

/**
 * Whether the app should switch to dark-neutral roles (light text) because the wallpaper reads as
 * dark.
 *
 * Dark themes always stay dark (never flip to light just because the wallpaper is bright);
 * readability there is left to the user's night dim. In light mode only the wallpaper's *intrinsic*
 * luminance counts, never the user's dim: dragging the day scrim must darken the image without
 * ever inverting the app's content polarity. An unknown luminance (-1) keeps the app theme's
 * polarity.
 */
fun useDarkNeutral(darkTheme: Boolean, luminance: Float): Boolean {
    if (darkTheme) return true
    if (luminance < 0f) return false
    return luminance < WALLPAPER_DARK_THRESHOLD
}

/**
 * Smallest dim (>= [requested]) that keeps neutral text legible over the worst-case (pure white)
 * wallpaper. Returns null when even a fully opaque scrim is insufficient.
 */
fun guardedDim(requested: Float, scheme: ColorScheme): Float? {
    val start = requested.coerceIn(0f, 1f)
    if (passesContrast(start, scheme)) return start
    if (!passesContrast(1f, scheme)) return null
    var low = start
    var high = 1f
    repeat(16) {
        val mid = (low + high) / 2f
        if (passesContrast(mid, scheme)) high = mid else low = mid
    }
    return high
}

private fun passesContrast(dim: Float, scheme: ColorScheme): Boolean {
    val wall = Color.Black.copy(alpha = dim.coerceIn(0f, 1f)).compositeOver(Color.White)
    val backgrounds = listOf(
        wall,
        scheme.surface.compositeOver(wall),
        scheme.surfaceContainer.compositeOver(wall),
    ).map { it.luminance() }
    val foregrounds = listOf(
        scheme.onBackground,
        scheme.onSurface,
        scheme.onSurfaceVariant,
    ).map { it.luminance() }
    return foregrounds.all { foreground ->
        backgrounds.all { background ->
            val hi = maxOf(foreground, background)
            val lo = minOf(foreground, background)
            (hi + 0.05f) / (lo + 0.05f) >= MIN_CONTRAST
        }
    }
}
