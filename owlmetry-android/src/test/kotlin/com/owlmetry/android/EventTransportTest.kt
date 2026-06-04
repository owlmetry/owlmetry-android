package com.owlmetry.android

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
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
import java.io.File
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.GZIPInputStream
import org.json.JSONObject

/**
 * [EventTransport] batching / retry / gzip / offline behavior, mirroring the
 * Swift `EventTransport` actor. Uses a fake [HttpClient] (capturing requests +
 * returning programmable responses) and a fake [Reachability] to drive the
 * offline path — the analog of injecting a `URLSession` + `NetworkMonitor` into
 * the Swift transport.
 *
 * Robolectric for real org.json + filesystem (the offline queue). The flush
 * loop's 5 s timer is not exercised here; we call `flush` / `flushAll` / methods
 * directly so behavior is deterministic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class EventTransportTest {

    /** Fake HTTP client: records every request, replies from a script. */
    private class FakeHttpClient : HttpClient {
        val requests = CopyOnWriteArrayList<HttpRequest>()
        /** Per-call responses; when exhausted, falls back to [default]. */
        val scripted = ArrayDeque<Any>() // HttpResponse or Throwable
        var default: HttpResponse = HttpResponse(200, """{"accepted":1,"rejected":0}""")

        override fun execute(request: HttpRequest): HttpResponse {
            requests.add(request)
            return when (val next = scripted.removeFirstOrNull()) {
                null -> default
                is HttpResponse -> next
                is Throwable -> throw next
                else -> default
            }
        }
    }

    private class FakeReachability(@Volatile var connected: Boolean = true) : Reachability {
        override val isConnected: Boolean get() = connected
    }

    private lateinit var dir: File
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        dir = File.createTempFile("owl-tx", "").let { it.delete(); it.mkdirs(); it }
        scope = CoroutineScope(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun transport(
        http: HttpClient,
        reachability: Reachability,
        compression: Boolean = false,
        scope: CoroutineScope,
        ioDispatcher: CoroutineDispatcher,
    ) = EventTransport(
        endpoint = URL("https://ingest.example.com"),
        apiKey = "owl_client_abc123",
        bundleId = "com.example.app",
        compressionEnabled = compression,
        offlineQueue = OfflineQueue(dir, scope),
        networkMonitor = reachability,
        scope = scope,
        httpClient = http,
        ioDispatcher = ioDispatcher,
    )

    private fun event(id: String) = LogEvent(
        clientEventId = id,
        sessionId = "sess",
        userId = "owl_anon_x",
        level = OwlLogLevel.INFO,
        sourceModule = null,
        message = "m",
        screenName = null,
        customAttributes = null,
        environment = OwlPlatform.ANDROID,
        osVersion = "14",
        appVersion = null,
        sdkName = "owlmetry-android",
        sdkVersion = "0.1.0",
        buildNumber = null,
        deviceModel = "Pixel",
        locale = "en_US",
        preferredLanguage = "en-US",
        supportedLanguages = null,
        isDev = false,
        timestamp = "2026-06-04T00:00:00.000Z",
    )

    @Test
    fun `flush posts buffered events to the ingest endpoint`() = runTest {
        val http = FakeHttpClient()
        val tx = transport(http, FakeReachability(true), scope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler))
        tx.enqueue(event("a"))
        tx.flush()

        assertEquals(1, http.requests.size)
        val req = http.requests.first()
        assertEquals("POST", req.method)
        assertTrue(req.url.toString().endsWith("/v1/ingest"))
        assertEquals("Bearer owl_client_abc123", req.headers["Authorization"])
        assertEquals("application/json", req.headers["Content-Type"])

        val body = JSONObject(String(req.body!!, Charsets.UTF_8))
        assertEquals("com.example.app", body.getString("bundle_id"))
        assertEquals(1, body.getJSONArray("events").length())
    }

    @Test
    fun `enqueue auto-flushes when the buffer reaches the batch size`() = runTest {
        val http = FakeHttpClient()
        // Unconfined transport scope so the auto-flush `scope.launch { flush() }`
        // runs eagerly to its first suspension rather than waiting on a separate
        // advance — we want to assert the *auto* flush fired, not drive it.
        val txScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val tx = transport(
            http,
            FakeReachability(true),
            scope = txScope,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        // 20 = BATCH_SIZE; the 20th enqueue triggers an auto-flush task.
        tx.enqueue((0 until 20).map { event("e$it") })
        testScheduler.advanceUntilIdle()

        assertEquals(1, http.requests.size)
        val body = JSONObject(String(http.requests.first().body!!, Charsets.UTF_8))
        assertEquals(20, body.getJSONArray("events").length())
    }

    @Test
    fun `offline events route to the offline queue and are not sent`() = runTest {
        val http = FakeHttpClient()
        val reach = FakeReachability(connected = false)
        val tx = transport(http, reach, scope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler))
        tx.enqueue(event("a"))
        tx.flush()

        assertEquals("no POST while offline", 0, http.requests.size)

        // Coming back online, a flush drains the offline queue and delivers it.
        reach.connected = true
        tx.flush()
        assertEquals(1, http.requests.size)
    }

    @Test
    fun `a failed send re-queues the batch offline`() = runTest {
        val http = FakeHttpClient().apply {
            // 5xx on every attempt -> retry exhausted -> failure.
            default = HttpResponse(503, "unavailable")
        }
        val tx = transport(http, FakeReachability(true), scope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler))
        tx.enqueue(event("a"))
        tx.flush()
        testScheduler.advanceUntilIdle() // let backoff delays elapse

        // 5 attempts were made, then the batch was re-queued.
        assertEquals(5, http.requests.size)

        // Next successful flush drains it from the offline queue.
        http.default = HttpResponse(200, """{"accepted":1,"rejected":0}""")
        tx.flush()
        testScheduler.advanceUntilIdle()
        assertTrue("re-delivered after recovery", http.requests.size > 5)
    }

    @Test
    fun `4xx is not retried`() = runTest {
        val http = FakeHttpClient().apply { default = HttpResponse(400, "bad") }
        val tx = transport(http, FakeReachability(true), scope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler))
        tx.enqueue(event("a"))
        tx.flush()
        testScheduler.advanceUntilIdle()

        assertEquals("client error: single attempt, no retry", 1, http.requests.size)
    }

    @Test
    fun `bodies over the threshold are gzip compressed`() = runTest {
        val http = FakeHttpClient()
        val tx = transport(http, FakeReachability(true), compression = true, scope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler))
        // Many events push the JSON body past the 512-byte threshold.
        tx.enqueue((0 until 10).map { event("event-with-a-longish-id-$it") })
        tx.flush()

        val req = http.requests.first()
        assertEquals("gzip", req.headers["Content-Encoding"])
        // The body must actually inflate back to valid ingest JSON.
        val inflated = GZIPInputStream(req.body!!.inputStream()).use { it.readBytes() }
        val json = JSONObject(String(inflated, Charsets.UTF_8))
        assertEquals(10, json.getJSONArray("events").length())
    }

    @Test
    fun `small bodies are not compressed`() = runTest {
        val http = FakeHttpClient()
        val tx = transport(http, FakeReachability(true), compression = true, scope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler))
        tx.enqueue(event("a")) // single small event < 512 bytes
        tx.flush()

        assertNull(http.requests.first().headers["Content-Encoding"])
    }

    @Test
    fun `claimIdentity posts to the claim endpoint with both ids`() = runTest {
        val http = FakeHttpClient()
        val tx = transport(http, FakeReachability(true), scope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler))
        tx.claimIdentity(anonymousId = "owl_anon_111", userId = "real-999")
        testScheduler.advanceUntilIdle()

        val claim = http.requests.firstOrNull { it.url.toString().endsWith("/v1/identity/claim") }
        assertNotNull(claim)
        val body = JSONObject(String(claim!!.body!!, Charsets.UTF_8))
        assertEquals("owl_anon_111", body.getString("anonymous_id"))
        assertEquals("real-999", body.getString("user_id"))
    }

    @Test
    fun `setUserProperties posts to the properties endpoint`() = runTest {
        val http = FakeHttpClient()
        val tx = transport(http, FakeReachability(true), scope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler))
        tx.setUserProperties("real-1", mapOf("plan" to "pro", "seats" to "5"))

        val req = http.requests.first { it.url.toString().endsWith("/v1/identity/properties") }
        val body = JSONObject(String(req.body!!, Charsets.UTF_8))
        assertEquals("real-1", body.getString("user_id"))
        val props = body.getJSONObject("properties")
        assertEquals("pro", props.getString("plan"))
        assertEquals("5", props.getString("seats"))
    }

    @Test
    fun `flushAll drains every buffered event across multiple batches`() = runTest {
        val http = FakeHttpClient()
        val tx = transport(http, FakeReachability(true), scope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler))
        tx.enqueue((0 until 45).map { event("e$it") }) // > 2 * BATCH_SIZE
        // enqueue auto-flushed once at 20; flushAll handles the rest.
        testScheduler.advanceUntilIdle()
        tx.flushAll()
        testScheduler.advanceUntilIdle()

        val totalSent = http.requests
            .filter { it.url.toString().endsWith("/v1/ingest") }
            .sumOf { JSONObject(String(it.body!!, Charsets.UTF_8)).getJSONArray("events").length() }
        assertEquals(45, totalSent)
    }
}
