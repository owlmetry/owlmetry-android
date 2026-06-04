package com.owlmetry.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Locks down the wire format against the Swift SDK's `LogEvent` Codable output:
 * exact snake_case keys, null omission, and types.
 *
 * Runs under Robolectric so `org.json` is the real implementation — plain JVM
 * unit tests stub `android.jar`'s `org.json` (the module's
 * `isReturnDefaultValues = true`), which would no-op `JSONObject.put`.
 */
@RunWith(RobolectricTestRunner::class)
class LogEventWireTest {

    private fun fullEvent() = LogEvent(
        clientEventId = "evt-1",
        sessionId = "sess-1",
        userId = "owl_anon_x",
        level = OwlLogLevel.WARN,
        sourceModule = "Main.kt:onCreate:42",
        message = "hello",
        screenName = "Home",
        customAttributes = linkedMapOf("a" to "1", "_file" to "Main.kt"),
        environment = OwlPlatform.ANDROID,
        osVersion = "14",
        appVersion = "1.2.3",
        sdkName = "owlmetry-android",
        sdkVersion = "0.1.0",
        buildNumber = "42",
        deviceModel = "Google Pixel 8",
        locale = "en_US",
        preferredLanguage = "en-US",
        supportedLanguages = listOf("en-US", "de-DE"),
        isDev = true,
        timestamp = "2026-06-04T12:34:56.789Z",
    )

    @Test
    fun emitsExactSnakeCaseKeysAndValues() {
        val json = fullEvent().toJson()
        assertEquals("evt-1", json.getString("client_event_id"))
        assertEquals("sess-1", json.getString("session_id"))
        assertEquals("owl_anon_x", json.getString("user_id"))
        assertEquals("warn", json.getString("level"))
        assertEquals("Main.kt:onCreate:42", json.getString("source_module"))
        assertEquals("hello", json.getString("message"))
        assertEquals("Home", json.getString("screen_name"))
        assertEquals("android", json.getString("environment"))
        assertEquals("14", json.getString("os_version"))
        assertEquals("1.2.3", json.getString("app_version"))
        assertEquals("owlmetry-android", json.getString("sdk_name"))
        assertEquals("0.1.0", json.getString("sdk_version"))
        assertEquals("42", json.getString("build_number"))
        assertEquals("Google Pixel 8", json.getString("device_model"))
        assertEquals("en_US", json.getString("locale"))
        assertEquals("en-US", json.getString("preferred_language"))
        assertEquals(true, json.getBoolean("is_dev"))
        assertEquals("2026-06-04T12:34:56.789Z", json.getString("timestamp"))

        val attrs = json.getJSONObject("custom_attributes")
        assertEquals("1", attrs.getString("a"))
        assertEquals("Main.kt", attrs.getString("_file"))

        val langs = json.getJSONArray("supported_languages")
        assertEquals(2, langs.length())
        assertEquals("en-US", langs.getString(0))
        assertEquals("de-DE", langs.getString(1))
    }

    @Test
    fun omitsNullOptionalsLikeSwiftCodable() {
        val event = fullEvent().copy(
            userId = null,
            sourceModule = null,
            screenName = null,
            customAttributes = null,
            osVersion = null,
            appVersion = null,
            sdkName = null,
            sdkVersion = null,
            buildNumber = null,
            deviceModel = null,
            locale = null,
            preferredLanguage = null,
            supportedLanguages = null,
        )
        val json = event.toJson()

        // Nullable fields omitted.
        for (key in listOf(
            "user_id", "source_module", "screen_name", "custom_attributes",
            "os_version", "app_version", "sdk_name", "sdk_version",
            "build_number", "device_model", "locale", "preferred_language",
            "supported_languages",
        )) {
            assertFalse("expected $key omitted when null", json.has(key))
        }

        // Required fields always present.
        for (key in listOf(
            "client_event_id", "session_id", "level", "message",
            "environment", "is_dev", "timestamp",
        )) {
            assertTrue("expected $key present", json.has(key))
        }
    }

    @Test
    fun logLevelWireValuesMatchSwift() {
        assertEquals("info", OwlLogLevel.INFO.wire)
        assertEquals("debug", OwlLogLevel.DEBUG.wire)
        assertEquals("warn", OwlLogLevel.WARN.wire)
        assertEquals("error", OwlLogLevel.ERROR.wire)
    }

    @Test
    fun ingestBodyWrapsEventsWithBundleId() {
        val json = IngestRequestBody("com.example.app", listOf(fullEvent())).toJson()
        assertEquals("com.example.app", json.getString("bundle_id"))
        assertEquals(1, json.getJSONArray("events").length())
    }
}
