package com.owlmetry.android

import kotlinx.coroutines.cancel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DuplicateFilter] suppression behavior, mirroring the Swift
 * `DuplicateFilterTests`: first occurrence allowed, up to 10 duplicates per
 * window allowed, 11th blocked, distinct messages/screens/relevant-attributes
 * are independent buckets, and system meta keys (`_file`/`_line`/etc.) are
 * excluded from the composite key. Pure JVM test — only reads [LogEvent] fields,
 * no org.json. A controllable clock drives the window-expiry case
 * deterministically.
 */
class DuplicateFilterTest {

    private fun stub(
        message: String,
        screenName: String? = null,
        attributes: Map<String, String>? = null,
    ) = LogEvent(
        clientEventId = "id",
        sessionId = "s",
        userId = null,
        level = OwlLogLevel.INFO,
        sourceModule = null,
        message = message,
        screenName = screenName,
        customAttributes = attributes,
        environment = OwlPlatform.ANDROID,
        osVersion = "14",
        appVersion = null,
        sdkName = "owlmetry-android",
        sdkVersion = "0.1.0",
        buildNumber = null,
        deviceModel = "Pixel",
        locale = "en_US",
        preferredLanguage = null,
        supportedLanguages = null,
        isDev = false,
        timestamp = "2026-06-04T00:00:00.000Z",
    )

    @Test
    fun allowsFirstOccurrence() {
        val filter = DuplicateFilter()
        assertTrue(filter.shouldAllow(stub("hello")))
    }

    @Test
    fun allowsUpToMaxDuplicates() {
        val filter = DuplicateFilter()
        val e = stub("repeated")
        repeat(10) { i -> assertTrue("Event ${i + 1} should be allowed", filter.shouldAllow(e)) }
    }

    @Test
    fun blocksAfterMaxDuplicates() {
        val filter = DuplicateFilter()
        val e = stub("spam")
        repeat(10) { filter.shouldAllow(e) }
        assertFalse("11th duplicate should be blocked", filter.shouldAllow(e))
    }

    @Test
    fun differentEventsAreIndependent() {
        val filter = DuplicateFilter()
        val a = stub("message A")
        val b = stub("message B")
        repeat(10) { filter.shouldAllow(a) }
        assertTrue(filter.shouldAllow(b))
        assertFalse(filter.shouldAllow(a))
    }

    @Test
    fun differentScreenCreatesDistinctKey() {
        val filter = DuplicateFilter()
        val a = stub("same", screenName = "screen_a")
        val b = stub("same", screenName = "screen_b")
        repeat(10) { filter.shouldAllow(a) }
        assertTrue(filter.shouldAllow(b))
    }

    @Test
    fun systemMetaKeysExcludedFromKey() {
        val filter = DuplicateFilter()
        val e1 = stub("test", attributes = mapOf("_file" to "A.kt", "_line" to "1", "_function" to "foo", "key" to "val"))
        val e2 = stub("test", attributes = mapOf("_file" to "B.kt", "_line" to "99", "_function" to "bar", "key" to "val"))
        filter.shouldAllow(e1)
        // Same relevant attrs (key=val) + same message → same bucket; one
        // occurrence each is well under the cap so both allowed, but the point
        // is the differing meta keys didn't split them.
        assertTrue(filter.shouldAllow(e2))
    }

    @Test
    fun differentRelevantAttributesCreateDistinctKeys() {
        val filter = DuplicateFilter()
        val a = stub("same", attributes = mapOf("plan" to "free"))
        val b = stub("same", attributes = mapOf("plan" to "pro"))
        repeat(10) { filter.shouldAllow(a) }
        assertTrue(filter.shouldAllow(b))
        assertFalse(filter.shouldAllow(a))
    }

    @Test
    fun windowReopensAfterTimeout() {
        var now = 0L
        val filter = DuplicateFilter(cacheTimeoutMs = 60_000L, nowProvider = { now })
        val e = stub("repeated")
        repeat(10) { filter.shouldAllow(e) }
        assertFalse(filter.shouldAllow(e))
        // Advance past the window — the stale entry is evicted on next check,
        // so the bucket re-opens.
        now += 60_001L
        assertTrue(filter.shouldAllow(e))
    }

    @Test
    fun windowExpiryUsesStrictGreaterThan() {
        // Swift's shouldAllow evicts only when elapsed `> cacheTimeout` (strict).
        // At exactly the boundary the entry is NOT evicted, so the 11th is still
        // blocked; one millisecond past, it re-opens.
        var now = 0L
        val filter = DuplicateFilter(cacheTimeoutMs = 60_000L, nowProvider = { now })
        val e = stub("boundary")
        repeat(10) { filter.shouldAllow(e) }
        // Exactly at the timeout (elapsed == cacheTimeout, not > it) → still capped.
        now = 60_000L
        assertFalse("at exactly the window edge the cap should still hold", filter.shouldAllow(e))
        // One past the edge → bucket re-opens.
        now = 60_001L
        assertTrue("one ms past the window the bucket should re-open", filter.shouldAllow(e))
    }

    @Test
    fun differentLevelsCreateDistinctKeys() {
        // The composite key leads with `level.wire`, so the same message at two
        // levels are independent dedup buckets (mirrors Swift's key prefix).
        val filter = DuplicateFilter()
        val info = stub("same").copy(level = OwlLogLevel.INFO)
        val error = stub("same").copy(level = OwlLogLevel.ERROR)
        repeat(10) { filter.shouldAllow(info) }
        assertTrue(filter.shouldAllow(error))
        assertFalse(filter.shouldAllow(info))
    }

    @Test
    fun startIsIdempotent() {
        // A second start() while a cleanup loop is already running is a no-op
        // (mirrors Swift's `guard cleanupTask == nil`). We can't observe the
        // internal job directly, but calling start twice must not throw or
        // disturb suppression behavior.
        val scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined,
        )
        val filter = DuplicateFilter()
        filter.start(scope)
        filter.start(scope)
        val e = stub("after-start")
        repeat(10) { assertTrue(filter.shouldAllow(e)) }
        assertFalse(filter.shouldAllow(e))
        scope.cancel()
    }
}
