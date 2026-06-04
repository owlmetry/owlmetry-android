package com.owlmetry.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Public-API parity for [Owl]'s questionnaire surface, mirroring the Swift
 * `Owl.fetchQuestionnaire` / `saveQuestionnaireResponse` / `dismissQuestionnaires`
 * + the process-local "shown this launch" dedupe + the launch/foreground/install
 * accessors. The transport-level wire shape is covered by
 * [QuestionnaireTransportTest]; this file drives the methods *through* a live
 * `Owl.configure(...)` so it covers the bits the transport tests can't:
 *
 *  - [Owl.fetchQuestionnaire] / [Owl.saveQuestionnaireResponse] /
 *    [Owl.dismissQuestionnaires] throw [OwlQuestionnaireError.NotConfigured]
 *    before configure (Swift `guard let snapshot`).
 *  - `Owl.fetchQuestionnaire` unwraps the success result and re-throws the
 *    transport error; the auto-attached `user_id` is the resolved identity.
 *  - `Owl.saveQuestionnaireResponse` emits `sdk:questionnaire_submitted`
 *    **only** when the receipt's `was_submitted` is true (silent on draft saves)
 *    — Swift's "telemetry only on the flip" rule.
 *  - `Owl.dismissQuestionnaires` returns the server `dismissed_at` and emits
 *    `sdk:questionnaire_dismissed`.
 *  - the process-local shown-slug dedupe ([questionnaireWasShownThisProcess] /
 *    [markQuestionnaireShown] / [debugClearShownQuestionnaires]).
 *  - launch/foreground/install accessors read through to
 *    [OwlQuestionnaireState.shared] (0 / null before configure, 1 after).
 *
 * Robolectric for real org.json + SharedPreferences; a recording [HttpClient]
 * scripts the questionnaire endpoints (the Android analog of the Swift tests'
 * `URLProtocol` mock).
 */
@RunWith(RobolectricTestRunner::class)
class QuestionnaireOwlApiTest {

    /** Records every request; scripts the questionnaire endpoints, 2xx elsewhere. */
    private class RecordingHttpClient : HttpClient {
        val requests = CopyOnWriteArrayList<HttpRequest>()

        @Volatile var fetchResponse: HttpResponse =
            HttpResponse(200, """{"eligible":false,"reason":"already_responded"}""")

        /** Scripted save responses (HttpResponse), else [saveDefault]. */
        val saveScript = ArrayDeque<HttpResponse>()
        @Volatile var saveDefault: HttpResponse =
            HttpResponse(200, """{"id":"resp-x","created_at":"2026-06-04T00:00:00.000Z","was_submitted":false}""")

        @Volatile var dismissResponse: HttpResponse =
            HttpResponse(200, """{"dismissed_at":"2026-06-04T12:00:00.000Z"}""")

        override fun execute(request: HttpRequest): HttpResponse {
            requests.add(request)
            val path = request.url.path
            return when {
                path.endsWith("/responses") -> saveScript.removeFirstOrNull() ?: saveDefault
                path.endsWith("/v1/questionnaires/dismiss") -> dismissResponse
                path.contains("/v1/questionnaires/") -> fetchResponse
                path.endsWith("/v1/identity/claim") -> HttpResponse(200, """{"claimed":true,"events_reassigned_count":0}""")
                path.endsWith("/v1/ingest") -> HttpResponse(200, """{"accepted":1,"rejected":0}""")
                else -> HttpResponse(200, "{}")
            }
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

        fun eventsWithMessage(msg: String): List<JSONObject> =
            ingestEvents().filter { it.getString("message") == msg }

        fun saveRequests(): List<HttpRequest> = requests.filter { it.url.path.endsWith("/responses") }
        fun fetchRequests(): List<HttpRequest> =
            requests.filter { it.method == "GET" && it.url.path.contains("/v1/questionnaires/") }
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
        // Clear the questionnaire-state prefs so launchCount starts from 0.
        OwlQuestionnaireState(context).debugReset()
    }

    @After
    fun tearDown() {
        Owl.resetForTesting()
        Owl.httpClientOverrideForTesting = null
        OwlQuestionnaireState(context).debugReset()
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

    /** Force a transport drain (setUser → claim → flushAll), then poll. */
    private fun drainAndPoll(predicate: () -> Boolean) {
        Owl.setUser("real-user-${System.nanoTime()}")
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(20)
        }
    }

