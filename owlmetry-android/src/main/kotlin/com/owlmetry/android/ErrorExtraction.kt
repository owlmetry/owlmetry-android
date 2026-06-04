package com.owlmetry.android

import java.io.PrintWriter
import java.io.StringWriter

/**
 * Extracts structured fields from a [Throwable] for `Owl.error(Throwable, ...)`.
 * Output is delivered as `_error_*` reserved custom attributes, which the server
 * reads for issue fingerprinting (`_error_type` becomes the fingerprint
 * discriminator) and dashboard display. Mirrors Swift's `ErrorExtraction`.
 *
 * Divergences from Swift, all rooted in the platform's error model:
 *  - **`_error_type`** is the fully-qualified Java/Kotlin class name
 *    (`error.javaClass.name`, e.g. `java.io.IOException`,
 *    `kotlin.IllegalStateException`). Swift uses `String(reflecting:)` on the
 *    Swift type. Both yield a stable, type-distinct string the server's issue
 *    tracker can discriminate on.
 *  - **`_error_stack`** is the JVM stack trace (the same text
 *    `printStackTrace()` emits, including the cause chain's `Caused by:`
 *    frames), capped at [maxStackLength]. Swift uses `Thread.callStackSymbols`
 *    captured at the call site. On the JVM the Throwable *carries* its own
 *    capture point, so we don't (and can't usefully) snapshot the caller's
 *    frames separately — the throwable's stack is the authoritative one.
 *  - **`_error_domain` / `_error_code`** have no Android analog. A Swift `Error`
 *    bridges to `NSError` with a synthesized domain (type name) + code (enum
 *    ordinal); a JVM `Throwable` has neither. We deliberately omit both keys
 *    rather than fabricate them — `_error_type` already carries the type, and
 *    the server treats these fields as optional.
 *  - **Cause chain** walks [Throwable.cause] (the JVM equivalent of
 *    `NSUnderlyingErrorKey`) up to [maxCauseDepth], emitting
 *    `_error_cause_N_type` (cause class name) + `_error_cause_N_message`.
 */
internal object ErrorExtraction {
    const val MAX_CAUSE_DEPTH: Int = 5
    const val MAX_STACK_LENGTH: Int = 16000

    data class Result(
        val message: String,
        val attributes: Map<String, String>,
    )

    /**
     * @param error the value passed to `Owl.error(Throwable, ...)`.
     * @param userMessage optional caller-provided context. When non-blank it is
     *   used as the event message; otherwise one is derived from the error.
     */
    fun extract(error: Throwable, userMessage: String?): Result {
        val attrs = LinkedHashMap<String, String>()

        attrs["_error_type"] = error.javaClass.name

        val stack = stackTraceString(error)
        if (stack.isNotEmpty()) {
            attrs["_error_stack"] =
                if (stack.length > MAX_STACK_LENGTH) stack.substring(0, MAX_STACK_LENGTH) else stack
        }

        // Walk the cause chain (Throwable.cause ≈ NSUnderlyingErrorKey), guarding
        // against a self-referential or cyclic chain so we can't loop forever.
        var current: Throwable? = error.cause
        var depth = 1
        while (current != null && depth <= MAX_CAUSE_DEPTH && current !== error) {
            attrs["_error_cause_${depth}_type"] = current.javaClass.name
            attrs["_error_cause_${depth}_message"] = current.localizedMessage ?: current.toString()
            val next = current.cause
            if (next === current) break // direct self-cause
            current = next
            depth += 1
        }

        return Result(
            message = resolveMessage(error, userMessage),
            attributes = attrs,
        )
    }

    /**
     * Caller-provided message wins. Otherwise prefer the throwable's
     * `localizedMessage`/`message` when present, falling back to `toString()`
     * (which carries the class name + message, e.g.
     * `java.lang.IllegalStateException: boom`) so the event is never empty.
     * Mirrors Swift's `resolveMessage` (caller > localizedDescription >
     * String(describing:)).
     */
    private fun resolveMessage(error: Throwable, userMessage: String?): String {
        val user = userMessage?.trim()
        if (!user.isNullOrEmpty()) return user
        val localized = error.localizedMessage?.trim()
        if (!localized.isNullOrEmpty()) return localized
        return error.toString()
    }

    private fun stackTraceString(error: Throwable): String {
        val sw = StringWriter()
        PrintWriter(sw).use { error.printStackTrace(it) }
        return sw.toString().trimEnd()
    }
}
