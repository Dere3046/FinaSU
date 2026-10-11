package me.weishu.kernelsu.data.model

/** The backend, not a duplicated provider allowlist, decides activation conflicts. */
data class ModuleMutationResult(val success: Boolean, val zygiskConflict: Boolean, val externalConflict: Boolean = false) {
    companion object {
        fun fromOutput(success: Boolean, output: List<String>): ModuleMutationResult =
            ModuleMutationResult(
                success = success,
                zygiskConflict = !success && output.any {
                    it.trimStart().removePrefix("Error: ").startsWith("FOLKZYGISK_CONFLICT:")
                },
                externalConflict = !success && output.any {
                    it.trimStart().removePrefix("Error: ").startsWith("FOLKZYGISK_EXTERNAL_CONFLICT:")
                },
            )
    }
}
