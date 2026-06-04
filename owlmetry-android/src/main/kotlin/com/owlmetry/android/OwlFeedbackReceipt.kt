package com.owlmetry.android

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Wire format POSTed to `POST /v1/feedback`. Internal — public callers go
 * through [Owl.sendFeedback]. Mirrors the Swift `FeedbackRequestBody`: every
 * key is snake_case, and **null fields are omitted** from the JSON body (matching
 * Swift's `JSONEncoder`, which drops `nil` optionals — the server treats a
 * missing key the same as an explicit null).
 */
internal data class FeedbackRequestBody(
    val bundleId: String,
    val message: String,
    val sessionId: String?,
    val userId: String?,
    val submitterName: String?,
    val submitterEmail: String?,
    val appVersion: String?,
    val sdkName: String?,
    val sdkVersion: String?,
    val environment: String?,
    val deviceModel: String?,
    val osVersion: String?,
    val isDev: Boolean,
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        // Required fields are always written.
        obj.put("bundle_id", bundleId)
        obj.put("message", message)
        // Optionals are omitted when null — Swift's encoder drops nil optionals.
        sessionId?.let { obj.put("session_id", it) }
        userId?.let { obj.put("user_id", it) }
        submitterName?.let { obj.put("submitter_name", it) }
        submitterEmail?.let { obj.put("submitter_email", it) }
        appVersion?.let { obj.put("app_version", it) }
        sdkName?.let { obj.put("sdk_name", it) }
        sdkVersion?.let { obj.put("sdk_version", it) }
        environment?.let { obj.put("environment", it) }
        deviceModel?.let { obj.put("device_model", it) }
        osVersion?.let { obj.put("os_version", it) }
        obj.put("is_dev", isDev)
        return obj
    }

    fun toJsonString(): String = toJson().toString()
}

/**
 * Confirmation returned by the server after feedback is accepted. The public
 * analog of the Swift `OwlFeedbackReceipt`. [createdAt] is the server's
 * `created_at` timestamp parsed from ISO-8601; falls back to "now" when the
 * server's value is unparseable (mirrors Swift's `?? Date()`).
 */
public data class OwlFeedbackReceipt(
    public val id: String,
    public val createdAt: Date,
) {
    internal companion object {
        /**
         * Parse the server's `{ id, created_at }` JSON into a receipt. The
         * `created_at` is ISO-8601 with fractional seconds + timezone (the
         * server's wire format, same shape this SDK emits for event timestamps);
         * an unparseable or missing value degrades to [Date] now rather than
         * failing the whole submission — matching Swift's `?? Date()`.
         */
        fun fromJson(json: JSONObject): OwlFeedbackReceipt {
            val id = json.optString("id")
            val createdAtRaw = json.optString("created_at", "")
            val createdAt = parseIso8601(createdAtRaw) ?: Date()
            return OwlFeedbackReceipt(id = id, createdAt = createdAt)
        }

        // ISO-8601 with milliseconds + numeric timezone offset, matching the
        // server's emitted `created_at`. `SimpleDateFormat` is not thread-safe,
        // so confine it to a ThreadLocal — same pattern as EventBuilder.
        private val isoParser: ThreadLocal<SimpleDateFormat> =
            object : ThreadLocal<SimpleDateFormat>() {
                override fun initialValue(): SimpleDateFormat =
                    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }
            }

        // Fallback parser for timestamps without fractional seconds (the server
        // is not guaranteed to always emit milliseconds; tolerate both).
        private val isoParserNoMillis: ThreadLocal<SimpleDateFormat> =
            object : ThreadLocal<SimpleDateFormat>() {
                override fun initialValue(): SimpleDateFormat =
                    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }
            }

        private fun parseIso8601(value: String): Date? {
            if (value.isEmpty()) return null
            return runCatching { isoParser.get()!!.parse(value) }.getOrNull()
                ?: runCatching { isoParserNoMillis.get()!!.parse(value) }.getOrNull()
        }
    }
}

/**
 * Errors surfaced by [Owl.sendFeedback]. The public analog of the Swift
 * `OwlFeedbackError` enum; [message] (via the overridden [Throwable.message])
 * mirrors Swift's `errorDescription` so a thrown error reads identically.
 */
public sealed class OwlFeedbackError(message: String) : Exception(message) {
    /** [Owl.configure] has not been called yet. */
    public object NotConfigured : OwlFeedbackError(
        "Owlmetry is not configured. Call Owl.configure(...) before sending feedback.",
    )

    /** The message parameter was empty or only whitespace. */
    public object EmptyMessage : OwlFeedbackError("Feedback message is empty.")

    /**
     * The server responded with a non-2xx status. [body] is returned verbatim
     * for debugging.
     */
    public data class ServerError(
        public val statusCode: Int,
        public val body: String?,
    ) : OwlFeedbackError(
        if (!body.isNullOrEmpty()) "Server returned $statusCode: $body" else "Server returned $statusCode",
    )

    /** A transport-level failure (network unreachable, invalid response, decode error). */
    public data class TransportFailure(
        public val detail: String,
    ) : OwlFeedbackError(detail)
}
