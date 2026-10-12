package me.weishu.kernelsu.ui.component.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.R
import kotlin.coroutines.resume

sealed interface BiometricResult {
    data object Success : BiometricResult
    data object Cancelled : BiometricResult
    data object CredentialRequested : BiometricResult
    data class Error(val code: Int) : BiometricResult
}

class BiometricAuthenticator(private val activity: FragmentActivity) {
    companion object {
        /**
         * Capability check usable outside an Activity: only missing hardware or missing enrolment
         * count as unsupported, so a transient unavailability never erases a valid policy.
         */
        fun isSupported(context: Context): Boolean {
            val status = BiometricManager.from(context)
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            return status != BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE &&
                status != BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED
        }

        /** Prompt-free enrollment check, so a policy switch needs no extra authentication. */
        fun isAvailable(activity: FragmentActivity): Boolean =
            BiometricManager.from(activity).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
                BiometricManager.BIOMETRIC_SUCCESS
    }

    suspend fun authenticate(allowCredentialFallback: Boolean): BiometricResult = withContext(Dispatchers.Main.immediate) {
        val available = BiometricManager.from(activity).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        if (available != BiometricManager.BIOMETRIC_SUCCESS) return@withContext BiometricResult.Error(available)
        suspendCancellableCoroutine<BiometricResult> { continuation ->
            val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (continuation.isActive) continuation.resume(BiometricResult.Success)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        val result = when (errorCode) {
                            BiometricPrompt.ERROR_NEGATIVE_BUTTON -> if (allowCredentialFallback)
                                BiometricResult.CredentialRequested else BiometricResult.Cancelled
                            BiometricPrompt.ERROR_CANCELED, BiometricPrompt.ERROR_USER_CANCELED -> BiometricResult.Cancelled
                            else -> BiometricResult.Error(errorCode)
                        }
                        if (continuation.isActive) continuation.resume(result)
                    }

                    // A mismatch is not terminal: the system prompt remains open for another attempt.
                    override fun onAuthenticationFailed() = Unit
                })
            continuation.invokeOnCancellation { prompt.cancelAuthentication() }
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(activity.getString(R.string.security_biometric_title))
                .setSubtitle(activity.getString(R.string.security_biometric_summary))
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButtonText(activity.getString(if (allowCredentialFallback)
                    R.string.security_use_credential else android.R.string.cancel))
                .build()
            try {
                prompt.authenticate(info)
            } catch (_: Exception) {
                if (continuation.isActive) continuation.resume(BiometricResult.Error(BiometricPrompt.ERROR_HW_UNAVAILABLE))
            }
        }
    }
}
