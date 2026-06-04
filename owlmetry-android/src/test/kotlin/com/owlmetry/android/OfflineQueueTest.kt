package com.owlmetry.android

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * [OfflineQueue] persistence + drain semantics, mirroring the Swift
 * `OfflineQueue` actor: enqueue/drain, the 10 000-event cap (oldest dropped),
 * and that explicitly-persisted events reload from disk in a fresh instance.
 *
 * Tests drive `persistNow()` / `drain()` (synchronous writes) rather than the
 * 1-second debounce, so they're deterministic. Robolectric for real org.json +
 * file I/O.
 */
@RunWith(RobolectricTestRunner::class)
class OfflineQueueTest {

    private lateinit var dir: File
    private val scope = CoroutineScope(Job())

    @Before
    fun setUp() {
        dir = File.createTempFile("owl-offline", "").let {
            it.delete()
            it.mkdirs()
            it
        }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun event(id: String) = LogEvent(
        clientEventId = id,
        sessionId = "sess",
        userId = "owl_anon_x",
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

    @Test
    fun `enqueue then drain returns events in order and empties the queue`() = runTest {
        val queue = OfflineQueue(dir, scope)
        queue.enqueue(listOf(event("a"), event("b")))
        queue.enqueue(event("c"))

        assertEquals(3, queue.count())
        assertFalse(queue.isEmpty())

        val drained = queue.drain()
        assertEquals(listOf("a", "b", "c"), drained.map { it.clientEventId })
        assertTrue(queue.isEmpty())
        assertEquals(0, queue.count())
    }

    @Test
    fun `persisted events reload in a fresh instance`() = runTest {
        val queue = OfflineQueue(dir, scope)
        queue.enqueue(listOf(event("p1"), event("p2")))
        queue.persistNow()

        val reloaded = OfflineQueue(dir, scope)
        assertEquals(2, reloaded.count())
        assertEquals(listOf("p1", "p2"), reloaded.drain().map { it.clientEventId })
    }

    @Test
    fun `drain writes the empty state to disk`() = runTest {
        val queue = OfflineQueue(dir, scope)
        queue.enqueue(event("only"))
        queue.persistNow()
        queue.drain() // drain persists the now-empty list

        val reloaded = OfflineQueue(dir, scope)
        assertTrue(reloaded.isEmpty())
    }

    @Test
    fun `over the cap the oldest events are dropped`() = runTest {
        val queue = OfflineQueue(dir, scope)
        // 10_000 cap. Enqueue 10_005; the first 5 should be evicted.
        val batch = (0 until 10_005).map { event("e$it") }
        queue.enqueue(batch)

        assertEquals(10_000, queue.count())
        val drained = queue.drain()
        assertEquals("e5", drained.first().clientEventId)
        assertEquals("e10004", drained.last().clientEventId)
    }

    @Test
    fun `clear removes the backing file`() = runTest {
        val queue = OfflineQueue(dir, scope)
        queue.enqueue(event("x"))
        queue.persistNow()
        queue.clear()

        val reloaded = OfflineQueue(dir, scope)
        assertTrue(reloaded.isEmpty())
    }
}
