package me.weishu.kernelsu.ui.util

import com.topjohnwu.superuser.ShellUtils

/** Only real module ids reach the shell, so the id can never inject arguments. */
private val ModuleIdPattern = Regex("[A-Za-z0-9._-]+")

/**
 * Reads the `name=` field of an installed module's `module.prop` through a root shell, or null when
 * the id is not a plausible module name or no prop is found. Safe to call from any thread.
 */
fun getModuleDisplayName(moduleId: String): String? {
    if (!ModuleIdPattern.matches(moduleId)) return null
    val output = ShellUtils.fastCmd(
        "cat /data/adb/modules/$moduleId/module.prop 2>/dev/null; " +
            "cat /data/adb/modules_update/$moduleId/module.prop 2>/dev/null"
    )
    return output.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith("name=") }
        ?.substringAfter("name=")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}
