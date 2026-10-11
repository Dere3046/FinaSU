package me.weishu.kernelsu.data.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleMutationResultTest {
    @Test fun onlyBackendFailureMarkerIdentifiesConflict() {
        assertTrue(ModuleMutationResult.fromOutput(false, listOf("  FOLKZYGISK_CONFLICT: rezygisk")).zygiskConflict)
        assertTrue(ModuleMutationResult.fromOutput(false, listOf("Error: FOLKZYGISK_CONFLICT: rezygisk")).zygiskConflict)
        assertFalse(ModuleMutationResult.fromOutput(true, listOf("FOLKZYGISK_CONFLICT: example")).zygiskConflict)
        assertFalse(ModuleMutationResult.fromOutput(false, listOf("unrelated failure")).zygiskConflict)
        assertFalse(ModuleMutationResult.fromOutput(false, listOf("mention FOLKZYGISK_CONFLICT: example")).zygiskConflict)
    }
}
