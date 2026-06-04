package com.owlmetry.android

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [LogEvent.fromJson] is the inverse of [LogEvent.toJson] — the round-trip the
 * [OfflineQueue] relies on to persist undelivered events through disk. This
 * pins that `toJson() -> fromJson()` reconstructs an identical [LogEvent],
 * including that omitted (null) optionals decode back to null rather than "".
 *
 * Runs under Robolectric so `org.json` is real (plain JVM unit tests no-op
 * org.json because the module sets `isReturnDefaultValues = true`).
 */
@RunWith(RobolectricTestRunner::class)
class LogEventRoundTripTest {

    private fun fullEvent() = LogEvent(
        clientEventId = "11111111-2222-3333-4444-555555555555",
        sessionId = "sess-abc",
        userId = "owl_anon_deadbeef",
        level = OwlLogLevel.WARN,
        sourceModule = "Main.kt:onCreate:42",
        message = "hello world",
        screenName = "Home",
        customAttributes = linkedMapOf("color" to "blue", "_file" to "Main.kt", "_line" to "42"),
        environment = OwlPlatform.ANDROID,
        osVersion = "14",
        appVersion = "1.2.3",
        sdkName = "owlmetry-android",
        sdkVersion = "0.1.0",
        buildNumber = "108",
        deviceModel = "Google Pixel 8",
        locale = "en_US",
        preferredLanguage = "en-US",
        supportedLanguages = listOf("en-US", "de-DE", "fr-FR"),
        isDev = true,
        timestamp = "2026-06-04T12:34:56.789Z",
    )

    @Test
    fun `full event round-trips through json`() {
        val original = fullEvent()
        val decoded = LogEvent.fromJson(original.toJson())
        assertEquals(original, decoded)
    }

    @Test
    fun `null optionals decode back to null not empty string`() {
        val minimal = LogEvent(
            clientEventId = "id-1",
            sessionId = "sess-1",
            userId = null,
            level = OwlLogLevel.INFO,
            sourceModule = null,
            message = "m",
            screenName = null,
            customAttributes = null,
            environment = OwlPlatform.ANDROID,
            osVersion = null,
            appVersion = null,
            sdkName = null,
            sdkVersion = null,
            buildNumber = null,
            deviceModel = null,
            locale = null,
            preferredLanguage = null,
            supportedLanguages = null,
            isDev = false,
            timestamp = "2026-01-01T00:00:00.000Z",
        )
        val decoded = LogEvent.fromJson(minimal.toJson())
        assertEquals(minimal, decoded)
        assertNull(decoded.userId)
        assertNull(decoded.customAttributes)
        assertNull(decoded.supportedLanguages)
    }

    @Test
    fun `list round-trips`() {
        val events = listOf(fullEvent(), fullEvent().copy(clientEventId = "second"))
        val arr = JSONArray()
        for (e in events) arr.put(e.toJson())
        val decoded = LogEvent.listFromJson(arr)
        assertEquals(events, decoded)
    }

    @Test
    fun `unknown level and environment fall back to defaults`() {
        val obj = fullEvent().toJson()
        obj.put("level", "fatal")          // not a known wire value
        obj.put("environment", "ios")      // not Android's wire value
        val decoded = LogEvent.fromJson(obj)
        assertEquals(OwlLogLevel.INFO, decoded.level)
        assertEquals(OwlPlatform.ANDROID, decoded.environment)
    }
}
