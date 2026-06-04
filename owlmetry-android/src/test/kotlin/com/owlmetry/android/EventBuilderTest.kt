package com.owlmetry.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class EventBuilderTest {

    private val device = DeviceInfo(
        platform = OwlPlatform.ANDROID,
        osVersion = "14",
        appVersion = "1.0.0",
        buildNumber = "7",
        deviceModel = "Google Pixel 8",
        locale = "en_US",
        preferredLanguage = "en-US",
        supportedLanguages = listOf("en-US"),
    )

    private fun build(
        attrs: Map<String, String>? = mapOf("custom" to "value"),
        message: String = "hi",
    ) = EventBuilder.build(
        message = message,
        level = OwlLogLevel.INFO,
        screenName = "Home",
        customAttributes = attrs,
        userId = "owl_anon_1",
        sessionId = "sess",
        deviceInfo = device,
        isDev = false,
        networkStatus = "wifi",
        file = "/path/to/Main.kt",
        function = "onCreate",
        line = 42,
        timestamp = Date(0),
    )

    @Test
    fun sourceModuleIsFileNameFunctionLine() {
        assertEquals("Main.kt:onCreate:42", build().sourceModule)
    }

    @Test
    fun mergesReservedSystemMetaKeys() {
        val attrs = build().customAttributes!!
        assertEquals("Main.kt", attrs["_file"])
        assertEquals("onCreate", attrs["_function"])
        assertEquals("42", attrs["_line"])
        assertEquals("wifi", attrs["_connection"])
        assertEquals("value", attrs["custom"])
        assertEquals(EventBuilder.systemMetaKeys, setOf("_file", "_function", "_line", "_connection"))
    }

    @Test
    fun stampsSdkIdentityFromVersion() {
        val event = build()
        assertEquals(OwlmetryVersion.NAME, event.sdkName)
        assertEquals(OwlmetryVersion.CURRENT, event.sdkVersion)
    }

    @Test
    fun generatesUuidClientEventId() {
        val a = build().clientEventId
        val b = build().clientEventId
        assertNotNull(a)
        assertTrue(a != b)
    }

    @Test
    fun timestampIsIso8601WithFractionalSeconds() {
        // Date(0) = epoch, formatter in UTC → trailing Z for UTC offset.
        assertEquals("1970-01-01T00:00:00.000Z", build().timestamp)
    }

    @Test
    fun reservedKeysSurviveEvenWithNoCustomAttributes() {
        val event = build(attrs = null)
        assertNotNull(event.customAttributes)
        assertEquals("Main.kt", event.customAttributes!!["_file"])
    }

    @Test
    fun trimsLongMessage() {
        val long = "x".repeat(3000)
        assertEquals(MessageTrimmer.MAX_EVENT_MESSAGE_LENGTH, build(message = long).message.length)
    }

    @Test
    fun trimsLongCustomAttributeValueButHonorsErrorStackOverride() {
        val event = EventBuilder.build(
            message = "m",
            level = OwlLogLevel.ERROR,
            screenName = null,
            customAttributes = mapOf(
                "big" to "y".repeat(500),
                "_error_stack" to "z".repeat(20000),
            ),
            userId = null,
            sessionId = "s",
            deviceInfo = device,
            isDev = false,
            networkStatus = "none",
            file = "A.kt",
            function = "f",
            line = 1,
            timestamp = Date(0),
        )
        val attrs = event.customAttributes!!
        assertEquals(200, attrs["big"]!!.length)
        assertEquals(16000, attrs["_error_stack"]!!.length)
        // No slash in file → fileName is the whole string.
        assertEquals("A.kt:f:1", event.sourceModule)
        assertNull(event.userId)
    }
}
