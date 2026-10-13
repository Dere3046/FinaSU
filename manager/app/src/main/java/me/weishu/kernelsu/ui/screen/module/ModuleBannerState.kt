package me.weishu.kernelsu.ui.screen.module

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.modulebanner.ApmBannerSlot
import me.weishu.kernelsu.modulebanner.BannerApiClient
import me.weishu.kernelsu.modulebanner.CustomModuleInfo
import me.weishu.kernelsu.modulebanner.ModuleBannerConfig
import me.weishu.kernelsu.modulebanner.ModuleBannerResolver
import me.weishu.kernelsu.modulebanner.ModuleBannerSlot
import me.weishu.kernelsu.modulebanner.ModuleBannerStorage
import me.weishu.kernelsu.modulebanner.ModuleCustomInfoStorage

/** A banner image: in-memory bytes for a local source, or a URL Coil should fetch itself. */
@Immutable
data class ModuleBannerVisual(
    val model: Any,
    val isRemote: Boolean = false,
)

@Immutable
data class ModuleCardVisual(
    val banner: ModuleBannerVisual? = null,
    val customInfo: CustomModuleInfo? = null,
)

/**
 * Resolves everything the module card needs from a slot: the banner image (walking the configured
 * source chain) and the user's metadata overrides. Re-runs whenever a banner setting or the reload
 * key changes, so the list repaints on its own.
 */
@Composable
fun rememberModuleCardVisual(
    moduleId: String,
    slot: ModuleBannerSlot = ApmBannerSlot,
): ModuleCardVisual {
    val enabled = ModuleBannerConfig.enabled
    val folkEnabled = ModuleBannerConfig.folkEnabled
    val apiMode = ModuleBannerConfig.apiModeEnabled
    val apiSource = ModuleBannerConfig.effectiveApiSource
    val reloadKey = ModuleBannerConfig.reloadKey
    val context = LocalContext.current

    val state = produceState(
        initialValue = ModuleCardVisual(),
        moduleId, slot, context, enabled, folkEnabled, apiMode, apiSource, reloadKey,
    ) {
        value = withContext(Dispatchers.IO) {
            ModuleCardVisual(
                banner = resolveBanner(context, slot, moduleId, enabled, folkEnabled, apiMode, apiSource),
                customInfo = ModuleCustomInfoStorage(slot, context).read(moduleId),
            )
        }
    }
    return state.value
}

private suspend fun resolveBanner(
    context: Context,
    slot: ModuleBannerSlot,
    moduleId: String,
    enabled: Boolean,
    folkEnabled: Boolean,
    apiMode: Boolean,
    apiSource: String,
): ModuleBannerVisual? {
    if (!enabled) return null

    if (apiMode && apiSource.isNotEmpty()) {
        BannerApiClient.load(context, moduleId, apiSource)?.let { return ModuleBannerVisual(it) }
    }
    if (folkEnabled) {
        ModuleBannerStorage(slot, context).read(moduleId)?.let { return ModuleBannerVisual(it) }
    }
    if (slot.supportsModulePropBanner) {
        val declared = ModuleBannerResolver.readModulePropBanner(slot, moduleId)
        if (!declared.isNullOrBlank()) {
            if (ModuleBannerResolver.isRemoteSource(declared)) {
                return ModuleBannerVisual(declared, isRemote = true)
            }
            ModuleBannerResolver.readBannerFileAt(slot, moduleId, declared)?.let {
                return ModuleBannerVisual(it)
            }
        }
    }
    ModuleBannerResolver.readModuleFileBanner(slot, moduleId)?.let { return ModuleBannerVisual(it) }
    return null
}
