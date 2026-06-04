package com.owlmetry.android

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Edge-case + error-message parity for the questionnaire surface. Two slices the
 * happy-path tests don't touch:
 *
 *  1. [OwlQuestionnaireError] message strings must mirror Swift's
 *     `errorDescription` byte-for-byte (the CLAUDE.md "mirror Swift names /
 *     semantics" rule — a thrown error must read identically across SDKs).
 *  2. Transport + answer-store edge behaviors: `force=false` omits the param,
 *     `was_submitted` defaults to false when the server omits it, save 404 →
 *     SlugNotFound, dismiss non-2xx → ServerError, a transport throw → a
 *     TransportFailure carrying the message, an eligible envelope with no
 *     `questionnaire` is treated as ineligible (null spec), the answer store's
 *     empty-schema / removal / sort invariants.
 *
 * Robolectric for real org.json. The transport runs against a fake [HttpClient].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class QuestionnaireErrorAndEdgeTest {

    private class FakeHttpClient : HttpClient {
        val requests = CopyOnWriteArrayList<HttpRequest>()
        @Volatile var default: HttpResponse = HttpResponse(200, "{}")
        @Volatile var throwable: Throwable? = null
        override fun execute(request: HttpRequest): HttpResponse {
            requests.add(request)
            throwable?.let { throw it }
            return default
        }
    }

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File.createTempFile("owl-qerr", "").let { it.delete(); it.mkdirs(); it }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun transport(http: HttpClient, scope: CoroutineScope, ioDispatcher: CoroutineDispatcher) =
        EventTransport(
            endpoint = URL("https://ingest.example.com"),
            apiKey = "owl_client_abc",
            bundleId = "com.example.app",
            compressionEnabled = false,
            offlineQueue = OfflineQueue(dir, scope),
            networkMonitor = object : Reachability { override val isConnected = true },
            scope = scope,
            httpClient = http,
            ioDispatcher = ioDispatcher,
        )

    private val deviceInfo = DeviceInfo(
        platform = OwlPlatform.ANDROID,
        osVersion = "14",
        appVersion = "1.2.3",
        buildNumber = "45",
        deviceModel = "Pixel 8",
        locale = "en_US",
        preferredLanguage = "en-US",
        supportedLanguages = emptyList(),
    )

    // ---- Error message parity with Swift's errorDescription ----

    @Test
    fun `error messages mirror Swift errorDescription`() {
        assertEquals(
            "Owlmetry is not configured. Call Owl.configure(...) first.",
            OwlQuestionnaireError.NotConfigured.message,
        )
        assertEquals("Questionnaire slug not found.", OwlQuestionnaireError.SlugNotFound.message)
        assertEquals(
            "Invalid answers: too long",
            OwlQuestionnaireError.InvalidAnswers("too long").message,
        )
        // ServerError with a non-empty body interpolates the body.
        assertEquals(
            "Server returned 503: down for maintenance",
            OwlQuestionnaireError.ServerError(503, "down for maintenance").message,
        )
        // ServerError with a null / empty body drops the suffix.
        assertEquals("Server returned 503", OwlQuestionnaireError.ServerError(503, null).message)
        assertEquals("Server returned 500", OwlQuestionnaireError.ServerError(500, "").message)
        // TransportFailure surfaces the detail verbatim.
        assertEquals("connection reset", OwlQuestionnaireError.TransportFailure("connection reset").message)
    }

    // ---- Transport edge cases ----

    @Test
    fun `fetch without force omits the force query param`() = runTest {
        val http = FakeHttpClient().apply {
            default = HttpResponse(200, """{"eligible":false,"reason":"inactive"}""")
        }
        val tx = transport(http, backgroundScope, StandardTestDispatcher(testScheduler))
        tx.fetchQuestionnaire(slug = "s", userId = null, force = false)
        val url = http.requests.single().url.toString()
        assertFalse(url.contains("force"))
        // userId omitted when null.
        assertFalse(url.contains("user_id="))
    }

    @Test
    fun `fetch maps inactive reason`() = runTest {
        val http = FakeHttpClient().apply {
            default = HttpResponse(200, """{"eligible":false,"reason":"inactive"}""")
        }
        val tx = transport(http, backgroundScope, StandardTestDispatcher(testScheduler))
        val result = (tx.fetchQuestionnaire("s", null) as QuestionnaireFetchOutcome.Success).result
        assertNull(result.questionnaire)
        assertEquals(OwlQuestionnaireIneligibleReason.INACTIVE, result.ineligibleReason)
    }

    @Test
    fun `fetch with eligible false and no reason yields null reason`() = runTest {
        val http = FakeHttpClient().apply { default = HttpResponse(200, """{"eligible":false}""") }
        val tx = transport(http, backgroundScope, StandardTestDispatcher(testScheduler))
        val result = (tx.fetchQuestionnaire("s", null) as QuestionnaireFetchOutcome.Success).result
        assertNull(result.questionnaire)
        assertNull(result.ineligibleReason)
    }

    @Test
    fun `fetch eligible true but missing questionnaire is treated as ineligible`() = runTest {
        // eligible:true but no questionnaire object → spec is null, not a crash.
        val http = FakeHttpClient().apply { default = HttpResponse(200, """{"eligible":true}""") }
        val tx = transport(http, backgroundScope, StandardTestDispatcher(testScheduler))
        val outcome = tx.fetchQuestionnaire("s", null)
        val result = (outcome as QuestionnaireFetchOutcome.Success).result
        assertNull(result.questionnaire)
    }

    @Test
    fun `fetch eligible without in_progress yields null draft`() = runTest {
        val body = """
            { "eligible": true,
              "questionnaire": { "id": "q", "slug": "s", "name": "n", "description": null,
                "schema": { "version": 1, "questions": [
                  { "type": "nps", "id": "n", "title": "t", "required": true } ] } } }
        """.trimIndent()
        val http = FakeHttpClient().apply { default = HttpResponse(200, body) }
        val tx = transport(http, backgroundScope, StandardTestDispatcher(testScheduler))
        val result = (tx.fetchQuestionnaire("s", null) as QuestionnaireFetchOutcome.Success).result
        assertEquals("s", result.questionnaire?.slug)
        assertNull(result.inProgress)
    }

    @Test
    fun `fetch surfaces non-404 server error`() = runTest {
        val http = FakeHttpClient().apply { default = HttpResponse(500, "boom") }
        val tx = transport(http, backgroundScope, StandardTestDispatcher(testScheduler))
        val err = (tx.fetchQuestionnaire("s", null) as QuestionnaireFetchOutcome.Failure).error
        assertTrue(err is OwlQuestionnaireError.ServerError)
        assertEquals(500, (err as OwlQuestionnaireError.ServerError).statusCode)
        assertEquals("boom", err.body)
    }

    @Test
    fun `fetch maps a transport throw to TransportFailure`() = runTest {
        val http = FakeHttpClient().apply { throwable = IOException("network down") }
        val tx = transport(http, backgroundScope, StandardTestDispatcher(testScheduler))
        val err = (tx.fetchQuestionnaire("s", null) as QuestionnaireFetchOutcome.Failure).error
        assertTrue(err is OwlQuestionnaireError.TransportFailure)
        assertEquals("network down", (err as OwlQuestionnaireError.TransportFailure).detail)
    }

    @Test
    fun `save defaults was_submitted to false when the server omits it`() = runTest {
        // No was_submitted key in the body — must decode to false (draft save).
        val http = FakeHttpClient().apply {
            default = HttpResponse(200, """{"id":"r","created_at":"2026-06-04T00:00:00.000Z"}""")
        }
        val tx = transport(http, backgroundScope, StandardTestDispatcher(testScheduler))
        val outcome = tx.saveQuestionnaireResponse(
            slug = "s", userId = "u", sessionId = "sess",
            answers = mapOf("n" to OwlQuestionnaireAnswerValue.NpsValue(7)),
            isComplete = false, deviceInfo = deviceInfo, environment = "android",
            appVersion = "1.0", isDev = false,
        )
        val receipt = (outcome as QuestionnaireSaveOutcome.Success).receipt
        assertEquals("r", receipt.id)
        assertFalse(receipt.wasSubmitted)
    }

    @Test
    fun `save maps 404 to SlugNotFound`() = runTest {
        val http = FakeHttpClient().apply { default = HttpResponse(404, "gone") }
        val tx = transport(http, backgroundScope, StandardTestDispatcher(testScheduler))
        val outcome = tx.saveQuestionnaireResponse(
            slug = "s", userId = null, sessionId = null, answers = emptyMap(),
            isComplete = true, deviceInfo = deviceInfo, environment = "android",
            appVersion = null, isDev = false,
        )
        assertTrue((outcome as QuestionnaireSaveOutcome.Failure).error is OwlQuestionnaireError.SlugNotFound)
    }

    @Test
    fun `save maps 5xx to ServerError`() = runTest {
        val http = FakeHttpClient().apply { default = HttpResponse(503, "unavailable") }
        val tx = transport(http, backgroundScope, StandardTestDispatcher(testScheduler))
        val outcome = tx.saveQuestionnaireResponse(
            slug = "s", userId = null, sessionId = null, answers = emptyMap(),
            isComplete = true, deviceInfo = deviceInfo, environment = "android",
            appVersion = null, isDev = false,
        )
        val err = (outcome as QuestionnaireSaveOutcome.Failure).error
        assertTrue(err is OwlQuestionnaireError.ServerError)
        assertEquals(503, (err as OwlQuestionnaireError.ServerError).statusCode)
    }

    @Test
    fun `save omits null optional fields from the wire body`() = runTest {
        val http = FakeHttpClient().apply {
            default = HttpResponse(200, """{"id":"r","created_at":"2026-06-04T00:00:00.000Z","was_submitted":true}""")
        }
        val tx = transport(http, backgroundScope, StandardTestDispatcher(testScheduler))
        tx.saveQuestionnaireResponse(
            slug = "s", userId = null, sessionId = null, answers = emptyMap(),
            isComplete = false, deviceInfo = deviceInfo, environment = null,
            appVersion = null, isDev = false,
        )
        val body = JSONObject(http.requests.single().body!!.toString(Charsets.UTF_8))
        assertFalse("user_id omitted when null", body.has("user_id"))
        assertFalse("session_id omitted when null", body.has("session_id"))
        assertFalse("app_version omitted when null", body.has("app_version"))
        assertFalse("environment omitted when null", body.has("environment"))
        // Required fields are always present.
        assertEquals("com.example.app", body.getString("bundle_id"))
        assertFalse(body.getBoolean("is_complete"))
        assertEquals("owlmetry-android", body.getString("sdk_name"))
    }

    @Test
    fun `dismiss maps non-2xx to ServerError`() = runTest {
        val http = FakeHttpClient().apply { default = HttpResponse(500, "nope") }
        val tx = transport(http, backgroundScope, StandardTestDispatcher(testScheduler))
        val outcome = tx.submitQuestionnaireDismiss(userId = "u")
        val err = (outcome as QuestionnaireDismissOutcome.Failure).error
        assertTrue(err is OwlQuestionnaireError.ServerError)
        assertEquals(500, (err as OwlQuestionnaireError.ServerError).statusCode)
    }

    @Test
    fun `dismiss maps a transport throw to TransportFailure`() = runTest {
        val http = FakeHttpClient().apply { throwable = IOException("offline") }
        val tx = transport(http, backgroundScope, StandardTestDispatcher(testScheduler))
        val err = (tx.submitQuestionnaireDismiss("u") as QuestionnaireDismissOutcome.Failure).error
        assertTrue(err is OwlQuestionnaireError.TransportFailure)
        assertEquals("offline", (err as OwlQuestionnaireError.TransportFailure).detail)
    }

    // ---- Answer store edge cases ----

    private fun schema(): OwlQuestionnaireSchema = OwlQuestionnaireSchema(
        version = 1,
        questions = listOf(
            OwlQuestionnaireQuestion.Text("t", "Text", null, required = false, placeholder = null, multiline = false),
            OwlQuestionnaireQuestion.Rating("r", "Rating", null, required = false, scale = 5),
            OwlQuestionnaireQuestion.Nps("n", "Nps", null, required = false),
        ),
    )

    @Test
    fun `firstUnansweredIndex on empty schema is zero`() {
        val empty = OwlQuestionnaireSchema(version = 1, questions = emptyList())
        assertEquals(0, OwlQuestionnaireAnswerStore().firstUnansweredIndex(empty))
    }

    @Test
    fun `setting then clearing an answer with null removes it`() {
        val s = schema()
        var store = OwlQuestionnaireAnswerStore().withText("t", "hello").withRating("r", 4).withNps("n", 9)
        assertTrue(store.isAnswered(s.questions[0]))
        assertTrue(store.isAnswered(s.questions[1]))
        assertTrue(store.isAnswered(s.questions[2]))

        store = store.withText("t", null).withRating("r", null).withNps("n", null)
        assertFalse(store.isAnswered(s.questions[0]))
        assertFalse(store.isAnswered(s.questions[1]))
        assertFalse(store.isAnswered(s.questions[2]))
        // Nothing collected once cleared.
        assertTrue(store.collected(s).isEmpty())
    }

    @Test
    fun `withSingle null removes the single-choice answer`() {
        val single = OwlQuestionnaireSchema(
            version = 1,
            questions = listOf(
                OwlQuestionnaireQuestion.SingleChoice(
                    "s", "S", null, required = false,
                    options = listOf(OwlQuestionnaireChoiceOption("a", "A")),
                ),
            ),
        )
        var store = OwlQuestionnaireAnswerStore().withSingle("s", "a")
        assertTrue(store.isAnswered(single.questions[0]))
        store = store.withSingle("s", null)
        assertFalse(store.isAnswered(single.questions[0]))
    }

    @Test
    fun `collected omits everything for an empty store`() {
        assertTrue(OwlQuestionnaireAnswerStore().collected(schema()).isEmpty())
    }

    @Test
    fun `togglingMulti to empty marks the question unanswered`() {
        val multiSchema = OwlQuestionnaireSchema(
            version = 1,
            questions = listOf(
                OwlQuestionnaireQuestion.MultiChoice(
                    "m", "M", null, required = false,
                    options = listOf(OwlQuestionnaireChoiceOption("x", "X"), OwlQuestionnaireChoiceOption("y", "Y")),
                ),
            ),
        )
        var store = OwlQuestionnaireAnswerStore().togglingMulti("m", "x").togglingMulti("m", "y")
        assertTrue(store.isAnswered(multiSchema.questions[0]))
        // Toggle both off → the set is present but empty → unanswered.
        store = store.togglingMulti("m", "x").togglingMulti("m", "y")
        assertFalse(store.isAnswered(multiSchema.questions[0]))
        assertTrue(store.collected(multiSchema).isEmpty())
    }

    @Test
    fun `hydrateDraftAnswers skips JSON-null values`() {
        val q = OwlQuestionnaire.fromJson(
            JSONObject(
                """{ "id": "i", "slug": "s", "name": "n", "schema": { "version": 1, "questions": [
                      { "type": "text", "id": "t", "title": "T", "required": false } ] } }""",
            ),
        )
        val raw = JSONObject("""{ "t": null }""")
        assertTrue(hydrateDraftAnswers(raw, q.schema).isEmpty())
    }
}
