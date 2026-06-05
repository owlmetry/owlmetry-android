package com.owlmetry.android

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
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
 * Second, deeper pass over [EventTransport] — the behaviors not pinned by
 * [EventTransportTest]: the production-critical claim ordering (flush buffered
 * events before POSTing the claim), retry semantics shared by claim/properties,
 * the offline mid-drain remainder path in `flushAll`, backoff timing, the
 * MAX_BUFFER_SIZE overflow eviction, the gzip threshold boundary, and the
 * `/v1/ingest` URL normalization. Mirrors the Swift `EventTransport` actor.
 *
 * Robolectric for real org.json + filesystem (the offline queue). HTTP and the
 * offline queue share the test scheduler so everything is deterministic; the
 * 5 s flush loop timer is never started here — we call methods directly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class EventTransportDeepTest {

    /** Fake HTTP client: records every request, replies from a script. */
    private class FakeHttpClient : HttpClient {
        val requests = CopyOnWriteArrayList<HttpRequest>()
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

        fun ingest() = requests.filter { it.url.toString().endsWith("/v1/ingest") }
        fun claims() = requests.filter { it.url.toString().endsWith("/v1/identity/claim") }
        fun properties() = requests.filter { it.url.toString().endsWith("/v1/identity/properties") }
    }

    private class FakeReachability(@Volatile var connected: Boolean = true) : Reachability {
        override val isConnected: Boolean get() = connected
    }

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File.createTempFile("owl-txd", "").let { it.delete(); it.mkdirs(); it }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun transport(
        http: HttpClient,
        reachability: Reachability,
        compression: Boolean = false,
        endpoint: String = "https://ingest.example.com",
        scope: CoroutineScope,
        ioDispatcher: CoroutineDispatcher,
    ) = EventTransport(
        endpoint = URL(endpoint),
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

    /**
     * Swift's `claimIdentity` calls `await flushAll()` BEFORE POSTing the claim,
     * so the server already has the buffered events when its `UPDATE events`
     * runs. The ingest POST must precede the claim POST in wire order.
     */
    @Test
    fun `claimIdentity flushes buffered events before posting the claim`() = runTest {
        val http = FakeHttpClient()
        val tx = transport(http, FakeReachability(true), scope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler))
        tx.enqueue(event("buffered-1"))
        tx.enqueue(event("buffered-2"))

        tx.claimIdentity(anonymousId = "owl_anon_111", userId = "real-999")
        testScheduler.advanceUntilIdle()

        assertEquals("buffered events flushed", 1, http.ingest().size)
        assertEquals("claim posted", 1, http.claims().size)
        // Ingest must come strictly before the claim in the request log.
        val ingestIdx = http.requests.indexOfFirst { it.url.toString().endsWith("/v1/ingest") }
        val claimIdx = http.requests.indexOfFirst { it.url.toString().endsWith("/v1/identity/claim") }
        assertTrue("ingest($ingestIdx) must precede claim($claimIdx)", ingestIdx < claimIdx)

        // The flushed batch carried both buffered events.
        val body = JSONObject(String(http.ingest().first().body!!, Charsets.UTF_8))
        assertEquals(2, body.getJSONArray("events").length())
    }

    /** A claim with an empty buffer still POSTs the claim (no ingest needed). */
    @Test
    fun `claimIdentity with an empty buffer posts only the claim`() = runTest {
        val http = FakeHttpClient()
        val tx = transport(http, FakeReachability(true), scope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler))
        tx.claimIdentity(anonymousId = "owl_anon_a", userId = "real-b")
        testScheduler.advanceUntilIdle()

        assertEquals(0, http.ingest().size)
        assertEquals(1, http.claims().size)
    }

    /**
     * The claim POST uses the same `performWithRetry` as ingest: a 5xx is
     * retried up to MAX_RETRIES (5). Mirrors Swift routing the claim through
     * `performWithRetry`.
     */
    @Test
    fun `claim retries on 5xx up to the retry limit`() = runTest {
        val http = FakeHttpClient().apply { default = HttpResponse(503, "down") }
        val tx = transport(http, FakeReachability(true), scope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler))
        tx.claimIdentity(anonymousId = "owl_anon_a", userId = "real-b")
        testScheduler.advanceUntilIdle()

        assertEquals("5 claim attempts then give up", 5, http.claims().size)
    }

    /** A 4xx on the claim is a single attempt — client errors don't retry. */
    @Test
    fun `claim does not retry on 4xx`() = runTest {
        val http = FakeHttpClient().apply { default = HttpResponse(403, "forbidden") }
        val tx = transport(http, FakeReachability(true), scope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler))
        tx.claimIdentity(anonymousId = "owl_anon_a", userId = "real-b")
        testScheduler.advanceUntilIdle()

        assertEquals(1, http.claims().size)
    }

    /** setUserProperties retries on 5xx the same way. */
    @Test
    fun `setUserProperties retries on 5xx`() = runTest {
        val http = FakeHttpClient().apply { default = HttpResponse(500, "boom") }
        val tx = transport(http, FakeReachability(true), scope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler))
        tx.setUserProperties("real-1", mapOf("plan" to "pro"))
        testScheduler.advanceUntilIdle()

        assertEquals(5, http.properties().size)
    }

    /** setUserProperties with an empty map still posts (Swift encodes `{}`). */
    @Test
    fun `setUserProperties posts an empty properties object`() = runTest {
        val http = FakeHttpClient()
        val tx = transport(http, FakeReachability(true), scope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler))
        tx.setUserProperties("real-1", emptyMap())

        val req = http.properties().single()
        val body = JSONObject(String(req.body!!, Charsets.UTF_8))
        assertEquals("real-1", body.getString("user_id"))
        assertEquals(0, body.getJSONObject("properties").length())
    }

    /**
     * A transport-layer throw (not an HTTP status) is retried like a 5xx —
     * mirrors Swift's `catch` branch in `performWithRetry`. The batch ends up
     * back on the offline queue after 5 failed attempts.
     */
    @Test
    fun `a thrown transport error is retried and the batch re-queued`() = runTest {
        val http = FakeHttpClient().apply {
            repeat(5) { scripted.add(IOException("connection reset")) }
        }
        val tx = transport(http, FakeReachability(true), scope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler))
        tx.enqueue(event("a"))
        tx.flush()
        testScheduler.advanceUntilIdle()

        assertEquals("5 attempts on a thrown error", 5, http.ingest().size)

        // Recovered: the next flush delivers it from the offline queue.
        http.default = HttpResponse(200, """{"accepted":1,"rejected":0}""")
        tx.flush()
        testScheduler.advanceUntilIdle()
        assertTrue(http.ingest().size > 5)
    }

    /**
     * Going offline mid-`flushAll` pushes the current batch PLUS the entire
     * remaining buffer to the offline queue in one shot, then stops. Mirrors
     * Swift's `let remainder = batch + buffer; buffer.removeAll();
     * handleUndelivered(remainder)`.
     */
    @Test
    fun `flushAll offline mid-drain routes the batch and remainder offline`() = runTest {
        val http = FakeHttpClient()
        val reach = FakeReachability(connected = false) // offline from the start
        val tx = transport(http, reach, scope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler))
        // 45 events = 3 batches' worth, all undelivered while offline.
        tx.enqueue((0 until 45).map { event("e$it") })
        testScheduler.advanceUntilIdle() // auto-flush also short-circuits offline
        tx.flushAll()
        testScheduler.advanceUntilIdle()

        assertEquals("nothing sent while offline", 0, http.ingest().size)

        // Come back online: everything queued offline is delivered.
        reach.connected = true
        tx.flushAll()
        testScheduler.advanceUntilIdle()
        val sent = http.ingest().sumOf {
            JSONObject(String(it.body!!, Charsets.UTF_8)).getJSONArray("events").length()
        }
        assertEquals(45, sent)
    }

    /**
     * persistBufferToDisk moves the in-memory buffer to the offline queue and
     * forces a write, so events survive process death. Mirrors Swift's
     * `persistBufferToDisk`. A fresh OfflineQueue over the same dir reloads them.
     */
    @Test
    fun `persistBufferToDisk moves the buffer to the offline queue on disk`() = runTest {
        val http = FakeHttpClient()
        val scope = backgroundScope
        val tx = transport(http, FakeReachability(true), scope = scope, ioDispatcher = StandardTestDispatcher(testScheduler))
        tx.enqueue(event("survive-1"))
        tx.enqueue(event("survive-2"))

        tx.persistBufferToDisk()
        testScheduler.advanceUntilIdle()

        assertEquals("nothing was sent — just persisted", 0, http.ingest().size)

        // A brand-new queue over the same directory reloads the persisted events.
        val reloaded = OfflineQueue(dir, scope)
        assertEquals(2, reloaded.count())
        assertEquals(
            listOf("survive-1", "survive-2"),
            reloaded.drain().map { it.clientEventId },
        )
    }

    /** persistBufferToDisk on an empty buffer is a no-op (no file, no send). */
    @Test
    fun `persistBufferToDisk with an empty buffer does nothing`() = runTest {
        val http = FakeHttpClient()
        val scope = backgroundScope
        val tx = transport(http, FakeReachability(true), scope = scope, ioDispatcher = StandardTestDispatcher(testScheduler))
        tx.persistBufferToDisk()
        testScheduler.advanceUntilIdle()

        val reloaded = OfflineQueue(dir, scope)
        assertTrue(reloaded.isEmpty())
    }

    /**
     * The retry loop uses exponential backoff (2^attempt s) capped at 30 s.
     * With four sleeps between five attempts (1 + 2 + 4 + 8 = 15 s), no request
     * fires until time is advanced — pin that the backoff actually suspends.
     */
    @Test
    fun `retry backoff suspends between attempts`() = runTest {
        val http = FakeHttpClient().apply { default = HttpResponse(503, "down") }
        val txScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val tx = transport(
            http,
            FakeReachability(true),
            scope = txScope,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        tx.enqueue(event("a"))
        // Drive flush eagerly to its first suspension point.
        txScope.launch { tx.flush() }
        runCurrent()

        // Attempt 1 fired immediately; the 1 s backoff before attempt 2 hasn't elapsed.
        assertEquals("only the first attempt before any backoff", 1, http.ingest().size)

        advanceTimeBy(1_001) // first backoff = 2^0 = 1 s
        runCurrent()
        assertEquals("second attempt after the 1 s backoff", 2, http.ingest().size)

        advanceTimeBy(2_001) // second backoff = 2^1 = 2 s
        runCurrent()
        assertEquals("third attempt after the 2 s backoff", 3, http.ingest().size)

        testScheduler.advanceUntilIdle()
        assertEquals("five attempts total", 5, http.ingest().size)
    }

    /**
     * Buffer overflow drops the OLDEST events past MAX_BUFFER_SIZE (10 000).
     * Mirrors Swift's `buffer.removeFirst(buffer.count - maxBufferSize)`. We
     * enqueue 10 005, flush in batches, and assert the first 5 never ship.
     */
    @Test
    fun `buffer overflow drops the oldest events`() = runTest {
        val http = FakeHttpClient()
        val tx = transport(http, FakeReachability(true), scope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler))
        // Enqueue past the 10_000 cap in a single batch so trimming runs once.
        tx.enqueue((0 until 10_005).map { event("e$it") })
        testScheduler.advanceUntilIdle() // auto-flush + drain everything
        tx.flushAll()
        testScheduler.advanceUntilIdle()

        val sentIds = http.ingest().flatMap { req ->
            val arr = JSONObject(String(req.body!!, Charsets.UTF_8)).getJSONArray("events")
            (0 until arr.length()).map { arr.getJSONObject(it).getString("client_event_id") }
        }.toSet()

        assertEquals("capped at 10_000 delivered events", 10_000, sentIds.size)
        assertFalse("oldest (e0) evicted", sentIds.contains("e0"))
        assertFalse("oldest (e4) evicted", sentIds.contains("e4"))
        assertTrue("e5 survives", sentIds.contains("e5"))
        assertTrue("newest survives", sentIds.contains("e10004"))
    }

    /**
     * The gzip threshold is `>= 512` bytes, applied to the UNCOMPRESSED body.
     * A body of exactly 511 bytes is sent raw; 512 triggers compression. We
     * can't hit the byte exactly with whole events, but we can pin the
     * directional boundary: a tiny single-event body is raw, a large one is gzip.
     */
    @Test
    fun `compression engages only past the 512 byte threshold`() = runTest {
        val http = FakeHttpClient()
        val tx = transport(http, FakeReachability(true), compression = true, scope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler))

        // One small event — body well under 512 bytes → no Content-Encoding.
        tx.enqueue(event("s"))
        tx.flush()
        assertNull("small body sent raw", http.ingest().first().headers["Content-Encoding"])

        // Many events — body well over 512 bytes → gzip.
        tx.enqueue((0 until 15).map { event("event-with-a-fairly-long-identifier-$it") })
        tx.flush()
        assertEquals("large body gzipped", "gzip", http.ingest().last().headers["Content-Encoding"])
    }

    /** With compression disabled, even a large body is never gzipped. */
    @Test
    fun `compression disabled never gzips even large bodies`() = runTest {
        val http = FakeHttpClient()
        val tx = transport(http, FakeReachability(true), compression = false, scope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler))
        tx.enqueue((0 until 15).map { event("event-with-a-fairly-long-identifier-$it") })
        tx.flush()
        assertNull(http.ingest().first().headers["Content-Encoding"])
    }

    /**
     * The ingest URL is the endpoint + `/v1/ingest` with exactly one slash at
     * the boundary, even when the endpoint already ends in a slash. Mirrors
     * Swift's `appendingPathComponent`.
     */
    @Test
    fun `endpoint with a trailing slash still yields one slash before the path`() = runTest {
        val http = FakeHttpClient()
        val tx = transport(
            http,
            FakeReachability(true),
            endpoint = "https://ingest.example.com/",
            scope = backgroundScope,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
        tx.enqueue(event("a"))
        tx.flush()

        assertEquals(
            "https://ingest.example.com/v1/ingest",
            http.ingest().first().url.toString(),
        )
    }

    /**
     * A 2xx body advertising rejections is still treated as success (the batch
     * is NOT re-queued) — mirrors Swift logging the rejection count but
     * returning `true`. The batch must not reappear in the offline queue.
     */
    @Test
    fun `a 2xx with rejected events is still a successful delivery`() = runTest {
        val http = FakeHttpClient().apply {
            default = HttpResponse(200, """{"accepted":0,"rejected":1}""")
        }
        val scope = backgroundScope
        val tx = transport(http, FakeReachability(true), scope = scope, ioDispatcher = StandardTestDispatcher(testScheduler))
        tx.enqueue(event("a"))
        tx.flush()
        testScheduler.advanceUntilIdle()

        assertEquals("single send, not retried", 1, http.ingest().size)
        // Nothing re-queued offline (success despite rejection).
        val queue = OfflineQueue(dir, scope)
        assertTrue("no re-queue on a 2xx", queue.isEmpty())
    }

    /**
     * Crash safety: a single throwing flush tick must NOT kill the periodic flush
     * loop. The loop body guards each `flush()` (rethrowing CancellationException)
     * so one bad tick logs and the loop keeps running for subsequent events —
     * without this, the throw would unwind the `while` loop and stop ALL future
     * periodic flushing for the process lifetime (and reach the scope's
     * exception handler). Here the first tick throws (reachability read fails),
     * and a later event is still delivered on a subsequent tick.
     */
    @Test
    fun `a throwing flush tick does not kill the periodic flush loop`() = runTest {
        val http = FakeHttpClient()
        // isConnected throws on its first read (during the first flush tick that
        // has a buffered event), then behaves. flush() reads isConnected after
        // pulling the batch, so the first tick throws out of flush().
        val flaky = object : Reachability {
            @Volatile
            var calls = 0
            override val isConnected: Boolean
                get() {
                    calls += 1
                    if (calls == 1) throw IllegalStateException("transient reachability failure")
                    return true
                }
        }
        val tx = transport(http, flaky, scope = backgroundScope, ioDispatcher = StandardTestDispatcher(testScheduler))

        tx.enqueue(event("lost-on-throwing-tick"))
        tx.start()

        // Tick 1: the buffered event is pulled, then isConnected throws → the
        // per-tick guard swallows it and the loop survives (this event is lost,
        // which is acceptable — losing one batch is never a crash).
        advanceTimeBy(5_001)
        runCurrent()

        // A new event arrives after the throwing tick.
        tx.enqueue(event("delivered-after-recovery"))

        // Tick 2: isConnected now succeeds → the loop is still alive and delivers.
        advanceTimeBy(5_001)
        runCurrent()

        // Stop the loop so the test can settle (don't advanceUntilIdle a live
        // infinite-delay loop).
        tx.shutdown()
        runCurrent()

        assertTrue("isConnected was read on both ticks", flaky.calls >= 2)
        assertEquals("loop survived the throwing tick and flushed a later event", 1, http.ingest().size)
        val body = JSONObject(String(http.ingest().first().body!!, Charsets.UTF_8))
        assertEquals(
            "delivered-after-recovery",
            body.getJSONArray("events").getJSONObject(0).getString("client_event_id"),
        )
    }
}
