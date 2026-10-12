package me.weishu.kernelsu.data.security

/** Validation and the canonical text form both credentials are hashed from. */
object Credentials {
    const val PIN_LENGTH_SHORT = 4
    const val PIN_LENGTH_LONG = 6
    const val PATTERN_NODES_MIN = 4
    const val PATTERN_NODES_MAX = 9
    const val PATTERN_NODE_COUNT = 9


    fun isValidPin(pin: CharArray): Boolean =
        (pin.size == PIN_LENGTH_SHORT || pin.size == PIN_LENGTH_LONG) && pin.all { it in '0'..'9' }

    fun isValidPattern(nodes: List<Int>): Boolean =
        nodes.size in PATTERN_NODES_MIN..PATTERN_NODES_MAX &&
            nodes.all { it in 0 until PATTERN_NODE_COUNT } &&
            nodes.toSet().size == nodes.size

    fun patternSecret(nodes: List<Int>): CharArray? =
        if (isValidPattern(nodes)) nodes.joinToString("-").toCharArray() else null

    /** A pattern stored as "0-3-6-7" is only accepted in that canonical form. */
    private fun isValidPatternText(text: CharArray): Boolean {
        if (text.isEmpty()) return false
        val nodes = mutableListOf<Int>()
        var value = 0
        var digits = 0
        for (ch in text) {
            if (ch == '-') {
                if (digits == 0) return false
                nodes += value
                value = 0
                digits = 0
            } else if (ch in '0'..'9') {
                value = value * 10 + (ch - '0')
                digits++
                if (digits > 1) return false
            } else {
                return false
            }
        }
        if (digits == 0) return false
        nodes += value
        return isValidPattern(nodes)
    }

    fun isValid(kind: CredentialKind, secret: CharArray): Boolean = when (kind) {
        CredentialKind.PIN -> isValidPin(secret)
        CredentialKind.PATTERN -> isValidPatternText(secret)
    }
}
