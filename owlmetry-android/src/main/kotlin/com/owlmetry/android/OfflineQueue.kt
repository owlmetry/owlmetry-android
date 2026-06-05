package com.owlmetry.android

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import java.io.File

/**
 * Disk-backed buffer for events that couldn't be delivered (offline, transport
 * failure). The Android analog of the Swift SDK's `OfflineQueue` actor.
 *
 * Swift's `OfflineQueue` is an `actor` serializing all access, persisting to
 * `<AppSupport>/Owlmetry/offline_queue.json` with a 1-second debounced write,
 * capped at 10 000 events (oldest dropped first). We mirror that surface:
 *  - a [Mutex] serializes mutation (the actor analog under the coroutines-only
 *    dependency rule),
 *  - persistence is a JSON array of [LogEvent]s (round-tripped via
 *    [LogEvent.toJson] / [LogEvent.fromJson]) so the file format is identical in
 *    spirit to Swift's `Codable` array,
 *  - writes are debounced 1 s via a coroutine launched on [scope] (analog of
 *    Swift's detached `Task { try? await Task.sleep(...) }`),
 *  - the queue is trimmed to [maxEvents], dropping the oldest.
 *
 * The file lives under `<filesDir>/owlmetry/offline_queue.json` — the closest
 * Android analog of iOS's Application Support directory (private, persistent,
 * not user-visible, not auto-purged like the cache dir).
 */
internal class OfflineQueue(
    directory: File,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val events = ArrayList<LogEvent>()
    private val file: File
    private var pendingWriteJob: Job? = null

    private val maxEvents = 10_000

    init {
        val dir = File(directory, "owlmetry")
        // mkdirs() can throw SecurityException under a restrictive SecurityManager
        // (it returns false for ordinary failures like disk-full). This runs
        // synchronously on the caller's thread inside Owl.configure(), so a failed
        // or forbidden mkdir must degrade to a no-op queue — writeToDisk() and
        // loadFromDisk() are themselves runCatching-guarded — rather than crash
        // configure().
        runCatching { dir.mkdirs() }
        file = File(dir, "offline_queue.json")
        events.addAll(loadFromDisk())
    }

    /** Append a single event. Mirrors Swift's `enqueue(_ event:)`. */
    suspend fun enqueue(event: LogEvent) {
        mutex.withLock {
            events.add(event)
            trimAndScheduleWrite()
        }
    }

    /** Append a batch. Mirrors Swift's `enqueue(_ batch:)`. */
    suspend fun enqueue(batch: List<LogEvent>) {
        if (batch.isEmpty()) return
        mutex.withLock {
            events.addAll(batch)
            trimAndScheduleWrite()
        }
    }

    /**
     * Atomically take everything queued, clearing the queue and flushing the
     * (now empty) state to disk. Mirrors Swift's `drain()`.
     */
    suspend fun drain(): List<LogEvent> = mutex.withLock {
        val drained = ArrayList(events)
        events.clear()
        writeToDisk()
        drained
    }

    /** Current queued count. Mirrors Swift's `count`. */
    suspend fun count(): Int = mutex.withLock { events.size }

    /** Whether the queue is empty. Mirrors Swift's `isEmpty`. */
    suspend fun isEmpty(): Boolean = mutex.withLock { events.isEmpty() }

    /** Force an immediate disk write, bypassing the debounce. For testing. */
    suspend fun persistNow() = mutex.withLock { writeToDisk() }

    /** Delete the backing file and clear in-memory state. For testing. */
    suspend fun clear() = mutex.withLock {
        events.clear()
        pendingWriteJob?.cancel()
        pendingWriteJob = null
        runCatching { file.delete() }
        Unit
    }

    // MARK: - Private (callers hold the mutex)

    private fun trimAndScheduleWrite() {
        if (events.size > maxEvents) {
            // Drop the oldest, matching Swift's `removeFirst(count - maxEvents)`.
            val overflow = events.size - maxEvents
            repeat(overflow) { events.removeAt(0) }
        }
        scheduleDiskWrite()
    }

    private fun scheduleDiskWrite() {
        if (pendingWriteJob?.isActive == true) return
        pendingWriteJob = scope.launch(Dispatchers.IO) {
            delay(1_000) // 1s debounce, matching Swift.
            mutex.withLock { writeToDisk() }
        }
    }

    private fun writeToDisk() {
        runCatching {
            val arr = JSONArray()
            for (event in events) arr.put(event.toJson())
            // Atomic-ish write: tmp file then rename, the analog of Swift's
            // `Data.write(options: .atomic)`.
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(arr.toString())
                tmp.delete()
            }
        }
    }

    private fun loadFromDisk(): List<LogEvent> {
        if (!file.exists()) return emptyList()
        return runCatching {
            LogEvent.listFromJson(JSONArray(file.readText()))
        }.getOrDefault(emptyList())
    }
}
