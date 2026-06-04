package com.owlmetry.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [MessageTrimmer] + [CustomAttributeTrimmer] length-capping behavior, mirroring
 * the Swift `MessageTrimmerTests` + `CustomAttributeTrimmerTests` case-for-case.
 * These caps run inside [EventBuilder.build] on the logging hot path: the event
 * message is capped at 2000 chars and each custom-attribute value at 200, with
 * the `_error_stack` reserved-key override raising its cap to 16000 so JVM stack
 * traces survive un-pre-truncated. Pure JVM test — no org.json, no Robolectric.
 */
class TrimmersTest {

    // MARK: - MessageTrimmer

    @Test
    fun emptyMessageReturnsEmpty() {
        assertEquals("", MessageTrimmer.trim(""))
    }

    @Test
    fun shortMessagePassesThrough() {
        val message = "user clicked signup"
        assertEquals(message, MessageTrimmer.trim(message))
    }

    @Test
    fun exactly2000CharsPassesThrough() {
        val message = "a".repeat(2000)
        assertEquals(message, MessageTrimmer.trim(message))
    }

    @Test
    fun over2000CharsIsTrimmed() {
        val message = "a".repeat(5000)
        val result = MessageTrimmer.trim(message)
        assertEquals(2000, result.length)
        assertEquals("a".repeat(2000), result)
    }

    @Test
    fun messageMaxLengthConstantMatchesSwift() {
        assertEquals(2000, MessageTrimmer.MAX_EVENT_MESSAGE_LENGTH)
    }

    // MARK: - CustomAttributeTrimmer

    @Test
    fun nullAttributesReturnNull() {
        assertNull(CustomAttributeTrimmer.trim(null))
    }

    @Test
    fun emptyAttributesReturnEmpty() {
        val result = CustomAttributeTrimmer.trim(emptyMap())
        assertEquals(emptyMap<String, String>(), result)
    }

    @Test
    fun shortValuesPassThrough() {
        val attributes = mapOf("key" to "short value")
        assertEquals(attributes, CustomAttributeTrimmer.trim(attributes))
    }

    @Test
    fun exactly200CharsPassThrough() {
        val value = "a".repeat(200)
        val result = CustomAttributeTrimmer.trim(mapOf("key" to value))
        assertEquals(value, result?.get("key"))
    }

    @Test
    fun over200CharsTrimmed() {
        val value = "a".repeat(250)
        val result = CustomAttributeTrimmer.trim(mapOf("key" to value))
        assertEquals("a".repeat(200), result?.get("key"))
    }

    @Test
    fun multipleKeysIndependent() {
        val short = "ok"
        val long = "b".repeat(300)
        val result = CustomAttributeTrimmer.trim(mapOf("short" to short, "long" to long))
        assertEquals(short, result?.get("short"))
        assertEquals(200, result?.get("long")?.length)
    }

    @Test
    fun errorStackKeyKeepsLongerValueViaOverride() {
        val stack = "f".repeat(5000)
        val result = CustomAttributeTrimmer.trim(mapOf("_error_stack" to stack))
        assertEquals(5000, result?.get("_error_stack")?.length)
    }

    @Test
    fun errorStackKeyTruncatesAtOverrideCap() {
        val stack = "f".repeat(20000)
        val result = CustomAttributeTrimmer.trim(mapOf("_error_stack" to stack))
        assertEquals(16000, result?.get("_error_stack")?.length)
    }

    @Test
    fun nonOverrideErrorKeyStillCapsAt200() {
        // Only `_error_stack` gets the 16000 override; other `_error_*` keys
        // (e.g. `_error_type`) cap at the default 200, matching Swift.
        val typeName = "T".repeat(500)
        val result = CustomAttributeTrimmer.trim(mapOf("_error_type" to typeName))
        assertEquals(200, result?.get("_error_type")?.length)
    }

    @Test
    fun attributeValueMaxLengthConstantMatchesSwift() {
        assertEquals(200, CustomAttributeTrimmer.MAX_CUSTOM_ATTRIBUTE_VALUE_LENGTH)
        assertEquals(16000, CustomAttributeTrimmer.reservedKeyLengthOverrides["_error_stack"])
    }
}
