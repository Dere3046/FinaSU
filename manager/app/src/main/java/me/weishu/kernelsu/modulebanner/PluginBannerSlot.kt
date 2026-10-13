package me.weishu.kernelsu.modulebanner

/**
 * The plugin family's banner slot.
 *
 * Plugins live in their own root and carry no `module.prop`, so a banner comes from the API source,
 * a user-picked image or a candidate file inside the plugin directory. The storage directory names
 * stay distinct from the APM ones so a plugin and a module sharing an id cannot overwrite each
 * other's banner or custom info.
 */
object PluginBannerSlot : ModuleBannerSlot {
    override val key = "plugin"
    override val bannerDirName = "plugin_banners"
    override val customInfoDirName = "plugin_custom_info"
    override val moduleRoots = listOf("/data/adb/plugins")
    override val supportsModulePropBanner = false
}
