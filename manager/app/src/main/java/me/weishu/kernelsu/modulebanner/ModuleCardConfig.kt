package me.weishu.kernelsu.modulebanner

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import me.weishu.kernelsu.ksuApp

/**
 * How the module list renders each card.
 *
 * Compose state so an edit in Settings repaints the list without a ViewModel; persistence lives in
 * its own prefs file and every mutator saves itself.
 */
object ModuleCardConfig {
    private const val PREFS_NAME = "module_card"
    private const val KEY_BUTTON_STYLE = "button_style"
    private const val KEY_SHOW_MORE_INFO = "show_more_info"
    private const val KEY_FOLD_SYSTEM = "fold_system"
    private const val KEY_SPLICED = "spliced_cards"

    /** Icon + label action buttons. */
    const val STYLE_FULL = "full"

    /** Icon-only action buttons, the denser look. */
    const val STYLE_ICON_ONLY = "icon_only"

    private const val LEGACY_STYLE_FULL = "kernelsu"
    private const val LEGACY_STYLE_ICON_ONLY = "apatch"

    private val prefs get() = ksuApp.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var buttonStyle by mutableStateOf(STYLE_FULL)
        private set

    /** Show version/author and the description under the card title. */
    var showMoreInfo by mutableStateOf(true)
        private set

    /** Start cards collapsed, revealing description and action buttons on tap. */
    var foldSystemModule by mutableStateOf(true)
        private set

    /** Join adjacent cards into one continuous group instead of separate rounded cards. */
    var splicedCardGroup by mutableStateOf(true)
        private set

    fun load() {
        val p = prefs
        buttonStyle = when (p.getString(KEY_BUTTON_STYLE, STYLE_FULL)) {
            LEGACY_STYLE_ICON_ONLY, STYLE_ICON_ONLY -> STYLE_ICON_ONLY
            else -> STYLE_FULL
        }
        showMoreInfo = p.getBoolean(KEY_SHOW_MORE_INFO, true)
        foldSystemModule = p.getBoolean(KEY_FOLD_SYSTEM, true)
        splicedCardGroup = p.getBoolean(KEY_SPLICED, true)
    }

    private fun save() = prefs.edit {
        putString(KEY_BUTTON_STYLE, buttonStyle)
        putBoolean(KEY_SHOW_MORE_INFO, showMoreInfo)
        putBoolean(KEY_FOLD_SYSTEM, foldSystemModule)
        putBoolean(KEY_SPLICED, splicedCardGroup)
    }

    fun updateButtonStyle(value: String) {
        buttonStyle = value
        save()
    }

    fun updateShowMoreInfo(value: Boolean) {
        showMoreInfo = value
        save()
    }

    fun updateFoldSystemModule(value: Boolean) {
        foldSystemModule = value
        save()
    }

    fun updateSplicedCardGroup(value: Boolean) {
        splicedCardGroup = value
        save()
    }
}
