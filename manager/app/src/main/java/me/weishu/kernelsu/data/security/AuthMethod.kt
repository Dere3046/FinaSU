package me.weishu.kernelsu.data.security

enum class AuthMethod(val key: String) {
    NONE("none"),
    BIOMETRIC("biometric"),
    PIN("pin"),
    PATTERN("pattern");

    val credential: CredentialKind?
        get() = when (this) {
            PIN -> CredentialKind.PIN
            PATTERN -> CredentialKind.PATTERN
            else -> null
        }

    companion object {
        private const val LEGACY_PASSWORD = "password"

        fun fromKey(key: String): AuthMethod? = entries.firstOrNull { it.key == key }

        /** The free-text password no longer exists, so its stored value degrades to NONE. */
        fun fromStoredKey(key: String): AuthMethod? = fromKey(key) ?: if (key == LEGACY_PASSWORD) NONE else null
    }
}
