package com.owlmetry.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList

/**
 * End-to-end logging behavior of [Owl], mirroring the Swift `OwlIntegrationTests`
 * + `SetUserRaceTests`: log levels stamp the right `level`, null attributes are
 * filtered ([Owl.cleanAttributes]), `error(Throwable)` populates `_error_*`,
 * duplicates are suppressed, and the in-flight-log gate keeps the identity claim
 * POST ordered after the events it depends on.
 *
 * Robolectric for real org.json + filesystem (the offline queue + device info).
 * A recording [HttpClient] is installed via [Owl.httpClientOverrideForTesting]
 * (the Android analog of the Swift tests' global `URLProtocol` mock), so we can
 * assert request ordering + payloads without a live server. The SDK's flush
 * loop runs on its own `Dispatchers.Default` scope, so tests force a drain via
 * `setUser` (→ claim → `flushAll`) and poll the recorder.
 */
@RunWith(RobolectricTestRunner::class)
class OwlLoggingTest {

    /** Records every request with arrival order; replies success per-endpoint. */
    private class RecordingHttpClient : HttpClient {
        val requests = CopyOnWriteArrayList<HttpRequest>()
        @Volatile var seq = 0
        val arrivalSeqByIndex = CopyOnWriteArrayList<Int>()

        override fun execute(request: HttpRequest): HttpResponse {
            requests.add(request)
            arrivalSeqByIndex.add(++seq)
            val path = request.url.path
            val body = when {
                path.endsWith("/v1/identity/claim") -> """{"claimed":true,"events_reassigned_count":0}"""
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
    }

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private lateinit var http: RecordingHttpClient

    @Before
    fun setUp() {
        Owl.resetForTesting()
        http = RecordingHttpClient()
        Owl.httpClientOverrideForTesting = http
        // Start from a clean anonymous identity so saved real ids from a prior
        // test don't fire an unexpected startup claim.
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
            // No gzip so recorded bodies are plain JSON; no console noise.
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

    @Test
    fun logLevelsStampTheCorrectLevel() {
        configure()
        Owl.info("an info", screenName = "Home")
        Owl.debug("a debug")
        Owl.warn("a warn")
        Owl.error("an error")

        drainAndPoll { http.eventsMatching { it.getString("message") == "an error" }.isNotEmpty() }

        fun levelOf(msg: String) =
            http.eventsMatching { it.getString("message") == msg }.firstOrNull()?.getString("level")

        assertEquals("info", levelOf("an info"))
        assertEquals("debug", levelOf("a debug"))
        assertEquals("warn", levelOf("a warn"))
        assertEquals("error", levelOf("an error"))

        val info = http.eventsMatching { it.getString("message") == "an info" }.first()
        assertEquals("Home", info.getString("screen_name"))
    }

    @Test
    fun sourceModuleIsDerivedFromTheCallSite() {
        // Kotlin has no #file/#line; the SDK walks the stack to the first frame
        // outside its own package. The call is made from com.example.app
        // (CallSiteHelper) — the real-app scenario — so source_module must name
        // that file/method, not the "Unknown:unknown:0" placeholder.
        configure()
        com.example.app.CallSiteHelper.emitInfo("call-site-test")

        drainAndPoll { http.eventsMatching { it.getString("message") == "call-site-test" }.isNotEmpty() }

        val event = http.eventsMatching { it.getString("message") == "call-site-test" }.first()
        val sourceModule = event.getString("source_module")
        assertFalse(
            "source_module must not be the placeholder when derivable",
            sourceModule == "Unknown:unknown:0",
        )
        assertTrue(
            "source_module should name the caller file, was '$sourceModule'",
            sourceModule.startsWith("CallSiteHelper.kt:emitInfo:"),
        )
        val attrs = event.getJSONObject("custom_attributes")
        assertEquals("CallSiteHelper.kt", attrs.getString("_file"))
        assertEquals("emitInfo", attrs.getString("_function"))
    }

    @Test
    fun nullAttributesAreFilteredOut() {
        configure()
        Owl.info("attr-test", attributes = mapOf("present" to "yes", "absent" to null))

        drainAndPoll { http.eventsMatching { it.getString("message") == "attr-test" }.isNotEmpty() }

        val event = http.eventsMatching { it.getString("message") == "attr-test" }.first()
        val attrs = event.getJSONObject("custom_attributes")
        assertEquals("yes", attrs.getString("present"))
        assertFalse("null-valued attr should be dropped", attrs.has("absent"))
    }

    @Test
    fun errorThrowablePopulatesErrorAttributes() {
        configure()
        val cause = java.io.IOException("disk full")
        Owl.error(RuntimeException("save failed", cause), "while saving")

        drainAndPoll { http.eventsMatching { it.getString("message") == "while saving" }.isNotEmpty() }

        val event = http.eventsMatching { it.getString("message") == "while saving" }.first()
        assertEquals("error", event.getString("level"))
        val attrs = event.getJSONObject("custom_attributes")
        assertEquals("java.lang.RuntimeException", attrs.getString("_error_type"))
        assertEquals("java.io.IOException", attrs.getString("_error_cause_1_type"))
        assertEquals("disk full", attrs.getString("_error_cause_1_message"))
        assertTrue(attrs.getString("_error_stack").contains("RuntimeException"))
    }

    @Test
    fun callerErrorAttributesDoNotOverrideSdkErrorKeys() {
        configure()
        Owl.error(
            RuntimeException("boom"),
            attributes = mapOf("_error_type" to "ATTACKER", "custom" to "kept"),
        )

        drainAndPoll { http.eventsMatching { it.getString("message").contains("boom") }.isNotEmpty() }

        val event = http.eventsMatching { it.getString("message").contains("boom") }.first()
        val attrs = event.getJSONObject("custom_attributes")
        // SDK-owned key wins; caller's own key is preserved.
        assertEquals("java.lang.RuntimeException", attrs.getString("_error_type"))
        assertEquals("kept", attrs.getString("custom"))
    }

    @Test
    fun duplicateEventsAreSuppressedPastTheCap() {
        configure()
        // 15 identical events: at most 10 survive the DuplicateFilter window.
        repeat(15) { Owl.info("spammy", screenName = "S") }

        drainAndPoll { http.eventsMatching { it.getString("message") == "spammy" }.size >= 10 }
        // Give any stragglers a moment, then assert the cap held.
        pollUntil(300) { false }

        val delivered = http.eventsMatching { it.getString("message") == "spammy" }.size
        assertEquals("DuplicateFilter should cap identical events at 10", 10, delivered)
    }

    @Test
    fun sessionStartedEventIsEmittedOnConfigure() {
        configure()
        drainAndPoll { http.eventsMatching { it.getString("message") == "sdk:session_started" }.isNotEmpty() }
        assertTrue(http.eventsMatching { it.getString("message") == "sdk:session_started" }.isNotEmpty())
    }

    @Test
    fun sessionStartedCarriesNonNegativeLaunchMsWhenPresent() {
        configure()
        drainAndPoll { http.eventsMatching { it.getString("message") == "sdk:session_started" }.isNotEmpty() }
        val session = http.eventsMatching { it.getString("message") == "sdk:session_started" }.first()
        // `_launch_ms` is best-effort (omitted only on a non-positive process-start
        // delta). When present it must be a non-negative integer string — the
        // Android analog of Swift's sysctl process-launch-duration.
        val attrs = if (session.has("custom_attributes")) session.getJSONObject("custom_attributes") else null
        if (attrs != null && attrs.has("_launch_ms")) {
            val launchMs = attrs.getString("_launch_ms").toLong()
            assertTrue("_launch_ms must be >= 0, was $launchMs", launchMs >= 0)
        }
    }

    @Test
    fun claimPostArrivesAfterAllPriorIngestPosts() {
        // SetUserRace port: a burst of logs followed immediately by setUser must
        // have the claim POST land at/after the last burst-event ingest, or the
        // server-side anon→real merge runs against an empty events table.
        configure()
        // 30 events; EventTransport batches at 20 → at least two ingest batches.
        repeat(30) { Owl.info("burst_$it", screenName = "race") }

        // No sleep — the gate is exactly what makes this safe.
        val realId = "race-real-${System.nanoTime()}"
        Owl.setUser(realId)

        pollUntil(8_000) {
            http.requests.any { it.url.path.endsWith("/v1/identity/claim") }
        }

        // Drain any residual in-flight work.
        pollUntil(2_000) {
            http.eventsMatching { it.getString("message").startsWith("burst_") }.size >= 30
        }

        val requests = http.requests.toList()
        val seqs = http.arrivalSeqByIndex.toList()

        val claimIndices = requests.indices.filter { requests[it].url.path.endsWith("/v1/identity/claim") }
        assertTrue("no claim POST captured", claimIndices.isNotEmpty())
        val firstClaimSeq = claimIndices.minOf { seqs[it] }

        // Find the arrival sequence of the last ingest POST that carried a burst event.
        var lastBurstIngestSeq = -1
        var burstCount = 0
        for (i in requests.indices) {
            if (!requests[i].url.path.endsWith("/v1/ingest")) continue
            val text = requests[i].body?.toString(Charsets.UTF_8) ?: continue
            val arr = JSONObject(text).getJSONArray("events")
            var hasBurst = false
            for (j in 0 until arr.length()) {
                if (arr.getJSONObject(j).getString("message").startsWith("burst_")) {
                    burstCount += 1
                    hasBurst = true
                }
            }
            if (hasBurst && seqs[i] > lastBurstIngestSeq) lastBurstIngestSeq = seqs[i]
        }

        assertEquals("all 30 burst events should reach /v1/ingest", 30, burstCount)
        assertNotNull(lastBurstIngestSeq)
        // The load-bearing assertion: claim arrives at or after the last burst ingest.
        assertTrue(
            "claim POST (seq=$firstClaimSeq) arrived BEFORE last burst ingest (seq=$lastBurstIngestSeq) — gate not holding",
            firstClaimSeq >= lastBurstIngestSeq,
        )

        // Claim payload carries the original anon id + the new real id.
        val claimReq = requests[claimIndices.first { seqs[it] == firstClaimSeq }]
        val claimJson = JSONObject(claimReq.body!!.toString(Charsets.UTF_8))
        assertEquals(realId, claimJson.getString("user_id"))
        assertTrue(claimJson.getString("anonymous_id").startsWith(IdentityStore.ANONYMOUS_ID_PREFIX))
    }
}
