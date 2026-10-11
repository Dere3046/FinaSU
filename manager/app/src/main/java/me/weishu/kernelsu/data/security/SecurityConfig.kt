package me.weishu.kernelsu.data.security

import java.util.Collections

/** A missing override inherits the default; an explicit NONE disables that target. */
@ConsistentCopyVisibility
data class SecurityConfig private constructor(
    val defaultMethod: AuthMethod,
    val targetOverrides: Map<SecurityTarget, AuthMethod>,
    val allowPasswordFallback: Boolean,
) {
    fun methodFor(target: SecurityTarget): AuthMethod = targetOverrides[target] ?: defaultMethod

    fun withoutPassword(): SecurityConfig = create(
        defaultMethod = defaultMethod.takeUnless { it == AuthMethod.PASSWORD } ?: AuthMethod.NONE,
        targetOverrides = targetOverrides.mapValues { (_, method) ->
            method.takeUnless { it == AuthMethod.PASSWORD } ?: AuthMethod.NONE
        },
        allowPasswordFallback = false,
    )

    fun needsPassword(): Boolean = defaultMethod == AuthMethod.PASSWORD ||
        AuthMethod.PASSWORD in targetOverrides.values || allowPasswordFallback

    companion object {
        fun create(
            defaultMethod: AuthMethod = AuthMethod.NONE,
            targetOverrides: Map<SecurityTarget, AuthMethod> = emptyMap(),
            allowPasswordFallback: Boolean = false,
        ): SecurityConfig = SecurityConfig(
            defaultMethod,
            Collections.unmodifiableMap(HashMap(targetOverrides)),
            allowPasswordFallback,
        )
    }
}
