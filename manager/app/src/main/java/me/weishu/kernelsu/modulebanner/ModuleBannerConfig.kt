package me.weishu.kernelsu.modulebanner

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import me.weishu.kernelsu.ksuApp

/**
 * Banner appearance and behaviour state.
 *
 * Fields are Compose state so an edit in Settings repaints the module list without a ViewModel;
 * persistence lives in a dedicated prefs file, and every mutator saves itself.
 */
object ModuleBannerConfig {
    private const val PREFS_NAME = "module_banner"
    private const val KEY_ENABLED = "banner_enabled"
    private const val KEY_FOLK_ENABLED = "folk_banner_enabled"
    private const val KEY_CUSTOM_OPACITY_ENABLED = "banner_custom_opacity_enabled"
    private const val KEY_CUSTOM_OPACITY = "banner_custom_opacity"
    private const val KEY_API_MODE_ENABLED = "banner_api_mode_enabled"
    private const val KEY_API_SOURCE = "banner_api_source"
    private const val KEY_LEGACY_API_SOURCE = "folk_banner_api_source"

    private val prefs get() = ksuApp.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Master switch for every module banner source. */
    var enabled by mutableStateOf(true)
        private set

    /** Whether the user-selected per-module image participates. */
    var folkEnabled by mutableStateOf(true)
        private set

    var customOpacityEnabled by mutableStateOf(true)
        private set

    var customOpacity by mutableFloatStateOf(1f)
        private set

    var apiModeEnabled by mutableStateOf(false)
        private set

    var apiSource by mutableStateOf("")
        private set

    /** Bumped to force banner consumers to re-read (cache cleared, image edited). */
    var reloadKey by mutableIntStateOf(0)
        private set

    val effectiveApiSource: String get() = apiSource.trim()

    fun load() {
        val p = prefs
        enabled = p.getBoolean(KEY_ENABLED, true)
        folkEnabled = p.getBoolean(KEY_FOLK_ENABLED, true)
        customOpacityEnabled = p.getBoolean(KEY_CUSTOM_OPACITY_ENABLED, true)
        customOpacity = p.getFloat(KEY_CUSTOM_OPACITY, 1f)
        apiModeEnabled = p.getBoolean(KEY_API_MODE_ENABLED, false)
        apiSource = p.getString(KEY_API_SOURCE, null)
            ?: p.getString(KEY_LEGACY_API_SOURCE, "").orEmpty()
    }

    fun save() = prefs.edit {
        putBoolean(KEY_ENABLED, enabled)
        putBoolean(KEY_FOLK_ENABLED, folkEnabled)
        putBoolean(KEY_CUSTOM_OPACITY_ENABLED, customOpacityEnabled)
        putFloat(KEY_CUSTOM_OPACITY, customOpacity)
        putBoolean(KEY_API_MODE_ENABLED, apiModeEnabled)
        putString(KEY_API_SOURCE, apiSource)
    }

    fun updateEnabled(value: Boolean) {
        enabled = value
        save()
    }

    fun updateFolkEnabled(value: Boolean) {
        folkEnabled = value
        save()
    }

    fun updateCustomOpacityEnabled(value: Boolean) {
        customOpacityEnabled = value
        save()
    }

    fun updateCustomOpacity(value: Float) {
        customOpacity = value
        save()
    }

    fun updateApiModeEnabled(value: Boolean) {
        apiModeEnabled = value
        save()
    }

    fun updateApiSource(value: String) {
        apiSource = value
        save()
    }

    fun requestReload() {
        reloadKey++
    }
}
