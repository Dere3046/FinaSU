package me.weishu.kernelsu.data.security

import java.util.Collections

/** A missing override inherits the default; an explicit NONE disables that target. */
@ConsistentCopyVisibility
data class SecurityConfig private constructor(
    val defaultMethod: AuthMethod,
    val targetOverrides: Map<SecurityTarget, AuthMethod>,
    val allowCredentialFallback: Boolean,
) {
    fun methodFor(target: SecurityTarget): AuthMethod = targetOverrides[target] ?: defaultMethod

    fun methods(): List<AuthMethod> = buildList {
        add(defaultMethod)
        addAll(targetOverrides.values)
    }

    fun usesBiometric(): Boolean =
        defaultMethod == AuthMethod.BIOMETRIC || AuthMethod.BIOMETRIC in targetOverrides.values

    /** Without hardware or an enrolled fingerprint the method cannot be answered, so it degrades. */
    fun withoutBiometric(): SecurityConfig = create(
        defaultMethod = defaultMethod.takeUnless { it == AuthMethod.BIOMETRIC } ?: AuthMethod.NONE,
        targetOverrides = targetOverrides.mapValues { (_, method) ->
            method.takeUnless { it == AuthMethod.BIOMETRIC } ?: AuthMethod.NONE
        },
        allowCredentialFallback = allowCredentialFallback,
    )

    /** Removes only policies backed by this credential, preserving the other credential's policy. */
    fun withoutCredential(kind: CredentialKind, hasRemainingCredential: Boolean): SecurityConfig = create(
        defaultMethod = defaultMethod.takeUnless { it.credential == kind } ?: AuthMethod.NONE,
        targetOverrides = targetOverrides.mapValues { (_, method) ->
            method.takeUnless { it.credential == kind } ?: AuthMethod.NONE
        },
        allowCredentialFallback = allowCredentialFallback && hasRemainingCredential,
    )

    /** Drops every credential-backed method when no credential remains. */
    fun withoutCredentials(): SecurityConfig = create(
        defaultMethod = defaultMethod.takeUnless { it.credential != null } ?: AuthMethod.NONE,
        targetOverrides = targetOverrides.mapValues { (_, method) ->
            method.takeUnless { it.credential != null } ?: AuthMethod.NONE
        },
        allowCredentialFallback = false,
    )

    companion object {
        fun create(
            defaultMethod: AuthMethod = AuthMethod.NONE,
            targetOverrides: Map<SecurityTarget, AuthMethod> = emptyMap(),
            allowCredentialFallback: Boolean = false,
        ): SecurityConfig = SecurityConfig(
            defaultMethod,
            Collections.unmodifiableMap(HashMap(targetOverrides)),
            allowCredentialFallback,
        )
    }
}
