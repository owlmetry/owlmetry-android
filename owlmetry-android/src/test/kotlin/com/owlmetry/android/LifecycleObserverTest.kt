package com.owlmetry.android

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [LifecycleObserver] behavior, mirroring the Swift `LifecycleObserver`:
 *  - ON_STOP (background) flushes the transport (`flushAll`) so buffered events
 *    reach the server;
 *  - the first ON_START (cold launch) is suppressed — it's already represented
 *    by `sdk:session_started` — so it does not emit `sdk:app_foregrounded`;
 *  - a subsequent ON_START (real foreground return) does emit
 *    `sdk:app_foregrounded`.
 *
 * Drives a real [EventTransport] over a recording [HttpClient] + a fake
 * [LifecycleOwner] (`LifecycleRegistry`) so the foreground/background callbacks
 * fire deterministically under the test scheduler. The `sdk:app_foregrounded` /
 * `sdk:app_backgrounded` events route through the live `Owl.log` pipeline, so
 * the SDK is configured first.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LifecycleObserverTest {

    private class FakeHttpClient : HttpClient {
        val requests = CopyOnWriteArrayList<HttpRequest>()
        override fun execute(request: HttpRequest): HttpResponse {
            requests.add(request)
            return HttpResponse(200, """{"accepted":1,"rejected":0}""")
        }

        fun ingestCount() = requests.count { it.url.toString().endsWith("/v1/ingest") }
    }

    private class FakeReachability(@Volatile var connected: Boolean = true) : Reachability {
        override val isConnected: Boolean get() = connected
    }

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File.createTempFile("owl-lifecycle", "").let { it.delete(); it.mkdirs(); it }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun transport(
        http: HttpClient,
        scope: CoroutineScope,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        offlineQueue: OfflineQueue = OfflineQueue(dir, scope),
        reachability: Reachability = FakeReachability(true),
    ) = EventTransport(
        endpoint = URL("https://ingest.example.com"),
        apiKey = "owl_client_abc",
        bundleId = "com.example.app",
        compressionEnabled = false,
        offlineQueue = offlineQueue,
        networkMonitor = reachability,
        scope = scope,
        httpClient = http,
        ioDispatcher = dispatcher,
    )

    private fun event(id: String) = LogEvent(
        clientEventId = id,
        sessionId = "session-1",
        userId = "user-1",
        level = OwlLogLevel.INFO,
        sourceModule = null,
        message = "buffered",
        screenName = null,
        customAttributes = null,
        environment = OwlPlatform.ANDROID,
        osVersion = "14",
        appVersion = "1.0.0",
        sdkName = OwlmetryVersion.NAME,
        sdkVersion = OwlmetryVersion.CURRENT,
        buildNumber = null,
        deviceModel = "Pixel",
        locale = null,
        preferredLanguage = null,
        supportedLanguages = null,
        isDev = false,
        timestamp = "2024-01-01T00:00:00.000Z",
    )

    @Test
    fun `onStop flushes buffered events`() = runTest(UnconfinedTestDispatcher()) {
        val http = FakeHttpClient()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val tx = transport(http, backgroundScope, dispatcher)
        tx.enqueue(event("e1"))
        advanceUntilIdle()
        assertEquals("nothing should flush before ON_STOP", 0, http.ingestCount())

        val owner = FakeLifecycleOwner()
        val observer = LifecycleObserver(transport = tx, scope = backgroundScope, lifecycleOwner = owner)
        observer.start()

        // Drive a foreground→background transition: STARTED (ON_START) then back
        // to CREATED (ON_STOP). The ON_STOP handler runs flushAll().
        owner.moveTo(Lifecycle.State.STARTED)
        advanceUntilIdle()
        owner.moveTo(Lifecycle.State.CREATED)
        advanceUntilIdle()

        assertTrue("ON_STOP must flush the buffered event", http.ingestCount() >= 1)
    }

    @Test
    fun `onStop persists the buffer to disk when offline`() = runTest(UnconfinedTestDispatcher()) {
        // Offline: flushAll() can't deliver, so the buffered event routes to the
        // offline queue, and persistBufferToDisk() forces a durable write. This
        // is Swift's watchOS / no-background-grant strategy — the disk is the
        // backstop after ON_STOP.
        val http = FakeHttpClient()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val offline = OfflineQueue(dir, backgroundScope)
        val tx = transport(http, backgroundScope, dispatcher, offlineQueue = offline, reachability = FakeReachability(false))
        tx.enqueue(event("e1"))
        advanceUntilIdle()

        val owner = FakeLifecycleOwner()
        val observer = LifecycleObserver(transport = tx, scope = backgroundScope, lifecycleOwner = owner)
        observer.start()
        owner.moveTo(Lifecycle.State.STARTED)
        advanceUntilIdle()
        owner.moveTo(Lifecycle.State.CREATED)
        advanceUntilIdle()

        // Nothing reached the server (offline)…
        assertEquals("nothing should reach the server while offline", 0, http.ingestCount())
        // …but the buffered event survived on the offline queue (flushAll's
        // handleUndelivered path); persistBufferToDisk drained the live buffer.
        assertTrue("ON_STOP must persist buffered events to the offline queue", offline.count() >= 1)
    }

    @Test
    fun `stop deregisters so subsequent transitions do not flush`() = runTest(UnconfinedTestDispatcher()) {
        val http = FakeHttpClient()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val tx = transport(http, backgroundScope, dispatcher)

        val owner = FakeLifecycleOwner()
        val observer = LifecycleObserver(transport = tx, scope = backgroundScope, lifecycleOwner = owner)
        observer.start()
        observer.stop()

        // Buffer an event, then drive a background transition. The observer is
        // deregistered, so its ON_STOP handler must not run flushAll().
        tx.enqueue(event("after-stop"))
        owner.moveTo(Lifecycle.State.STARTED)
        advanceUntilIdle()
        owner.moveTo(Lifecycle.State.CREATED)
        advanceUntilIdle()

        assertEquals("a stopped observer must not flush on background", 0, http.ingestCount())
    }

    @Test
    fun `first onStart is suppressed, second emits app_foregrounded`() {
        // Plain (non-runTest) test: the `app_foregrounded` events route through
        // the live `Owl` pipeline (real Dispatchers.Default scope), so a test
        // scheduler would never advance it — drive the observer callbacks
        // directly and poll on the wall clock, exactly like OwlLoggingTest.
        val http = FakeHttpClient()
        Owl.resetForTesting()
        Owl.httpClientOverrideForTesting = http
        try {
            Owl.configure(
                context = androidx.test.core.app.ApplicationProvider.getApplicationContext(),
                endpoint = "https://ingest.example.com",
                apiKey = "owl_client_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                compressionEnabled = false,
                networkTrackingEnabled = false,
                consoleLogging = false,
                attributionEnabled = false,
            )

            val owner = FakeLifecycleOwner()
            // The flush scope is irrelevant here — these assertions exercise only
            // onStart, which routes through the live Owl pipeline, not the scope.
            val observer = LifecycleObserver(
                transport = Owl.transport!!,
                scope = CoroutineScope(Dispatchers.Default),
                lifecycleOwner = owner,
            )

            // First ON_START (cold launch) — suppressed. Drain via setUser and
            // confirm no app_foregrounded event reached the server.
            observer.onStart(owner)
            Owl.setUser("real-user-1-${System.nanoTime()}")
            pollUntil(5_000) { http.requests.any { it.url.toString().endsWith("/v1/identity/claim") } }
            assertEquals(
                "first ON_START must not emit app_foregrounded",
                0,
                http.requests.count {
                    it.url.toString().endsWith("/v1/ingest") && bodyHasMessage(it, "sdk:app_foregrounded")
                },
            )

            // Second ON_START (real foreground return) → emit app_foregrounded.
            observer.onStart(owner)
            Owl.setUser("real-user-2-${System.nanoTime()}")
            pollUntil(5_000) {
                http.requests.any { it.url.toString().endsWith("/v1/ingest") && bodyHasMessage(it, "sdk:app_foregrounded") }
            }

            assertTrue(
                "second ON_START must emit app_foregrounded",
                http.requests.any { it.url.toString().endsWith("/v1/ingest") && bodyHasMessage(it, "sdk:app_foregrounded") },
            )
        } finally {
            Owl.resetForTesting()
            Owl.httpClientOverrideForTesting = null
        }
    }

    private fun bodyHasMessage(req: HttpRequest, message: String): Boolean {
        val text = req.body?.toString(Charsets.UTF_8) ?: return false
        return text.contains("\"message\":\"$message\"")
    }

    private fun pollUntil(timeoutMs: Long, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(20)
        }
    }

    /** A minimal LifecycleOwner backed by a LifecycleRegistry, driven by tests. */
    private class FakeLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
        fun moveTo(state: Lifecycle.State) {
            registry.currentState = state
        }
    }
}