    private val eligibleBody = """
        { "eligible": true,
          "questionnaire": {
            "id": "q1", "slug": "nps", "name": "NPS", "description": null,
            "schema": { "version": 1, "questions": [
              { "type": "nps", "id": "n", "title": "Recommend?", "required": true }
            ] } },
          "in_progress": { "response_id": "r-9", "answers": { "n": 6 } } }
    """.trimIndent()

    // ---- NotConfigured before configure ----

    @Test
    fun `fetch before configure throws NotConfigured`() = runBlocking {
        try {
            Owl.fetchQuestionnaire(slug = "nps")
            fail("expected NotConfigured")
        } catch (e: OwlQuestionnaireError) {
            assertTrue(e is OwlQuestionnaireError.NotConfigured)
        }
        // No network call was made.
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun `save before configure throws NotConfigured`() = runBlocking {
        try {
            Owl.saveQuestionnaireResponse(
                slug = "nps",
                answers = mapOf("n" to OwlQuestionnaireAnswerValue.NpsValue(8)),
                isComplete = true,
            )
            fail("expected NotConfigured")
        } catch (e: OwlQuestionnaireError) {
            assertTrue(e is OwlQuestionnaireError.NotConfigured)
        }
        assertTrue(http.saveRequests().isEmpty())
    }

    @Test
    fun `dismiss before configure throws NotConfigured`() = runBlocking {
        try {
            Owl.dismissQuestionnaires()
            fail("expected NotConfigured")
        } catch (e: OwlQuestionnaireError) {
            assertTrue(e is OwlQuestionnaireError.NotConfigured)
        }
    }

    // ---- fetch through Owl ----

    @Test
    fun `fetch through Owl unwraps the result and attaches the resolved user id`() = runBlocking {
        http.fetchResponse = HttpResponse(200, eligibleBody)
        configure()

        val result = Owl.fetchQuestionnaire(slug = "nps")
        assertEquals("nps", result.questionnaire?.slug)
        assertEquals("r-9", result.inProgress?.responseId)
        assertEquals(OwlQuestionnaireAnswerValue.NpsValue(6), result.inProgress?.answers?.get("n"))

        // The auto-attached user_id is the resolved anonymous identity.
        val req = http.fetchRequests().single()
        val anon = IdentityStore(context).anonymousId()
        assertTrue("expected user_id=$anon in ${req.url}", req.url.toString().contains("user_id=$anon"))
        // force not requested by default.
        assertFalse(req.url.toString().contains("force=true"))
    }

    @Test
    fun `fetch through Owl re-throws SlugNotFound on 404`() = runBlocking {
        http.fetchResponse = HttpResponse(404, "nope")
        configure()
        try {
            Owl.fetchQuestionnaire(slug = "missing")
            fail("expected SlugNotFound")
        } catch (e: OwlQuestionnaireError) {
            assertTrue(e is OwlQuestionnaireError.SlugNotFound)
        }
    }

    @Test
    fun `fetch through Owl with force passes force=true`() = runBlocking {
        http.fetchResponse = HttpResponse(200, eligibleBody)
        configure()
        Owl.fetchQuestionnaire(slug = "nps", force = true)
        assertTrue(http.fetchRequests().single().url.toString().contains("force=true"))
    }

    // ---- save through Owl: telemetry only on the submit flip ----

    @Test
    fun `save with was_submitted emits questionnaire_submitted exactly once`() = runBlocking {
        http.saveDefault =
            HttpResponse(200, """{"id":"resp-1","created_at":"2026-06-04T00:00:00.000Z","was_submitted":true}""")
        configure()

        val receipt = Owl.saveQuestionnaireResponse(
            slug = "nps",
            answers = mapOf("n" to OwlQuestionnaireAnswerValue.NpsValue(9)),
            isComplete = true,
        )
        assertEquals("resp-1", receipt.id)
        assertTrue(receipt.wasSubmitted)

        drainAndPoll { http.eventsWithMessage("sdk:questionnaire_submitted").isNotEmpty() }
        val emitted = http.eventsWithMessage("sdk:questionnaire_submitted")
        assertEquals(1, emitted.size)
        assertEquals("nps", emitted.first().getJSONObject("custom_attributes").getString("slug"))
    }

    @Test
    fun `draft save (was_submitted false) does NOT emit questionnaire_submitted`() = runBlocking {
        http.saveDefault =
            HttpResponse(200, """{"id":"resp-2","created_at":"2026-06-04T00:00:00.000Z","was_submitted":false}""")
        configure()

        val receipt = Owl.saveQuestionnaireResponse(
            slug = "nps",
            answers = mapOf("n" to OwlQuestionnaireAnswerValue.NpsValue(5)),
            isComplete = false,
        )
        assertFalse(receipt.wasSubmitted)

        // Drain the stream; the submitted event must never appear.
        drainAndPoll { http.eventsWithMessage("sdk:session_started").isNotEmpty() }
        Thread.sleep(100)
        assertTrue(http.eventsWithMessage("sdk:questionnaire_submitted").isEmpty())

        // The save still went out with is_complete=false.
        val body = JSONObject(http.saveRequests().single().body!!.toString(Charsets.UTF_8))
        assertFalse(body.getBoolean("is_complete"))
    }

    @Test
    fun `save through Owl re-throws InvalidAnswers on 400`() = runBlocking {
        http.saveDefault = HttpResponse(400, "bad answers")
        configure()
        try {
            Owl.saveQuestionnaireResponse(
                slug = "nps",
                answers = mapOf("n" to OwlQuestionnaireAnswerValue.NpsValue(9)),
                isComplete = true,
            )
            fail("expected InvalidAnswers")
        } catch (e: OwlQuestionnaireError) {
            assertTrue(e is OwlQuestionnaireError.InvalidAnswers)
            assertEquals("bad answers", (e as OwlQuestionnaireError.InvalidAnswers).detail)
        }
    }

    // ---- dismiss through Owl ----

    @Test
    fun `dismiss through Owl returns dismissed_at and emits dismissed event`() = runBlocking {
        configure()
        val date = Owl.dismissQuestionnaires()
        assertNull(null) // placeholder so the date is observed below
        assertTrue("dismissed_at should parse to a non-null Date", date.time > 0)

        // The user_id on the dismiss body is the resolved anon identity.
        val dismissReq = http.requests.single { it.url.path.endsWith("/v1/questionnaires/dismiss") }
        val body = JSONObject(dismissReq.body!!.toString(Charsets.UTF_8))
        assertEquals(IdentityStore(context).anonymousId(), body.getString("user_id"))

        drainAndPoll { http.eventsWithMessage("sdk:questionnaire_dismissed").isNotEmpty() }
        assertTrue(http.eventsWithMessage("sdk:questionnaire_dismissed").isNotEmpty())
    }

    // ---- process-local shown-slug dedupe ----

    @Test
    fun `shown slug dedupe marks and clears`() {
        assertFalse(Owl.questionnaireWasShownThisProcess("alpha"))
        Owl.markQuestionnaireShown("alpha")
        assertTrue(Owl.questionnaireWasShownThisProcess("alpha"))
        // A different slug is independent.
        assertFalse(Owl.questionnaireWasShownThisProcess("beta"))
        Owl.debugClearShownQuestionnaires()
        assertFalse(Owl.questionnaireWasShownThisProcess("alpha"))
    }

    // ---- launch / foreground / install accessors ----

    @Test
    fun `launch and foreground accessors are zero before configure and bump after`() {
        // Before configure, shared is null → accessors default to 0 / null.
        assertEquals(0, Owl.launchCount)
        assertEquals(0, Owl.foregroundCount)
        assertNull(Owl.firstLaunchAt)

        configure()

        // configure() calls markConfiguredOnce → launchCount == 1, firstLaunchAt set.
        assertEquals(1, Owl.launchCount)
        assertEquals(0, Owl.foregroundCount)
        val first = Owl.firstLaunchAt
        assertTrue("firstLaunchAt should be a recent epoch-millis", first != null && first > 0)

        // Foreground transitions accumulate via the published shared state.
        OwlQuestionnaireState.shared!!.incrementForeground()
        assertEquals(1, Owl.foregroundCount)
    }
}
