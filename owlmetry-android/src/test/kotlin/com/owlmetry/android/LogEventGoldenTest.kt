package com.owlmetry.android

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * GOLDEN PARITY against the Swift SDK's `LogEvent` `Codable` output. This is the
 * load-bearing wire-format lock: the same `/v1/ingest` server must accept Android
 * and Swift events byte-for-byte at the field level, so this test pins
 *
 *  - the EXACT set of JSON keys (no extras, no renames, no camelCase leaks),
 *  - the type of every value (string / boolean / nested object / array),
 *  - that `null` optionals are OMITTED entirely (Swift `Codable` skips `nil`),
 *  - `custom_attributes` is a nested object, `supported_languages` an array,
 *  - `level` serializes as its lowercase wire string,
 *  - `environment` is "android".
 *
 * Swift's `CodingKeys` (Models/LogEvent.swift) is the authoritative key list.
 * Runs under Robolectric so `org.json` is the real implementation (plain JVM
 * unit tests no-op `JSONObject.put` because the module sets
 * `isReturnDefaultValues = true`).
 */
@RunWith(RobolectricTestRunner::class)
class LogEventGoldenTest {

    /** Every snake_case key Swift's `LogEvent.CodingKeys` can emit. */
    private val allKeys = setOf(
        "client_event_id",
        "session_id",
        "user_id",
        "level",
        "source_module",
        "message",
        "screen_name",
        "custom_attributes",
        "environment",
        "os_version",
        "app_version",
        "sdk_name",
        "sdk_version",
        "build_number",
        "device_model",
        "locale",
        "preferred_language",
        "supported_languages",
        "is_dev",
        "timestamp",
    )

    /** Keys for the seven always-present (non-nullable in Swift) fields. */
    private val requiredKeys = setOf(
        "client_event_id",
        "session_id",
        "level",
        "message",
        "environment",
        "is_dev",
        "timestamp",
    )

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

    /** Collect the actual key set off a JSONObject. */
    private fun keysOf(obj: JSONObject): Set<String> {
        val keys = mutableSetOf<String>()
        val it = obj.keys()
        while (it.hasNext()) keys.add(it.next())
        return keys
    }

    @Test
    fun fullEventEmitsExactlyTheSwiftKeySet() {
        val json = fullEvent().toJson()
        // EXACT equality: any extra key (e.g. a camelCase leak or a new field
        // not mirrored in Swift) fails here; any missing required key too.
        assertEquals(allKeys, keysOf(json))
    }

    @Test
    fun stringTypedFieldsAreStrings() {
        val json = fullEvent().toJson()
        // org.json's get(...) returns the boxed type; assert each value's class.
        val stringKeys = allKeys - "custom_attributes" - "supported_languages" - "is_dev"
        for (key in stringKeys) {
            assertTrue(
                "$key should serialize as a JSON string, was ${json.get(key)::class.java.simpleName}",
                json.get(key) is String,
            )
        }
    }

    @Test
    fun isDevIsBooleanNotString() {
        val json = fullEvent().toJson()
        assertTrue("is_dev must be a JSON boolean", json.get("is_dev") is Boolean)
        assertEquals(true, json.getBoolean("is_dev"))
    }

    @Test
    fun levelSerializesAsLowercaseWireString() {
        assertEquals("warn", fullEvent().toJson().getString("level"))
        assertEquals("info", fullEvent().copy(level = OwlLogLevel.INFO).toJson().getString("level"))
        assertEquals("debug", fullEvent().copy(level = OwlLogLevel.DEBUG).toJson().getString("level"))
        assertEquals("error", fullEvent().copy(level = OwlLogLevel.ERROR).toJson().getString("level"))
    }

    @Test
    fun environmentIsAndroid() {
        assertEquals("android", fullEvent().toJson().getString("environment"))
    }

    @Test
    fun customAttributesIsNestedObjectWithVerbatimEntries() {
        val json = fullEvent().toJson()
        assertTrue("custom_attributes must be a nested object", json.get("custom_attributes") is JSONObject)
        val attrs = json.getJSONObject("custom_attributes")
        assertEquals(setOf("color", "_file", "_line"), keysOf(attrs))
        assertEquals("blue", attrs.getString("color"))
        assertEquals("Main.kt", attrs.getString("_file"))
        assertEquals("42", attrs.getString("_line"))
    }

    @Test
    fun supportedLanguagesIsArrayInOrder() {
        val json = fullEvent().toJson()
        assertTrue("supported_languages must be a JSON array", json.get("supported_languages") is JSONArray)
        val arr = json.getJSONArray("supported_languages")
        assertEquals(3, arr.length())
        assertEquals("en-US", arr.getString(0))
        assertEquals("de-DE", arr.getString(1))
        assertEquals("fr-FR", arr.getString(2))
    }

    @Test
    fun nullOptionalsAreOmittedNotPresentAsNull() {
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

        // The key set collapses to EXACTLY the seven required fields. This is
        // strictly stronger than checking has() per key: it also proves no
        // optional leaked through as an explicit JSON null.
        assertEquals(requiredKeys, keysOf(json))

        // And belt-and-suspenders: no value is JSONObject.NULL.
        for (key in keysOf(json)) {
            assertFalse("$key must not be JSON null", json.isNull(key))
        }
    }

    @Test
    fun emptyCustomAttributesStillSerializesAsObjectWhenNonNull() {
        // A non-null but empty map is still present (Swift omits only nil, not an
        // empty dictionary). EventBuilder never produces this, but the model must
        // not drop a non-null value.
        val json = fullEvent().copy(customAttributes = emptyMap()).toJson()
        assertTrue(json.has("custom_attributes"))
        assertEquals(0, json.getJSONObject("custom_attributes").length())
    }

    @Test
    fun goldenStringRoundTripsThroughAFreshParse() {
        // Serialize → reparse → re-check a representative slice, proving the
        // String form is well-formed JSON and not just an in-memory JSONObject.
        val reparsed = JSONObject(fullEvent().toJsonString())
        assertEquals(allKeys, keysOf(reparsed))
        assertEquals("warn", reparsed.getString("level"))
        assertEquals("android", reparsed.getString("environment"))
        assertEquals(true, reparsed.getBoolean("is_dev"))
        assertEquals("blue", reparsed.getJSONObject("custom_attributes").getString("color"))
        assertEquals("fr-FR", reparsed.getJSONArray("supported_languages").getString(2))
    }
}
