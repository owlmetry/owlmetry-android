package com.owlmetry.android

import java.util.UUID

/**
 * Tracks a metric operation lifecycle (start → complete / fail / cancel).
 *
 * Created by [Owl.startOperation] — **do not instantiate directly**. The start
 * event is emitted by `startOperation`; calling one of [complete], [fail], or
 * [cancel] on the returned operation emits the matching terminal event, tagged
 * with the same [trackingId] so the server can pair start ↔ terminal and compute
 * the duration server-side as a cross-check against the SDK-stamped
 * `duration_ms`.
 *
 * Mirrors the Swift `OwlOperation` final class. Swift measures elapsed time with
 * `ContinuousClock` (a monotonic clock unaffected by wall-clock adjustments); the
 * Android analog is [System.nanoTime], also monotonic and immune to NTP / user
 * clock changes — the correct choice for a duration that must stay non-negative
 * even if the wall clock jumps during the operation.
 */
public class OwlOperation internal constructor(
    /** The metric slug this operation tracks (already normalized by [Owl.startOperation]). */
    internal val metric: String,
) {
    /**
     * Opaque per-operation id, stamped onto the start event and every terminal
     * event so the server can correlate them. Mirrors Swift's `trackingId`.
     */
    public val trackingId: String = UUID.randomUUID().toString()

    /**
     * Monotonic start instant in nanoseconds ([System.nanoTime]). The analog of
     * Swift's `ContinuousClock.Instant` — used only for elapsed-time deltas,
     * never as an absolute timestamp.
     */
    private val startNanos: Long = System.nanoTime()

    /**
     * Complete the operation successfully. Auto-adds `tracking_id` +
     * `duration_ms`, then emits an **info**-level `metric:<metric>:complete`
     * event. Mirrors Swift `OwlOperation.complete`.
     *
     * Source location ([file]/[function]/[line]) is captured the same way as the
     * public logging methods — callers normally let these default and the SDK
     * derives the call site from the stack.
     */
    public fun complete(
        attributes: Map<String, String?> = emptyMap(),
        file: String = DEFAULT_FILE,
        function: String = DEFAULT_FUNCTION,
        line: Int = 0,
    ) {
        val attrs = LinkedHashMap<String, String?>(attributes)
        attrs["tracking_id"] = trackingId
        attrs["duration_ms"] = durationMs().toString()
        Owl.info("metric:$metric:complete", attributes = attrs, file = file, function = function, line = line)
    }

    /**
     * Record a failed operation. Auto-adds `tracking_id` + `duration_ms` +
     * `error`, then emits an **error**-level `metric:<metric>:fail` event.
     * Mirrors Swift `OwlOperation.fail`.
     */
    public fun fail(
        error: String,
        attributes: Map<String, String?> = emptyMap(),
        file: String = DEFAULT_FILE,
        function: String = DEFAULT_FUNCTION,
        line: Int = 0,
    ) {
        val attrs = LinkedHashMap<String, String?>(attributes)
        attrs["tracking_id"] = trackingId
        attrs["duration_ms"] = durationMs().toString()
        attrs["error"] = error
        Owl.error("metric:$metric:fail", attributes = attrs, file = file, function = function, line = line)
    }

    /**
     * Cancel the operation. Auto-adds `tracking_id` + `duration_ms`, then emits
     * an **info**-level `metric:<metric>:cancel` event. Mirrors Swift
     * `OwlOperation.cancel`.
     */
    public fun cancel(
        attributes: Map<String, String?> = emptyMap(),
        file: String = DEFAULT_FILE,
        function: String = DEFAULT_FUNCTION,
        line: Int = 0,
    ) {
        val attrs = LinkedHashMap<String, String?>(attributes)
        attrs["tracking_id"] = trackingId
        attrs["duration_ms"] = durationMs().toString()
        Owl.info("metric:$metric:cancel", attributes = attrs, file = file, function = function, line = line)
    }

    /**
     * Elapsed time since construction, in whole milliseconds. Monotonic and
     * always non-negative. Mirrors Swift's `durationMs()`, which truncates to
     * whole milliseconds — [System.nanoTime] / 1_000_000 with integer division
     * does the same.
     */
    private fun durationMs(): Long = (System.nanoTime() - startNanos) / 1_000_000L

    internal companion object {
        // Kept in step with Owl's source-location placeholders — Kotlin has no
        // #file/#function literal, so an unspecified call site defaults here and
        // Owl.info/error derives the real frame from the stack.
        private const val DEFAULT_FILE: String = "Unknown"
        private const val DEFAULT_FUNCTION: String = "unknown"
    }
}
