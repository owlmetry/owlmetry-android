package com.owlmetry.android

import org.json.JSONArray
import org.json.JSONObject

/**
 * The outgoing wire model for a single event. The JSON produced by [toJson] is
 * byte-compatible with the Swift SDK's `LogEvent` `Codable` output so the same
 * `/v1/ingest` server accepts both:
 *
 *  - Snake-case keys exactly as Swift's `CodingKeys`.
 *  - Optional (nullable) fields are *omitted* when null, matching Swift's
 *    `Codable` skipping `nil` optionals. Non-nullable fields
 *    (`client_event_id`, `session_id`, `level`, `message`, `environment`,
 *    `is_dev`, `timestamp`) are always present.
 *  - `custom_attributes` is a JSON object; `supported_languages` a JSON array;
 *    `level`/`environment` serialize via their wire string.
 *  - `timestamp` is an ISO-8601 string with fractional seconds + timezone,
 *    matching Swift's `ISO8601DateFormatter` with `.withFractionalSeconds`.
 */
public data class LogEvent(
    public val clientEventId: String,
    public val sessionId: String,
    public val userId: String?,
    public val level: OwlLogLevel,
    public val sourceModule: String?,
    public val message: String,
    public val screenName: String?,
    public val customAttributes: Map<String, String>?,
    public val environment: OwlPlatform,
    public val osVersion: String?,
    public val appVersion: String?,
    public val sdkName: String?,
    public val sdkVersion: String?,
    public val buildNumber: String?,
    public val deviceModel: String?,
    public val locale: String?,
    public val preferredLanguage: String?,
    public val supportedLanguages: List<String>?,
    public val isDev: Boolean,
    public val timestamp: String,
) {
    /**
     * Serialize to a [JSONObject]. Keys are inserted in the same order as
     * Swift's `CodingKeys`; nullable fields are omitted when null. (JSON object
     * key ordering is not semantically significant to the server, but matching
     * Swift keeps diffs and golden tests readable.)
     */
    public fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("client_event_id", clientEventId)
        obj.put("session_id", sessionId)
        userId?.let { obj.put("user_id", it) }
        obj.put("level", level.wire)
        sourceModule?.let { obj.put("source_module", it) }
        obj.put("message", message)
        screenName?.let { obj.put("screen_name", it) }
        customAttributes?.let { obj.put("custom_attributes", JSONObject(it as Map<*, *>)) }
        obj.put("environment", environment.wire)
        osVersion?.let { obj.put("os_version", it) }
        appVersion?.let { obj.put("app_version", it) }
        sdkName?.let { obj.put("sdk_name", it) }
        sdkVersion?.let { obj.put("sdk_version", it) }
        buildNumber?.let { obj.put("build_number", it) }
        deviceModel?.let { obj.put("device_model", it) }
        locale?.let { obj.put("locale", it) }
        preferredLanguage?.let { obj.put("preferred_language", it) }
        supportedLanguages?.let { obj.put("supported_languages", JSONArray(it)) }
        obj.put("is_dev", isDev)
        obj.put("timestamp", timestamp)
        return obj
    }

    /** Convenience: the serialized JSON as a String. */
    public fun toJsonString(): String = toJson().toString()

    public companion object {
        /**
         * Parse a [LogEvent] back from a [JSONObject] produced by [toJson]. This
         * is the inverse used by the [OfflineQueue] to round-trip events through
         * disk, mirroring Swift's `Codable` decode of `[LogEvent]` from the
         * persisted offline file.
         *
         * Required fields (`client_event_id`, `session_id`, `level`, `message`,
         * `environment`, `is_dev`, `timestamp`) throw [org.json.JSONException]
         * when missing; optional fields default to null when absent — matching
         * the omit-when-null encoding in [toJson]. Unknown `level`/`environment`
         * wire strings fall back to [OwlLogLevel.INFO] / [OwlPlatform.ANDROID]
         * rather than crashing a flush over a forward-compat value.
         */
        public fun fromJson(obj: JSONObject): LogEvent {
            return LogEvent(
                clientEventId = obj.getString("client_event_id"),
                sessionId = obj.getString("session_id"),
                userId = obj.optStringOrNull("user_id"),
                level = levelFromWire(obj.getString("level")),
                sourceModule = obj.optStringOrNull("source_module"),
                message = obj.getString("message"),
                screenName = obj.optStringOrNull("screen_name"),
                customAttributes = obj.optJSONObject("custom_attributes")?.let { stringMap(it) },
                environment = platformFromWire(obj.getString("environment")),
                osVersion = obj.optStringOrNull("os_version"),
                appVersion = obj.optStringOrNull("app_version"),
                sdkName = obj.optStringOrNull("sdk_name"),
                sdkVersion = obj.optStringOrNull("sdk_version"),
                buildNumber = obj.optStringOrNull("build_number"),
                deviceModel = obj.optStringOrNull("device_model"),
                locale = obj.optStringOrNull("locale"),
                preferredLanguage = obj.optStringOrNull("preferred_language"),
                supportedLanguages = obj.optJSONArray("supported_languages")?.let { stringList(it) },
                isDev = obj.getBoolean("is_dev"),
                timestamp = obj.getString("timestamp"),
            )
        }

        /** Parse a JSON array of event objects. Used to reload the offline queue. */
        public fun listFromJson(arr: JSONArray): List<LogEvent> {
            val out = ArrayList<LogEvent>(arr.length())
            for (i in 0 until arr.length()) {
                out.add(fromJson(arr.getJSONObject(i)))
            }
            return out
        }

        private fun levelFromWire(wire: String): OwlLogLevel =
            OwlLogLevel.entries.firstOrNull { it.wire == wire } ?: OwlLogLevel.INFO

        private fun platformFromWire(wire: String): OwlPlatform =
            OwlPlatform.entries.firstOrNull { it.wire == wire } ?: OwlPlatform.ANDROID

        private fun stringMap(obj: JSONObject): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                out[key] = obj.getString(key)
            }
            return out
        }

        private fun stringList(arr: JSONArray): List<String> {
            val out = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) out.add(arr.getString(i))
            return out
        }

        /** `optString` returns "" for a missing key; we want a true null. */
        private fun JSONObject.optStringOrNull(key: String): String? =
            if (has(key) && !isNull(key)) getString(key) else null
    }
}

/**
 * The `/v1/ingest` request envelope: `{ "bundle_id": ..., "events": [...] }`.
 * Mirrors Swift's `IngestRequestBody`.
 */
public data class IngestRequestBody(
    public val bundleId: String,
    public val events: List<LogEvent>,
) {
    public fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("bundle_id", bundleId)
        val arr = JSONArray()
        for (event in events) {
            arr.put(event.toJson())
        }
        obj.put("events", arr)
        return obj
    }

    public fun toJsonString(): String = toJson().toString()
}
