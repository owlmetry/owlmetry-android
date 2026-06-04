package com.owlmetry.android

/**
 * Caps an event message at the same length the server enforces. Mirrors Swift's
 * `MessageTrimmer`.
 */
internal object MessageTrimmer {
    const val MAX_EVENT_MESSAGE_LENGTH: Int = 2000

    fun trim(message: String): String =
        if (message.length > MAX_EVENT_MESSAGE_LENGTH) {
            message.substring(0, MAX_EVENT_MESSAGE_LENGTH)
        } else {
            message
        }
}

/**
 * Caps each custom-attribute value before transport. Mirrors Swift's
 * `CustomAttributeTrimmer`, including the per-key override for `_error_stack`
 * (16000 chars) that matches the server's
 * `RESERVED_ATTRIBUTE_VALUE_LENGTH_OVERRIDES` so stack traces aren't
 * pre-truncated to 200 chars.
 */
internal object CustomAttributeTrimmer {
    const val MAX_CUSTOM_ATTRIBUTE_VALUE_LENGTH: Int = 200

    val reservedKeyLengthOverrides: Map<String, Int> = mapOf(
        "_error_stack" to 16000,
    )

    fun trim(customAttributes: Map<String, String>?): Map<String, String>? {
        if (customAttributes == null) return null
        if (customAttributes.isEmpty()) return customAttributes

        val result = LinkedHashMap<String, String>(customAttributes.size)
        for ((key, value) in customAttributes) {
            val cap = reservedKeyLengthOverrides[key] ?: MAX_CUSTOM_ATTRIBUTE_VALUE_LENGTH
            result[key] = if (value.length > cap) value.substring(0, cap) else value
        }
        return result
    }
}
