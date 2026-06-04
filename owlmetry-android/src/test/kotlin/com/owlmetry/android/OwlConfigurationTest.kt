package com.owlmetry.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Validates config parsing + the Swift-mirrored error cases. */
class OwlConfigurationTest {

    @Test
    fun acceptsValidHttpsEndpointAndClientKey() {
        val config = OwlConfiguration.create(
            endpoint = "https://ingest.owlmetry.com",
            apiKey = "owl_client_abc123",
            bundleId = "com.example.app",
        )
        assertEquals("com.example.app", config.bundleId)
        assertEquals("owl_client_abc123", config.apiKey)
        assertEquals("ingest.owlmetry.com", config.endpoint.host)
    }

    @Test
    fun rejectsNonClientApiKey() {
        val e = assertThrows(OwlConfigurationError.InvalidApiKey::class.java) {
            OwlConfiguration.create(
                endpoint = "https://ingest.owlmetry.com",
                apiKey = "owl_agent_abc",
                bundleId = "com.example.app",
            )
        }
        assertEquals("API key must start with \"owl_client_\"", e.message)
    }

    @Test
    fun rejectsMalformedEndpoint() {
        assertThrows(OwlConfigurationError.InvalidEndpoint::class.java) {
            OwlConfiguration.create(
                endpoint = "not a url",
                apiKey = "owl_client_abc",
                bundleId = "com.example.app",
            )
        }
    }

    @Test
    fun rejectsNonHttpScheme() {
        assertThrows(OwlConfigurationError.InvalidEndpoint::class.java) {
            OwlConfiguration.create(
                endpoint = "ftp://example.com",
                apiKey = "owl_client_abc",
                bundleId = "com.example.app",
            )
        }
    }

    @Test
    fun rejectsEmptyBundleId() {
        assertThrows(OwlConfigurationError.MissingBundleId::class.java) {
            OwlConfiguration.create(
                endpoint = "https://ingest.owlmetry.com",
                apiKey = "owl_client_abc",
                bundleId = "",
            )
        }
    }
}
