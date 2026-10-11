package me.weishu.kernelsu.wallpaper

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList

/**
 * Application language, shared by the platform locale APIs and the theme protocol.
 *
 * Android 13+ owns the selection through [LocaleManager]; older releases fall back to the
 * `fpt_app_language` preference applied by [wrap] in `attachBaseContext`. The theme protocol's
 * `appLanguage` field reads and writes the very same state, so a theme import and the in-app
 * picker never disagree.
 */
object ThemeLanguage {
    private const val PREFS = "settings"
    private const val KEY = "fpt_app_language"

    /** Currently selected BCP-47 tags, or an empty string for "follow the system". */
    fun currentTags(context: Context): String =
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()
        } else {
            context.getSharedPreferences(PREFS, 0).getString(KEY, "").orEmpty()
        }

    /**
     * Select application language. An empty [tags] resets to the system locale. On Android 13+
     * the platform recreates the activity; older releases must recreate the caller themselves
     * for [wrap] to take effect.
     */
    fun setTags(context: Context, tags: String) {
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales =
                if (tags.isEmpty()) LocaleList.getEmptyLocaleList()
                else LocaleList.forLanguageTags(tags)
        } else {
            check(context.getSharedPreferences(PREFS, 0).edit().putString(KEY, tags).commit())
        }
    }

    /** Android 12 locale fallback, using the protocol's language tags. */
    fun wrap(context: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return context
        val tags = context.getSharedPreferences(PREFS, 0).getString(KEY, "").orEmpty()
        if (tags.isEmpty()) return context
        return context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocales(LocaleList.forLanguageTags(tags))
        })
    }
}
