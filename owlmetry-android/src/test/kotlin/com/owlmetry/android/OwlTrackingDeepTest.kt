package com.owlmetry.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Deep / edge-case coverage for Phase 6 — structured metrics ([OwlOperation] +
 * [Owl.startOperation] / [Owl.recordMetric]), funnel steps ([Owl.step] /
 * deprecated [Owl.track]), slug normalization ([Owl.normalizeSlug]), and
 * user-property POSTs ([Owl.setUserProperties]).
 *
 * Complements [OwlTrackingTest] (happy paths) by mirroring the *exact* assertions
 * from the Swift `SDKIntegrationTests` / `OwlOperationTests` that aren't yet
 * covered:
 *  - one operation's `start` and its terminal share the SAME `tracking_id`, and
 *    distinct operations get distinct ids;
 *  - the `start` event carries NO `duration_ms` while every terminal does, as a
 *    non-negative integer string;
 *  - SDK-owned attributes (`tracking_id`, `duration_ms`, `error`) OVERRIDE any
 *    caller-supplied same-named keys (Swift assigns into the map after copying
 *    the caller dict);
 *  - level separation: `start`/`complete`/`cancel` are info, `fail` is error;
 *  - `normalizeSlug` is exhaustive + the normalized slug is what reaches the wire;
 *  - null-valued `step`/metric attributes are dropped (cleanAttributes path);
 *  - `setUserProperties` wire body shape (`user_id` + `properties` object),
 *    empty-string delete sentinel preserved, every call POSTs once;
 *  - tracking + properties before configure are silent no-ops.
 *
 * Robolectric supplies real org.json + filesystem; a recording [HttpClient]
 * captures ingest + property POSTs. A drain is forced by issuing a claim
 * (`setUser` → `flushAll`) then polling.
 */
@RunWith(RobolectricTestRunner::class)
class OwlTrackingDeepTest {

    private class RecordingHttpClient : HttpClient {
        val requests = CopyOnWriteArrayList<HttpRequest>()

        override fun execute(request: HttpRequest): HttpResponse {
            requests.add(request)
            val path = request.url.path
            val body = when {
                path.endsWith("/v1/identity/claim") -> """{"claimed":true,"events_reassigned_count":0}"""
                path.endsWith("/v1/identity/properties") -> """{"ok":true}"""
                path.endsWith("/v1/ingest") -> """{"accepted":1,"rejected":0}"""
                else -> "{}"
            }
            return HttpResponse(200, body)
        }

        fun ingestEvents(): List<JSONObject> {
            val out = ArrayList<JSONObject>()
            for (req in requests) {
                if (!req.url.path.endsWith("/v1/ingest")) continue
                val text = req.body?.toString(Charsets.UTF_8) ?: continue
                val arr = JSONObject(text).getJSONArray("events")
                for (i in 0 until arr.length()) out.add(arr.getJSONObject(i))
            }
            return out
        }

        fun eventsMatching(predicate: (JSONObject) -> Boolean): List<JSONObject> =
            ingestEvents().filter(predicate)

        fun propertyRequests(): List<JSONObject> =
            requests.filter { it.url.path.endsWith("/v1/identity/properties") }
                .mapNotNull { it.body?.toString(Charsets.UTF_8) }
                .map { JSONObject(it) }
    }

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private lateinit var http: RecordingHttpClient

    @Before
    fun setUp() {
        Owl.resetForTesting()
        http = RecordingHttpClient()
        Owl.httpClientOverrideForTesting = http
        IdentityStore(context).clearUserId()
    }

    @After
    fun tearDown() {
        Owl.resetForTesting()
        Owl.httpClientOverrideForTesting = null
    }

    private fun configure() {
        Owl.configure(
            context = context,
            endpoint = "https://ingest.example.com",
            apiKey = "owl_client_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            compressionEnabled = false,
            networkTrackingEnabled = false,
            consoleLogging = false,
            attributionEnabled = false,
        )
    }

    /** Force a transport drain by issuing a claim (setUser → flushAll), then poll. */
    private fun drainAndPoll(predicate: () -> Boolean) {
        Owl.setUser("real-user-${System.nanoTime()}")
        pollUntil(timeoutMs = 5_000, predicate)
    }

