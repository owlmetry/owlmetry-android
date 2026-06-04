package com.owlmetry.android

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Deeper [OfflineQueue] behaviors not covered by [OfflineQueueTest]: the
 * empty-batch no-op, the 1-second debounced write (the analog of Swift's
 * `scheduleDiskWrite` → `Task.sleep(1s)`), and graceful recovery when the
 * backing file is corrupt or unparseable. Mirrors the Swift `OfflineQueue` actor.
 *
 * Robolectric for real org.json + file I/O. The debounce test drives the test
 * scheduler's virtual clock so the 1 s timer is exercised deterministically.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class OfflineQueueDeepTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File.createTempFile("owl-offline-deep", "").let { it.delete(); it.mkdirs(); it }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun event(id: String) = LogEvent(
        clientEventId = id,
        sessionId = "sess",
        userId = null,
        level = OwlLogLevel.INFO,
        sourceModule = null,
        message = "m-$id",
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

    /** An empty batch enqueue is a no-op — mirrors Swift's `guard !batch.isEmpty`. */
    @Test
    fun `enqueue of an empty batch leaves the queue empty`() = runTest {
        val queue = OfflineQueue(dir, backgroundScope)
        queue.enqueue(emptyList())
        assertTrue(queue.isEmpty())
        assertEquals(0, queue.count())
    }

    /**
     * A plain `enqueue` schedules a debounced disk write that fires ~1 s later
     * (Swift: `Task.sleep(1_000_000_000)`), NOT immediately. Before the timer
     * elapses, a fresh queue over the same dir sees nothing; after, it reloads.
     *
     * The debounce coroutine runs on `Dispatchers.IO` (real wall-clock), so this
     * uses real time rather than a virtual scheduler — the only honest way to
     * exercise the actual 1 s delay. Polled with a generous ceiling to stay
     * non-flaky.
     */
    @Test
    fun `enqueue debounces the disk write by one second`() = runBlocking {
        val scope = CoroutineScope(Job())
        try {
            val queue = OfflineQueue(dir, scope)
            queue.enqueue(event("debounced"))

            // Shortly after enqueue, the debounced write has NOT happened yet.
            Thread.sleep(200)
            val tooEarly = OfflineQueue(dir, scope)
            assertTrue("write not flushed within 200 ms of enqueue", tooEarly.isEmpty())

            // Poll past the 1 s debounce (ceiling 5 s) until the write lands.
            var reloadedCount = 0
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline) {
                reloadedCount = OfflineQueue(dir, scope).count()
                if (reloadedCount > 0) break
                Thread.sleep(50)
            }
            assertEquals("write flushed after the 1 s debounce", 1, reloadedCount)
            assertEquals("debounced", OfflineQueue(dir, scope).drain().map { it.clientEventId }.single())
        } finally {
            scope.cancel()
        }
    }

    /**
     * A corrupt / unparseable backing file degrades to an empty queue rather
     * than throwing — mirrors Swift's `(try? decode) ?? []`. The SDK must never
     * crash the host app over a malformed offline file.
     */
    @Test
    fun `a corrupt backing file loads as an empty queue`() = runTest {
        // Write garbage where the queue expects a JSON array.
        val owlDir = File(dir, "owlmetry").apply { mkdirs() }
        File(owlDir, "offline_queue.json").writeText("}{ not json at all [[[")

        val queue = OfflineQueue(dir, backgroundScope)
        assertTrue("corrupt file → empty queue, no crash", queue.isEmpty())

        // And it's still usable after recovery.
        queue.enqueue(event("after-recovery"))
        queue.persistNow()
        val reloaded = OfflineQueue(dir, backgroundScope)
        assertEquals(listOf("after-recovery"), reloaded.drain().map { it.clientEventId })
    }

    /**
     * A valid-JSON-but-wrong-shape file (a JSON object, not an array of events)
     * also degrades to empty rather than throwing.
     */
    @Test
    fun `a wrong-shape json file loads as an empty queue`() = runTest {
        val owlDir = File(dir, "owlmetry").apply { mkdirs() }
        File(owlDir, "offline_queue.json").writeText("""{"not":"an array"}""")

        val queue = OfflineQueue(dir, backgroundScope)
        assertTrue(queue.isEmpty())
    }

    /**
     * persistNow bypasses the debounce — events are on disk immediately,
     * before any 1 s timer would fire. Mirrors Swift's `persistNow()` →
     * `writeToDisk()` direct call.
     */
    @Test
    fun `persistNow writes synchronously without waiting for the debounce`() = runTest {
        val queue = OfflineQueue(dir, backgroundScope)
        queue.enqueue(event("now"))
        queue.persistNow()
        // No time advanced — the file must already be there.
        val reloaded = OfflineQueue(dir, backgroundScope)
        assertEquals(1, reloaded.count())
    }
}
