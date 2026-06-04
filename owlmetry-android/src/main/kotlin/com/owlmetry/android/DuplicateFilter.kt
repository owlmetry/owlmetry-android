package com.owlmetry.android

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Suppresses runaway duplicate events. Mirrors the Swift `DuplicateFilter`
 * actor: within a rolling [cacheTimeoutMs] window, at most
 * [maxDuplicatesPerWindow] events sharing the same composite key are allowed;
 * the rest are dropped. The window timestamp is anchored on the *first*
 * occurrence (not refreshed on each hit), so a burst of identical events is
 * capped to 10 per minute and then re-opens after the minute elapses.
 *
 * Swift uses an `actor` for serialization plus a 60s `cleanupTask` that evicts
 * stale keys. Kotlin has no actor type; the equivalent is a plain monitor lock
 * (`synchronized`) guarding the map — [shouldAllow] is a synchronous critical
 * section, exactly the serialization the Swift actor provides — plus a cleanup
 * coroutine launched on the SDK scope. The composite key is byte-identical to
 * Swift's: `level|message|screenName|sortedRelevantAttributes`, where
 * "relevant" attributes exclude the system meta keys
 * ([EventBuilder.systemMetaKeys]) so `_file`/`_line`/etc. never split otherwise
 * identical events into distinct dedup buckets.
 */
internal class DuplicateFilter(
    private val cacheTimeoutMs: Long = 60_000L,
    private val maxDuplicatesPerWindow: Int = 10,
    private val nowProvider: () -> Long = { System.currentTimeMillis() },
) {
    private data class Entry(var count: Int, val timestamp: Long)

    private val lock = Any()
    private val recentLogs = HashMap<String, Entry>()
    private var cleanupJob: Job? = null

    /**
     * Start the periodic eviction loop on [scope]. Idempotent — a second call
     * while a loop is already running is a no-op (matches Swift's
     * `guard cleanupTask == nil`). The loop sleeps 60s then sweeps; cancelled
     * when the SDK scope is cancelled on reset.
     */
    fun start(scope: CoroutineScope) {
        synchronized(lock) {
            if (cleanupJob != null) return
            cleanupJob = scope.launch {
                while (isActive) {
                    delay(cacheTimeoutMs)
                    cleanup()
                }
            }
        }
    }

    /**
     * `true` if [event] should be forwarded, `false` if it's a duplicate over
     * the per-window cap. Synchronous + lock-guarded so concurrent log
     * coroutines see a consistent count, mirroring the Swift actor's
     * serialization of `shouldAllow`.
     */
    fun shouldAllow(event: LogEvent): Boolean {
        synchronized(lock) {
            val key = compositeKey(event)
            val now = nowProvider()
            val existing = recentLogs[key]

            if (existing != null) {
                if (now - existing.timestamp > cacheTimeoutMs) {
                    recentLogs.remove(key)
                } else if (existing.count >= maxDuplicatesPerWindow) {
                    return false
                }
            }

            val current = recentLogs[key]
            recentLogs[key] = Entry(
                count = (current?.count ?: 0) + 1,
                // Anchor on the first occurrence's timestamp (Swift reuses
                // `existing.timestamp` when present), so the window doesn't slide
                // forward on every hit.
                timestamp = current?.timestamp ?: now,
            )
            return true
        }
    }

    private fun cleanup() {
        synchronized(lock) {
            val now = nowProvider()
            val it = recentLogs.entries.iterator()
            while (it.hasNext()) {
                val entry = it.next()
                if (now - entry.value.timestamp >= cacheTimeoutMs) it.remove()
            }
        }
    }

    private fun compositeKey(event: LogEvent): String {
        var relevantAttributes = ""
        val attributes = event.customAttributes
        if (attributes != null) {
            val relevantKeys = attributes.keys
                .filter { it !in EventBuilder.systemMetaKeys }
                .sorted()
            if (relevantKeys.isNotEmpty()) {
                relevantAttributes = relevantKeys.joinToString("|") { "$it:${attributes[it] ?: ""}" }
            }
        }
        return "${event.level.wire}|${event.message}|${event.screenName ?: ""}|$relevantAttributes"
    }
}