    private fun pollUntil(timeoutMs: Long, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(20)
        }
    }

    private fun event(message: String): JSONObject? =
        http.eventsMatching { it.getString("message") == message }.firstOrNull()

    // MARK: - OwlOperation unit invariants (no network)

    @Test
    fun trackingIdIsAUuidAndDistinctPerOperation() {
        // OwlOperation has an internal constructor reachable in-module.
        val a = OwlOperation(metric = "x")
        val b = OwlOperation(metric = "x")
        // Each op gets a fresh UUID — same metric must not collide ids.
        assertNotEquals(a.trackingId, b.trackingId)
        // RFC-4122 canonical form: 36 chars, 8-4-4-4-12 hex groups.
        assertTrue(
            "trackingId not a canonical UUID: ${a.trackingId}",
            a.trackingId.matches(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")),
        )
    }

    // MARK: - tracking_id pairing & duration semantics

    @Test
    fun startAndTerminalShareOneTrackingIdAcrossLifecycle() {
        configure()
        val op = Owl.startOperation("photo-conversion")
        op.complete()

        drainAndPoll { event("metric:photo-conversion:complete") != null }

        val start = event("metric:photo-conversion:start")!!
        val terminal = event("metric:photo-conversion:complete")!!
        val startId = start.getJSONObject("custom_attributes").getString("tracking_id")
        val terminalId = terminal.getJSONObject("custom_attributes").getString("tracking_id")
        // Server pairs start ↔ terminal via this shared id.
        assertEquals(op.trackingId, startId)
        assertEquals(startId, terminalId)
    }

    @Test
    fun twoConcurrentOperationsDoNotCrossTheirTrackingIds() {
        configure()
        val a = Owl.startOperation("alpha")
        val b = Owl.startOperation("beta")
        a.complete()
        b.complete()

        drainAndPoll {
            event("metric:alpha:complete") != null && event("metric:beta:complete") != null
        }

        val aStart = event("metric:alpha:start")!!.getJSONObject("custom_attributes").getString("tracking_id")
        val aDone = event("metric:alpha:complete")!!.getJSONObject("custom_attributes").getString("tracking_id")
        val bStart = event("metric:beta:start")!!.getJSONObject("custom_attributes").getString("tracking_id")
        val bDone = event("metric:beta:complete")!!.getJSONObject("custom_attributes").getString("tracking_id")

        assertEquals(a.trackingId, aStart)
        assertEquals(a.trackingId, aDone)
        assertEquals(b.trackingId, bStart)
        assertEquals(b.trackingId, bDone)
        assertNotEquals("Distinct operations must not share a tracking id", aStart, bStart)
    }

    @Test
    fun startEventHasNoDurationButEveryTerminalDoes() {
        configure()
        val complete = Owl.startOperation("c-op"); complete.complete()
        val fail = Owl.startOperation("f-op"); fail.fail(error = "boom")
        val cancel = Owl.startOperation("x-op"); cancel.cancel()

        drainAndPoll {
            event("metric:c-op:complete") != null &&
                event("metric:f-op:fail") != null &&
                event("metric:x-op:cancel") != null
        }

        // Starts carry tracking_id but never duration_ms.
        for (slug in listOf("c-op", "f-op", "x-op")) {
            val startAttrs = event("metric:$slug:start")!!.getJSONObject("custom_attributes")
            assertTrue("$slug start missing tracking_id", startAttrs.has("tracking_id"))
            assertFalse("$slug start must not carry duration_ms", startAttrs.has("duration_ms"))
        }

        // Every terminal carries a non-negative integer duration_ms (string form).
        for (msg in listOf("metric:c-op:complete", "metric:f-op:fail", "metric:x-op:cancel")) {
            val attrs = event(msg)!!.getJSONObject("custom_attributes")
            val raw = attrs.getString("duration_ms")
            val parsed = raw.toLongOrNull()
            assertNotNull("duration_ms on $msg must be an integer string, was \"$raw\"", parsed)
            assertTrue("duration_ms on $msg must be >= 0, was $parsed", parsed!! >= 0)
        }
    }

    // MARK: - SDK attributes override caller-supplied same-named keys

    @Test
    fun completeOverridesCallerSuppliedTrackingIdAndDuration() {
        configure()
        val op = Owl.startOperation("override-op")
        // Caller tries to inject bogus tracking_id + duration_ms; SDK must win.
        op.complete(attributes = mapOf("tracking_id" to "HIJACK", "duration_ms" to "-999", "extra" to "kept"))

        drainAndPoll { event("metric:override-op:complete") != null }

        val attrs = event("metric:override-op:complete")!!.getJSONObject("custom_attributes")
        assertEquals("SDK tracking_id must override caller value", op.trackingId, attrs.getString("tracking_id"))
        assertNotEquals("SDK duration_ms must override caller value", "-999", attrs.getString("duration_ms"))
        assertTrue(attrs.getString("duration_ms").toLong() >= 0)
        // Caller's unrelated attribute is preserved.
        assertEquals("kept", attrs.getString("extra"))
    }

    @Test
    fun failOverridesCallerSuppliedErrorAttribute() {
        configure()
        val op = Owl.startOperation("fail-override")
        // Swift assigns attrs["error"] = error AFTER copying the caller dict, so
        // the explicit error: argument wins over any caller-supplied "error" key.
        op.fail(error = "real-error", attributes = mapOf("error" to "caller-error", "ctx" to "x"))

        drainAndPoll { event("metric:fail-override:fail") != null }

        val attrs = event("metric:fail-override:fail")!!.getJSONObject("custom_attributes")
        assertEquals("real-error", attrs.getString("error"))
        assertEquals("x", attrs.getString("ctx"))
    }

    // MARK: - Level separation

    @Test
    fun terminalLevelsMatchSwift() {
        configure()
        Owl.startOperation("lvl-c").complete()
        Owl.startOperation("lvl-f").fail(error = "e")
        Owl.startOperation("lvl-x").cancel()

        drainAndPoll {
            event("metric:lvl-c:complete") != null &&
                event("metric:lvl-f:fail") != null &&
                event("metric:lvl-x:cancel") != null
        }

        assertEquals("info", event("metric:lvl-c:start")!!.getString("level"))
        assertEquals("info", event("metric:lvl-c:complete")!!.getString("level"))
        assertEquals("error", event("metric:lvl-f:fail")!!.getString("level"))
        assertEquals("info", event("metric:lvl-x:cancel")!!.getString("level"))
    }

    // MARK: - Slug normalization parity (pure unit + applied to the wire)

    @Test
    fun normalizeSlugExhaustiveParity() {
        // Already-valid → untouched.
        assertEquals("photo-conversion", Owl.normalizeSlug("photo-conversion"))
        assertEquals("a", Owl.normalizeSlug("a"))
        assertEquals("0", Owl.normalizeSlug("0"))
        assertEquals("a-b-c", Owl.normalizeSlug("a-b-c"))
        // Uppercase forces normalization even with otherwise-valid chars.
        assertEquals("hello", Owl.normalizeSlug("Hello"))
        assertEquals("hello-world", Owl.normalizeSlug("HELLO_WORLD"))
        // Spaces, punctuation, and symbol runs collapse to single hyphens.
        assertEquals("photo-conversion", Owl.normalizeSlug("Photo Conversion!"))
        assertEquals("a-b", Owl.normalizeSlug("a   b"))
        assertEquals("a-b", Owl.normalizeSlug("a@@@b"))
        // Leading/trailing invalid chars are trimmed.
        assertEquals("checkout", Owl.normalizeSlug("...checkout..."))
        assertEquals("leading-trailing", Owl.normalizeSlug("--Leading Trailing--"))
        // Non-ASCII letters are NOT in [a-z0-9-] → become hyphens, then collapse.
        assertEquals("caf", Owl.normalizeSlug("café"))
        assertEquals("a-b", Owl.normalizeSlug("a–b")) // en-dash is not '-'
        // Digits survive.
        assertEquals("v2-launch", Owl.normalizeSlug("v2 launch"))
    }

    @Test
    fun startOperationEmitsUnderNormalizedSlug() {
        configure()
        // The returned op's lifecycle messages use the normalized slug too.
        val op = Owl.startOperation("Photo Conversion!")
        op.complete()

        drainAndPoll { event("metric:photo-conversion:complete") != null }

        assertNotNull(event("metric:photo-conversion:start"))
        assertNotNull(event("metric:photo-conversion:complete"))
        // The raw (un-normalized) slug must NOT appear on the wire.
        assertNull(event("metric:Photo Conversion!:start"))
    }

    // MARK: - Attribute null filtering on the metric / step path

    @Test
    fun nullValuedStepAttributesAreDropped() {
        configure()
        Owl.step("signup", attributes = mapOf("present" to "yes", "absent" to null))

        drainAndPoll { event("step:signup") != null }

        val attrs = event("step:signup")!!.getJSONObject("custom_attributes")
        assertEquals("yes", attrs.getString("present"))
        assertFalse("null-valued attribute must be omitted from the shipped event", attrs.has("absent"))
    }

    @Test
    fun recordMetricWithAllNullCallerAttributesShipsOnlyReservedKeys() {
        configure()
        // All caller attributes are null → cleanAttributes returns null. The event
        // still carries custom_attributes because EventBuilder always merges the
        // reserved _file/_function/_line/_connection keys (mirrors Swift — the map
        // "is nulled out if empty (it never is, given the reserved keys)").
        Owl.recordMetric("empty-attrs", attributes = mapOf("a" to null, "b" to null))

        drainAndPoll { event("metric:empty-attrs:record") != null }

        val attrs = event("metric:empty-attrs:record")!!.getJSONObject("custom_attributes")
        // Null-valued caller keys never reach the wire...
        assertFalse("null-valued caller attribute 'a' must be dropped", attrs.has("a"))
        assertFalse("null-valued caller attribute 'b' must be dropped", attrs.has("b"))
        // ...but the SDK's reserved system keys are always present.
        assertTrue(attrs.has("_file"))
        assertTrue(attrs.has("_function"))
        assertTrue(attrs.has("_line"))
        assertTrue(attrs.has("_connection"))
    }

    // MARK: - Deprecated track() parity

    @Test
    @Suppress("DEPRECATION")
    fun deprecatedTrackCarriesAttributesThroughToStep() {
        configure()
        Owl.track("legacy", attributes = mapOf("k" to "v"))

        drainAndPoll { event("step:legacy") != null }

        val step = event("step:legacy")!!
        assertEquals("info", step.getString("level"))
        assertEquals("v", step.getJSONObject("custom_attributes").getString("k"))
        // Must never emit a track:-prefixed message.
        assertNull(event("track:legacy"))
    }

    // MARK: - setUserProperties wire shape

    @Test
    fun setUserPropertiesPostsExactWireBody() {
        configure()
        Owl.setUserProperties(mapOf("plan" to "premium", "org" to "acme"))

        pollUntil(5_000) { http.propertyRequests().isNotEmpty() }

        val reqs = http.propertyRequests()
        assertEquals("exactly one properties POST expected", 1, reqs.size)
        val body = reqs.first()
        // Body shape: top-level user_id + nested properties object.
        assertTrue("user_id must be present and resolved (anonymous id pre-setUser)", body.has("user_id"))
        assertTrue(body.getString("user_id").isNotEmpty())
        val props = body.getJSONObject("properties")
        assertEquals("premium", props.getString("plan"))
        assertEquals("acme", props.getString("org"))
        assertEquals("only the supplied keys ship", 2, props.length())
    }

    @Test
    fun setUserPropertiesPreservesEmptyStringDeleteSentinel() {
        configure()
        // Empty-string value is the server-side delete sentinel — it must NOT be
        // stripped client-side (distinct from null attribute filtering on events).
        Owl.setUserProperties(mapOf("remove_me" to "", "keep" to "v"))

        pollUntil(5_000) { http.propertyRequests().isNotEmpty() }

        val props = http.propertyRequests().first().getJSONObject("properties")
        assertTrue("empty-string delete sentinel must be preserved", props.has("remove_me"))
        assertEquals("", props.getString("remove_me"))
        assertEquals("v", props.getString("keep"))
    }

    @Test
    fun eachSetUserPropertiesCallPostsOnce() {
        configure()
        Owl.setUserProperties(mapOf("a" to "1"))
        Owl.setUserProperties(mapOf("b" to "2"))

        pollUntil(5_000) { http.propertyRequests().size >= 2 }

        val reqs = http.propertyRequests()
        assertEquals("each call fires its own merge POST", 2, reqs.size)
        // Server merges; the SDK does not coalesce — both bodies are sent verbatim.
        val allProps = reqs.map { it.getJSONObject("properties") }
        assertTrue(allProps.any { it.has("a") && it.getString("a") == "1" })
        assertTrue(allProps.any { it.has("b") && it.getString("b") == "2" })
    }

    // MARK: - Pre-configure no-ops

    @Test
    fun trackingBeforeConfigureEmitsNothing() {
        // No configure() → no transport. Tracking calls must silently no-op.
        Owl.recordMetric("ghost")
        Owl.step("ghost-step")
        val op = Owl.startOperation("ghost-op")
        op.complete()
        op.fail(error = "x")
        op.cancel()

        // Now configure and force a drain — nothing from before should appear.
        configure()
        drainAndPoll { false } // just give the pipeline time to flush a claim.

        assertTrue(
            "no metric/step/operation events should ship from before configure",
            http.eventsMatching {
                val m = it.getString("message")
                m.startsWith("metric:ghost") || m.startsWith("step:ghost") || m.startsWith("metric:ghost-op")
            }.isEmpty(),
        )
    }

    @Test
    fun setUserPropertiesBeforeConfigureNeverPosts() {
        Owl.setUserProperties(mapOf("tier" to "gold"))
        pollUntil(500) { false }
        assertTrue(
            "properties POST must not fire before configure",
            http.propertyRequests().isEmpty(),
        )
    }
}
