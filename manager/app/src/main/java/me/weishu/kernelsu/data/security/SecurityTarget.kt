package me.weishu.kernelsu.data.security

import androidx.annotation.StringRes
import me.weishu.kernelsu.R

enum class SecurityTarget(val key: String, @StringRes val titleRes: Int) {
    APP_START("app_start", R.string.security_target_app_start),
    MODULE_INSTALL("module_install", R.string.security_target_module_install),
    MODULE_UNINSTALL("module_uninstall", R.string.security_target_module_uninstall),
    MODULE_DISABLE("module_disable", R.string.security_target_module_disable),
    MODULE_FLASH("module_flash", R.string.security_target_module_flash),
    THEME_IMPORT("theme_import", R.string.security_target_theme_import),
    REBOOT("reboot", R.string.security_target_reboot);

    companion object {
        fun fromKey(key: String): SecurityTarget? = entries.firstOrNull { it.key == key }
    }
}
