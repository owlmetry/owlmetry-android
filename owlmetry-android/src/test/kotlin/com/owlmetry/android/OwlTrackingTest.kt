package com.owlmetry.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Structured-metrics, funnel-step, and user-property behavior of [Owl] +
 * [OwlOperation], mirroring the Swift `OwlMetricsTests` / `OwlOperationTests` /
 * `UserPropertiesTests`:
 *  - `startOperation` emits `metric:<slug>:start` and the returned operation's
 *    `complete`/`fail`/`cancel` emit the matching terminal event, all sharing one
 *    `tracking_id`; terminals carry a numeric `duration_ms`; `fail` is
 *    error-level and adds `error`.
 *  - `recordMetric` emits `metric:<slug>:record` (info-level).
 *  - `step` / deprecated `track` emit `step:<name>`.
 *  - Invalid slugs are auto-corrected by [Owl.normalizeSlug].
 *  - `setUserProperties` POSTs to `/v1/identity/properties` after configure, and
 *    no-ops before configure.
 *
 * Robolectric for real org.json + filesystem; a recording [HttpClient] mock
 * (Android analog of the Swift `URLProtocol` stub) captures ingest + property
 * requests. The flush loop runs on its own scope, so each test forces a drain via
 * `setUser` (→ claim → `flushAll`) and polls.
 */
@RunWith(RobolectricTestRunner::class)
class OwlTrackingTest {

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

    // MARK: - Structured metrics

    @Test
    fun startOperationEmitsStartEventWithTrackingId() {
        configure()
        val op = Owl.startOperation("photo-conversion", attributes = mapOf("source" to "camera"))
        op.complete()

        drainAndPoll { event("metric:photo-conversion:complete") != null }

        val start = event("metric:photo-conversion:start")
        assertNotNull("start event missing", start)
        assertEquals("info", start!!.getString("level"))
        val startAttrs = start.getJSONObject("custom_attributes")
        assertEquals(op.trackingId, startAttrs.getString("tracking_id"))
        assertEquals("camera", startAttrs.getString("source"))
        // start has no duration_ms (only terminals do).
        assertFalse(startAttrs.has("duration_ms"))
    }

    @Test
    fun completeEmitsTerminalEventWithMatchingTrackingIdAndDuration() {
        configure()
        val op = Owl.startOperation("api-request")
        op.complete(attributes = mapOf("status" to "200"))

        drainAndPoll { event("metric:api-request:complete") != null }

        val complete = event("metric:api-request:complete")!!
        assertEquals("info", complete.getString("level"))
        val attrs = complete.getJSONObject("custom_attributes")
        assertEquals(op.trackingId, attrs.getString("tracking_id"))
        assertEquals("200", attrs.getString("status"))
        // duration_ms is a non-negative integer string.
        val durationMs = attrs.getString("duration_ms").toLong()
        assertTrue("duration_ms must be >= 0, was $durationMs", durationMs >= 0)
    }

    @Test
    fun failEmitsErrorLevelTerminalWithErrorAttribute() {
        configure()
        val op = Owl.startOperation("upload")
        op.fail(error = "network timeout")

        drainAndPoll { event("metric:upload:fail") != null }

        val fail = event("metric:upload:fail")!!
        assertEquals("error", fail.getString("level"))
        val attrs = fail.getJSONObject("custom_attributes")
        assertEquals(op.trackingId, attrs.getString("tracking_id"))
        assertEquals("network timeout", attrs.getString("error"))
        assertNotNull(attrs.getString("duration_ms"))
    }

    @Test
    fun cancelEmitsInfoLevelTerminal() {
        configure()
        val op = Owl.startOperation("checkout")
        op.cancel()

        drainAndPoll { event("metric:checkout:cancel") != null }

        val cancel = event("metric:checkout:cancel")!!
        assertEquals("info", cancel.getString("level"))
        val attrs = cancel.getJSONObject("custom_attributes")
        assertEquals(op.trackingId, attrs.getString("tracking_id"))
        assertNotNull(attrs.getString("duration_ms"))
    }

    @Test
    fun recordMetricEmitsRecordEvent() {
        configure()
        Owl.recordMetric("onboarding", attributes = mapOf("step" to "3"))

        drainAndPoll { event("metric:onboarding:record") != null }

        val record = event("metric:onboarding:record")!!
        assertEquals("info", record.getString("level"))
        assertEquals("3", record.getJSONObject("custom_attributes").getString("step"))
    }

    @Test
    fun invalidSlugIsAutoCorrected() {
        configure()
        // "Photo Conversion!" → lowercase, spaces+bang → '-', collapse, trim.
        Owl.recordMetric("Photo Conversion!")

        drainAndPoll { event("metric:photo-conversion:record") != null }
        assertNotNull(event("metric:photo-conversion:record"))
    }

    @Test
    fun normalizeSlugMatchesSwiftRules() {
        // Already-valid slugs pass through untouched.
        assertEquals("photo-conversion", Owl.normalizeSlug("photo-conversion"))
        assertEquals("a1-b2", Owl.normalizeSlug("a1-b2"))
        // Uppercase + spaces + punctuation collapse to single hyphens, trimmed.
        assertEquals("hello-world", Owl.normalizeSlug("Hello World"))
        assertEquals("checkout", Owl.normalizeSlug("  checkout  "))
        assertEquals("a-b-c", Owl.normalizeSlug("a__b##c"))
        assertEquals("foo-bar", Owl.normalizeSlug("foo   bar"))
        assertEquals("leading-trailing", Owl.normalizeSlug("--Leading Trailing--"))
    }

    // MARK: - Funnel steps

    @Test
    fun stepEmitsStepPrefixedEvent() {
        configure()
        Owl.step("signup-started", attributes = mapOf("plan" to "pro"))

        drainAndPoll { event("step:signup-started") != null }

        val step = event("step:signup-started")!!
        assertEquals("info", step.getString("level"))
        assertEquals("pro", step.getJSONObject("custom_attributes").getString("plan"))
    }

    @Test
    @Suppress("DEPRECATION")
    fun deprecatedTrackForwardsToStep() {
        configure()
        Owl.track("legacy-step")

        drainAndPoll { event("step:legacy-step") != null }
        // track() forwards to step(), so the wire message is "step:…", not "track:…".
        assertNotNull(event("step:legacy-step"))
        assertNull("track() must not emit a track:-prefixed event", event("track:legacy-step"))
    }

    // MARK: - User properties

    @Test
    fun setUserPropertiesPostsToPropertiesEndpoint() {
        configure()
        Owl.setUserProperties(mapOf("tier" to "gold", "city" to "NYC"))

        pollUntil(5_000) { http.propertyRequests().isNotEmpty() }

        val reqs = http.propertyRequests()
        assertTrue("no properties POST captured", reqs.isNotEmpty())
        val body = reqs.first()
        assertTrue(body.has("user_id"))
        val props = body.getJSONObject("properties")
        assertEquals("gold", props.getString("tier"))
        assertEquals("NYC", props.getString("city"))
    }

    @Test
    fun setUserPropertiesBeforeConfigureIsNoOp() {
        // No configure() → no transport → must not POST anything.
        Owl.setUserProperties(mapOf("tier" to "gold"))
        pollUntil(500) { false }
        assertTrue("properties POST should not fire before configure", http.propertyRequests().isEmpty())
    }
}
