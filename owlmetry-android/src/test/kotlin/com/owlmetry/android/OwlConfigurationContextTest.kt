package com.owlmetry.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Context-driven configuration tests. Where [OwlConfigurationTest] exercises the
 * internal explicit-bundleId factory in a plain JVM, these run under Robolectric
 * and go through the *public* `create(context, ...)` overload — the one apps
 * actually call — to prove the bundle ID is resolved from `context.packageName`
 * (the Android analog of Swift's `Bundle.main.bundleIdentifier`) and that the
 * Swift-mirrored validation still fires on that path.
 */
@RunWith(RobolectricTestRunner::class)
class OwlConfigurationContextTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun resolvesBundleIdFromContextPackageName() {
        val config = OwlConfiguration.create(
            context = context,
            endpoint = "https://ingest.owlmetry.com",
            apiKey = "owl_client_abc123",
        )
        // The bundle ID is whatever the host app's Context reports as its
        // packageName (Android analog of Bundle.main.bundleIdentifier). Under
        // Robolectric this is the test application id (e.g.
        // "com.owlmetry.android.test"), so assert the pass-through rather than a
        // hardcoded literal.
        assertEquals(context.packageName, config.bundleId)
        assertTrue(
            "bundleId should be a non-empty package name",
            config.bundleId.isNotEmpty(),
        )
        assertTrue(config.bundleId.startsWith("com.owlmetry.android"))
    }

    @Test
    fun acceptsValidConfigThroughContextFactory() {
        val config = OwlConfiguration.create(
            context = context,
            endpoint = "https://ingest.owlmetry.com/",
            apiKey = "owl_client_live_xyz",
        )
        assertEquals("owl_client_live_xyz", config.apiKey)
        assertEquals("ingest.owlmetry.com", config.endpoint.host)
        assertEquals("https", config.endpoint.scheme)
        // Defaults mirror the Swift initializer defaults (all true).
        assertTrue(config.flushOnBackground)
        assertTrue(config.compressionEnabled)
        assertTrue(config.networkTrackingEnabled)
        assertTrue(config.consoleLogging)
        assertTrue(config.attributionEnabled)
    }

    @Test
    fun honorsNonDefaultFlagsThroughContextFactory() {
        val config = OwlConfiguration.create(
            context = context,
            endpoint = "https://ingest.owlmetry.com",
            apiKey = "owl_client_abc",
            flushOnBackground = false,
            compressionEnabled = false,
            networkTrackingEnabled = false,
            consoleLogging = false,
            attributionEnabled = false,
        )
        assertEquals(false, config.flushOnBackground)
        assertEquals(false, config.compressionEnabled)
        assertEquals(false, config.networkTrackingEnabled)
        assertEquals(false, config.consoleLogging)
        assertEquals(false, config.attributionEnabled)
    }

    @Test
    fun rejectsNonClientApiKeyThroughContextFactory() {
        val e = assertThrows(OwlConfigurationError.InvalidApiKey::class.java) {
            OwlConfiguration.create(
                context = context,
                endpoint = "https://ingest.owlmetry.com",
                apiKey = "owl_agent_abc",
            )
        }
        assertEquals("API key must start with \"owl_client_\"", e.message)
    }

    @Test
    fun rejectsApiKeyMissingPrefixEntirely() {
        assertThrows(OwlConfigurationError.InvalidApiKey::class.java) {
            OwlConfiguration.create(
                context = context,
                endpoint = "https://ingest.owlmetry.com",
                apiKey = "totally_wrong",
            )
        }
    }

    @Test
    fun rejectsMalformedEndpointThroughContextFactory() {
        assertThrows(OwlConfigurationError.InvalidEndpoint::class.java) {
            OwlConfiguration.create(
                context = context,
                endpoint = "not a url",
                apiKey = "owl_client_abc",
            )
        }
    }

    @Test
    fun rejectsRelativeOrSchemelessEndpoint() {
        assertThrows(OwlConfigurationError.InvalidEndpoint::class.java) {
            OwlConfiguration.create(
                context = context,
                endpoint = "ingest.owlmetry.com/v1/ingest",
                apiKey = "owl_client_abc",
            )
        }
    }

    @Test
    fun rejectsNonHttpSchemeThroughContextFactory() {
        assertThrows(OwlConfigurationError.InvalidEndpoint::class.java) {
            OwlConfiguration.create(
                context = context,
                endpoint = "ftp://example.com",
                apiKey = "owl_client_abc",
            )
        }
    }

    @Test
    fun invalidEndpointErrorCarriesOriginalValue() {
        val e = assertThrows(OwlConfigurationError.InvalidEndpoint::class.java) {
            OwlConfiguration.create(
                context = context,
                endpoint = "ftp://example.com",
                apiKey = "owl_client_abc",
            )
        }
        assertEquals("ftp://example.com", e.value)
        assertEquals("Invalid endpoint URL: ftp://example.com", e.message)
    }
}
