package me.weishu.kernelsu.wallpaper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the content-polarity rule for the global wallpaper.
 *
 * Regression guard: the decision once took `luminance * (1 - dim)`, which let the day-dim slider
 * flip the whole app to light text as soon as the scrim crossed the threshold. [useDarkNeutral] no
 * longer accepts a dim at all, so the slider can only darken the image, never invert polarity.
 */
class WallpaperPolarityTest {
    @Test fun brightWallpaperKeepsTheLightScheme() {
        assertFalse(useDarkNeutral(darkTheme = false, luminance = 1f))
        assertFalse(useDarkNeutral(darkTheme = false, luminance = 0.61f))
        assertFalse(useDarkNeutral(darkTheme = false, luminance = 0.5f))
    }

    @Test fun darkWallpaperSwitchesToDarkNeutral() {
        assertTrue(useDarkNeutral(darkTheme = false, luminance = 0.49f))
        assertTrue(useDarkNeutral(darkTheme = false, luminance = 0f))
    }

    @Test fun darkThemesAlwaysStayDark() {
        assertTrue(useDarkNeutral(darkTheme = true, luminance = 1f))
        assertTrue(useDarkNeutral(darkTheme = true, luminance = 0.8f))
    }

    @Test fun unknownLuminanceKeepsTheAppPolarity() {
        assertFalse(useDarkNeutral(darkTheme = false, luminance = -1f))
        assertTrue(useDarkNeutral(darkTheme = true, luminance = -1f))
    }
}
