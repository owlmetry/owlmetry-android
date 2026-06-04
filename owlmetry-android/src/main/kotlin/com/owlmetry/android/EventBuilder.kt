package com.owlmetry.android

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Assembles every outgoing [LogEvent]. SDK identity (`sdk_name`/`sdk_version`)
 * is stamped here from [OwlmetryVersion] so call sites never set it. Mirrors the
 * Swift `EventBuilder`:
 *
 *  - `source_module` is `"<fileName>:<function>:<line>"` where `fileName` is the
 *    last path component of `file`.
 *  - Reserved attributes `_file`/`_function`/`_line`/`_connection` are merged on
 *    top of the (trimmed) custom attributes, then the whole map is nulled out if
 *    empty (it never is, given the reserved keys, but the guard mirrors Swift).
 *  - `message` is length-trimmed; custom attributes are value-trimmed.
 *  - `timestamp` is ISO-8601 with millisecond fractional seconds + timezone.
 */
internal object EventBuilder {
    /** Underscore-prefixed reserved keys merged onto every event. */
    val systemMetaKeys: Set<String> = setOf("_file", "_function", "_line", "_connection")

    /**
     * ISO-8601 with milliseconds + numeric timezone offset (e.g.
     * `2026-06-04T12:34:56.789+00:00`). Matches Swift's
     * `ISO8601DateFormatter` with `[.withInternetDateTime, .withFractionalSeconds]`,
     * which emits 3 fractional digits. `XXX` yields `Z` for UTC or `+hh:mm`
     * otherwise — Swift emits `Z` for UTC too. `ThreadLocal` because
     * `SimpleDateFormat` is not thread-safe.
     */
    private val isoFormatter: ThreadLocal<SimpleDateFormat> =
        object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue(): SimpleDateFormat =
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
        }

    private fun formatTimestamp(date: Date): String = isoFormatter.get()!!.format(date)

    fun build(
        message: String,
        level: OwlLogLevel,
        screenName: String?,
        customAttributes: Map<String, String>?,
        userId: String?,
        sessionId: String,
        deviceInfo: DeviceInfo,
        isDev: Boolean,
        networkStatus: String,
        file: String,
        function: String,
        line: Int,
        timestamp: Date = Date(),
    ): LogEvent {
        val lastSlash = file.lastIndexOf('/')
        val fileName = if (lastSlash >= 0) file.substring(lastSlash + 1) else file

        val mergedAttributes = LinkedHashMap<String, String>()
        CustomAttributeTrimmer.trim(customAttributes)?.let { mergedAttributes.putAll(it) }
        mergedAttributes["_file"] = fileName
        mergedAttributes["_function"] = function
        mergedAttributes["_line"] = line.toString()
        mergedAttributes["_connection"] = networkStatus

        return LogEvent(
            clientEventId = UUID.randomUUID().toString(),
            sessionId = sessionId,
            userId = userId,
            level = level,
            sourceModule = "$fileName:$function:$line",
            message = MessageTrimmer.trim(message),
            screenName = screenName,
            customAttributes = if (mergedAttributes.isEmpty()) null else mergedAttributes,
            environment = deviceInfo.platform,
            osVersion = deviceInfo.osVersion,
            appVersion = deviceInfo.appVersion,
            sdkName = OwlmetryVersion.NAME,
            sdkVersion = OwlmetryVersion.CURRENT,
            buildNumber = deviceInfo.buildNumber,
            deviceModel = deviceInfo.deviceModel,
            locale = deviceInfo.locale,
            preferredLanguage = deviceInfo.preferredLanguage,
            supportedLanguages = deviceInfo.supportedLanguages.ifEmpty { null },
            isDev = isDev,
            timestamp = formatTimestamp(timestamp),
        )
    }
}
