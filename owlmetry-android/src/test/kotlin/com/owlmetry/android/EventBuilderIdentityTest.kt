package com.owlmetry.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Date
import java.util.UUID

/**
 * Asserts [EventBuilder.build] produces an event with the right SDK identity,
 * id shape, level/message, and reserved attributes — complementing
 * [EventBuilderTest] (which covers source_module / trimming / timestamp). Runs
 * under Robolectric so the produced [LogEvent] can also be serialized via
 * `org.json` to confirm the identity survives onto the wire.
 */
@RunWith(RobolectricTestRunner::class)
class EventBuilderIdentityTest {

    private val device = DeviceInfo(
        platform = OwlPlatform.ANDROID,
        osVersion = "14",
        appVersion = "2.0.0",
        buildNumber = "9",
        deviceModel = "Google Pixel 8",
        locale = "en_US",
        preferredLanguage = "en-US",
        supportedLanguages = listOf("en-US"),
    )

    private fun build(
        message: String = "user tapped buy",
        level: OwlLogLevel = OwlLogLevel.ERROR,
        attrs: Map<String, String>? = mapOf("plan" to "pro"),
    ) = EventBuilder.build(
        message = message,
        level = level,
        screenName = "Checkout",
        customAttributes = attrs,
        userId = "owl_anon_1",
        sessionId = "sess-xyz",
        deviceInfo = device,
        isDev = false,
        networkStatus = "cellular",
        file = "/src/com/example/Checkout.kt",
        function = "onPurchase",
        line = 88,
        timestamp = Date(0),
    )

    @Test
    fun stampsSdkNameAsOwlmetryAndroid() {
        assertEquals("owlmetry-android", build().sdkName)
        // And the constant the builder reads is the same value.
        assertEquals("owlmetry-android", OwlmetryVersion.NAME)
    }

    @Test
    fun stampsSdkVersionFromVersionConstant() {
        assertEquals(OwlmetryVersion.CURRENT, build().sdkVersion)
    }

    @Test
    fun clientEventIdIsAValidUuid() {
        val id = build().clientEventId
        assertNotNull(id)
        // UUID.fromString throws if the string isn't a canonical UUID; round-trip
        // proves the lowercase canonical 36-char form EventBuilder emits.
        val parsed = UUID.fromString(id)
        assertEquals(id, parsed.toString())
        assertEquals(36, id.length)
    }

    @Test
    fun clientEventIdIsUniquePerBuild() {
        val ids = (0 until 50).map { build().clientEventId }.toSet()
        assertEquals("each build must mint a fresh client_event_id", 50, ids.size)
    }

    @Test
    fun carriesGivenLevelAndMessage() {
        val event = build(message = "boom", level = OwlLogLevel.WARN)
        assertEquals(OwlLogLevel.WARN, event.level)
        assertEquals("boom", event.message)
    }

    @Test
    fun mergesSuppliedCustomAttributesAlongsideReservedKeys() {
        val event = build(attrs = mapOf("plan" to "pro", "step" to "2"))
        val attrs = event.customAttributes!!
        // Supplied attributes survive.
        assertEquals("pro", attrs["plan"])
        assertEquals("2", attrs["step"])
        // Reserved system-meta keys are stamped on top.
        assertEquals("Checkout.kt", attrs["_file"])
        assertEquals("onPurchase", attrs["_function"])
        assertEquals("88", attrs["_line"])
        assertEquals("cellular", attrs["_connection"])
    }

    @Test
    fun passesSessionAndUserAndDevDirectly() {
        val event = build()
        assertEquals("sess-xyz", event.sessionId)
        assertEquals("owl_anon_1", event.userId)
        assertEquals(false, event.isDev)
        assertEquals(OwlPlatform.ANDROID, event.environment)
    }

    @Test
    fun identityAndReservedAttributesSurviveOntoTheWire() {
        // Robolectric-backed org.json: confirm the builder output serializes with
        // the identity + reserved attributes intact (not just on the model).
        val json = build().toJson()
        assertEquals("owlmetry-android", json.getString("sdk_name"))
        assertEquals(OwlmetryVersion.CURRENT, json.getString("sdk_version"))
        assertEquals("error", json.getString("level"))
        assertEquals("user tapped buy", json.getString("message"))
        assertEquals("Checkout.kt:onPurchase:88", json.getString("source_module"))

        // client_event_id is on the wire and is a valid UUID.
        assertEquals(36, json.getString("client_event_id").length)
        UUID.fromString(json.getString("client_event_id"))

        val attrs = json.getJSONObject("custom_attributes")
        assertEquals("pro", attrs.getString("plan"))
        assertEquals("Checkout.kt", attrs.getString("_file"))
        assertEquals("onPurchase", attrs.getString("_function"))
        assertEquals("88", attrs.getString("_line"))
        assertEquals("cellular", attrs.getString("_connection"))
    }

    @Test
    fun reservedAttributesPresentEvenWhenNoCustomAttributesSupplied() {
        val event = build(attrs = null)
        val attrs = event.customAttributes!!
        assertTrue(attrs.containsKey("_file"))
        assertTrue(attrs.containsKey("_function"))
        assertTrue(attrs.containsKey("_line"))
        assertTrue(attrs.containsKey("_connection"))
        assertEquals(EventBuilder.systemMetaKeys, attrs.keys)
    }
}
