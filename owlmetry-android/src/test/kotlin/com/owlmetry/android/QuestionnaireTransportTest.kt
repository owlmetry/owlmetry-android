package com.owlmetry.android

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [EventTransport] questionnaire fetch / save / dismiss behavior, mirroring the
 * Swift `EventTransport` questionnaire methods. Uses a fake [HttpClient] to
 * assert the request shape (method, URL, query, body) and decode the typed
 * outcomes. Robolectric for real org.json.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class QuestionnaireTransportTest {

    private class FakeHttpClient : HttpClient {
        val requests = CopyOnWriteArrayList<HttpRequest>()
        var default: HttpResponse = HttpResponse(200, "{}")
        override fun execute(request: HttpRequest): HttpResponse {
            requests.add(request)
            return default
        }
    }

    private class FakeReachability(@Volatile var connected: Boolean = true) : Reachability {
        override val isConnected: Boolean get() = connected
    }

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File.createTempFile("owl-q", "").let { it.delete(); it.mkdirs(); it }
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
            networkMonitor = FakeReachability(true),
            scope = scope,
            httpClient = http,
            ioDispatcher = ioDispatcher,
        )

    private val schemaBody = """
        { "eligible": true,
          "questionnaire": {
            "id": "q1", "slug": "nps", "name": "NPS", "description": null,
            "schema": { "version": 1, "questions": [
              { "type": "nps", "id": "n", "title": "Recommend?", "required": true }
            ] } },
          "in_progress": { "response_id": "r-7", "answers": { "n": 8 } } }
    """.trimIndent()

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

    @Test
    fun `fetch builds a GET with bundle and user query and decodes the envelope`() = runTest {
        val http = FakeHttpClient().apply { default = HttpResponse(200, schemaBody) }
        val tx = transport(http, backgroundScope, StandardTestDispatcher(testScheduler))

        val outcome = tx.fetchQuestionnaire(slug = "nps", userId = "owl_anon_42", force = true)
        assertTrue(outcome is QuestionnaireFetchOutcome.Success)
        val result = (outcome as QuestionnaireFetchOutcome.Success).result
        assertEquals("nps", result.questionnaire?.slug)
        assertEquals("r-7", result.inProgress?.responseId)
        assertEquals(OwlQuestionnaireAnswerValue.NpsValue(8), result.inProgress?.answers?.get("n"))

        val req = http.requests.first()
        assertEquals("GET", req.method)
        assertNull(req.body)
        val url = req.url.toString()
        assertTrue(url.contains("/v1/questionnaires/nps"))
        assertTrue(url.contains("bundle_id=com.example.app"))
        assertTrue(url.contains("user_id=owl_anon_42"))
        assertTrue(url.contains("force=true"))
        assertEquals("Bearer owl_client_abc", req.headers["Authorization"])
    }

    @Test
    fun `fetch surfaces ineligible reason with null questionnaire`() = runTest {
        val http = FakeHttpClient().apply {
            default = HttpResponse(200, """{ "eligible": false, "reason": "already_responded" }""")
        }
        val tx = transport(http, backgroundScope, StandardTestDispatcher(testScheduler))

        val outcome = tx.fetchQuestionnaire(slug = "nps", userId = null)
        val result = (outcome as QuestionnaireFetchOutcome.Success).result
        assertNull(result.questionnaire)
        assertEquals(OwlQuestionnaireIneligibleReason.ALREADY_RESPONDED, result.ineligibleReason)
    }

    @Test
    fun `fetch maps 404 to SlugNotFound`() = runTest {
        val http = FakeHttpClient().apply { default = HttpResponse(404, "not found") }
        val tx = transport(http, backgroundScope, StandardTestDispatcher(testScheduler))
        val outcome = tx.fetchQuestionnaire(slug = "missing", userId = null)
        assertTrue((outcome as QuestionnaireFetchOutcome.Failure).error is OwlQuestionnaireError.SlugNotFound)
    }

    @Test
    fun `save posts the answer set and decodes the receipt`() = runTest {
        val http = FakeHttpClient().apply {
            default = HttpResponse(200, """{ "id": "resp-1", "created_at": "2026-06-04T00:00:00.000Z", "was_submitted": true }""")
        }
        val tx = transport(http, backgroundScope, StandardTestDispatcher(testScheduler))

        val outcome = tx.saveQuestionnaireResponse(
            slug = "nps",
            userId = "owl_anon_42",
            sessionId = "sess-1",
            answers = mapOf("n" to OwlQuestionnaireAnswerValue.NpsValue(8)),
            isComplete = true,
            deviceInfo = deviceInfo,
            environment = "android",
            appVersion = "1.2.3",
            isDev = true,
        )
        val receipt = (outcome as QuestionnaireSaveOutcome.Success).receipt
        assertEquals("resp-1", receipt.id)
        assertTrue(receipt.wasSubmitted)

        val req = http.requests.first()
        assertEquals("POST", req.method)
        assertTrue(req.url.toString().endsWith("/v1/questionnaires/nps/responses"))
        val body = JSONObject(String(req.body!!, Charsets.UTF_8))
        assertEquals("com.example.app", body.getString("bundle_id"))
        assertEquals("owl_anon_42", body.getString("user_id"))
        assertEquals("sess-1", body.getString("session_id"))
        assertEquals(true, body.getBoolean("is_complete"))
        assertEquals(true, body.getBoolean("is_dev"))
        assertEquals("android", body.getString("environment"))
        assertEquals("Pixel 8", body.getString("device_model"))
        assertEquals(8, body.getJSONObject("answers").getInt("n"))
        assertEquals("owlmetry-android", body.getString("sdk_name"))
    }

    @Test
    fun `save maps 400 to InvalidAnswers`() = runTest {
        val http = FakeHttpClient().apply { default = HttpResponse(400, "bad answers") }
        val tx = transport(http, backgroundScope, StandardTestDispatcher(testScheduler))
        val outcome = tx.saveQuestionnaireResponse(
            slug = "nps", userId = null, sessionId = null,
            answers = emptyMap(), isComplete = false,
            deviceInfo = deviceInfo, environment = "android", appVersion = null, isDev = false,
        )
        val err = (outcome as QuestionnaireSaveOutcome.Failure).error
        assertTrue(err is OwlQuestionnaireError.InvalidAnswers)
        assertEquals("bad answers", (err as OwlQuestionnaireError.InvalidAnswers).detail)
    }

    @Test
    fun `dismiss posts bundle and user and decodes dismissed_at`() = runTest {
        val http = FakeHttpClient().apply {
            default = HttpResponse(200, """{ "dismissed_at": "2026-06-04T12:00:00.000Z" }""")
        }
        val tx = transport(http, backgroundScope, StandardTestDispatcher(testScheduler))

        val outcome = tx.submitQuestionnaireDismiss(userId = "owl_anon_42")
        assertTrue(outcome is QuestionnaireDismissOutcome.Success)

        val req = http.requests.first()
        assertEquals("POST", req.method)
        assertTrue(req.url.toString().endsWith("/v1/questionnaires/dismiss"))
        val body = JSONObject(String(req.body!!, Charsets.UTF_8))
        assertEquals("com.example.app", body.getString("bundle_id"))
        assertEquals("owl_anon_42", body.getString("user_id"))
    }
}
