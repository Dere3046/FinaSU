package me.weishu.kernelsu.data.backup

import me.weishu.kernelsu.data.repository.SettingsRepository

/**
 * A resolved backup request: which targets to write to and which content to include.
 *
 * This is a value object so the same selection can drive a manual "back up now" run and, later, the
 * pre-flash snapshot without duplicating the enable/disable rules.
 */
data class BackupPlan(
    val localEnabled: Boolean,
    val webDavEnabled: Boolean,
    val modules: Boolean,
    val allowlist: Boolean,
    val partitions: Set<String>,
) {
    /** The [BackupKind]s implied by the selected content; partitions collapse into [BackupKind.BOOT]. */
    val kinds: Set<BackupKind> = buildSet {
        if (modules) add(BackupKind.MODULE)
        if (allowlist) add(BackupKind.ALLOWLIST)
        if (partitions.isNotEmpty()) add(BackupKind.BOOT)
    }

    /** `true` when at least one output (local or WebDAV) is enabled. */
    val hasTarget: Boolean get() = localEnabled || webDavEnabled

    /** `true` when at least one kind of content is selected. */
    val hasContent: Boolean get() = kinds.isNotEmpty()

    companion object {
        fun from(settings: SettingsRepository): BackupPlan = BackupPlan(
            localEnabled = settings.backupLocalEnabled,
            webDavEnabled = settings.backupWebDavEnabled,
            modules = settings.backupModulesEnabled,
            allowlist = settings.backupAllowlistEnabled,
            partitions = settings.backupPartitions,
        )
    }
}
