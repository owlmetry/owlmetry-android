package com.owlmetry.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ErrorExtraction] structured-field extraction, mirroring the Swift
 * `ErrorExtractionTests` adapted to JVM [Throwable] semantics:
 *  - `_error_type` is the fully-qualified class name (Swift: module-qualified
 *    reflected type).
 *  - `_error_stack` is the JVM stack trace, capped at [ErrorExtraction.MAX_STACK_LENGTH].
 *  - cause chain via [Throwable.cause] up to 5 deep (Swift: `NSUnderlyingErrorKey`).
 *  - caller message wins; empty caller message falls through to the error's own.
 *
 * `_error_domain` / `_error_code` are intentionally absent — see the divergence
 * note in [ErrorExtraction]; the JVM has no NSError domain/code analog.
 */
class ErrorExtractionTest {

    private class PaymentFailed(reason: String) : Exception("payment failed: $reason")

    @Test
    fun plainErrorPopulatesTypeAndStack() {
        val result = ErrorExtraction.extract(error = IllegalStateException("boom"), userMessage = null)
        assertEquals("java.lang.IllegalStateException", result.attributes["_error_type"])
        assertTrue("stack should be present", !result.attributes["_error_stack"].isNullOrEmpty())
        assertTrue(result.attributes["_error_stack"]!!.contains("IllegalStateException"))
    }

    @Test
    fun typeNameIsFullyQualified() {
        val result = ErrorExtraction.extract(error = PaymentFailed("declined"), userMessage = null)
        // Nested class → enclosing$Nested form; the key point is the package
        // dotted prefix is present (module-qualified analog).
        assertTrue(
            "expected qualified type, got ${result.attributes["_error_type"]}",
            result.attributes["_error_type"]?.contains(".") == true,
        )
        assertTrue(result.attributes["_error_type"]!!.contains("PaymentFailed"))
    }

    @Test
    fun noDomainOrCodeKeysOnJvm() {
        val result = ErrorExtraction.extract(error = RuntimeException("x"), userMessage = null)
        assertNull(result.attributes["_error_domain"])
        assertNull(result.attributes["_error_code"])
    }

    @Test
    fun causeChainIsPopulated() {
        val inner = java.io.IOException("inner cause")
        val outer = RuntimeException("outer thing failed", inner)
        val result = ErrorExtraction.extract(error = outer, userMessage = null)
        assertEquals("java.io.IOException", result.attributes["_error_cause_1_type"])
        assertEquals("inner cause", result.attributes["_error_cause_1_message"])
        assertEquals("outer thing failed", result.message)
    }

    @Test
    fun causeChainStopsAtMaxDepth() {
        // Build a chain 7 deep; expect only 5 cause levels to surface.
        var deepest: Throwable = RuntimeException("bottom")
        for (i in 6 downTo 0) {
            deepest = RuntimeException("level $i", deepest)
        }
        val result = ErrorExtraction.extract(error = deepest, userMessage = null)
        assertEquals("level 5", result.attributes["_error_cause_5_message"])
        assertNull(result.attributes["_error_cause_6_type"])
    }

    @Test
    fun selfReferentialCauseDoesNotLoop() {
        // A throwable that is its own cause must not hang the cause walk.
        val e = object : RuntimeException("self") {
            override val cause: Throwable get() = this
        }
        val result = ErrorExtraction.extract(error = e, userMessage = null)
        // No cause keys emitted (the only cause is the error itself).
        assertNull(result.attributes["_error_cause_1_type"])
    }

    @Test
    fun userProvidedMessageWins() {
        val result = ErrorExtraction.extract(error = RuntimeException("ignored"), userMessage = "while uploading the photo")
        assertEquals("while uploading the photo", result.message)
    }

    @Test
    fun blankUserMessageFallsThroughToError() {
        val result = ErrorExtraction.extract(error = RuntimeException("real message"), userMessage = "   ")
        assertEquals("real message", result.message)
    }

    @Test
    fun messagelessErrorFallsBackToToString() {
        val result = ErrorExtraction.extract(error = RuntimeException(), userMessage = null)
        assertFalse(result.message.isEmpty())
        assertTrue(result.message.contains("RuntimeException"))
    }

    @Test
    fun stackTruncatedAtMaxLength() {
        // A deeply chained throwable with a giant message produces a stack far
        // exceeding 16000 chars; assert the cap holds.
        val huge = "x".repeat(40_000)
        val result = ErrorExtraction.extract(error = RuntimeException(huge), userMessage = null)
        assertEquals(ErrorExtraction.MAX_STACK_LENGTH, result.attributes["_error_stack"]!!.length)
    }

    @Test
    fun stackIncludesCausedByFrames() {
        // The JVM stack text carries the cause chain's `Caused by:` frames — the
        // divergence note in ErrorExtraction explicitly relies on this (Swift
        // snapshots the call site; the JVM Throwable carries its own + causes).
        val inner = java.io.IOException("inner io")
        val outer = RuntimeException("outer", inner)
        val result = ErrorExtraction.extract(error = outer, userMessage = null)
        val stack = result.attributes["_error_stack"]!!
        assertTrue("stack should carry the cause chain", stack.contains("Caused by:"))
        assertTrue(stack.contains("IOException"))
    }

    @Test
    fun causeWithoutMessageFallsBackToToString() {
        // A cause carrying no message yields its `toString()` (class name) for
        // `_error_cause_N_message` rather than an empty string — the impl uses
        // `localizedMessage ?: toString()`.
        val inner = IllegalStateException() // no message
        val outer = RuntimeException("outer", inner)
        val result = ErrorExtraction.extract(error = outer, userMessage = null)
        assertEquals("java.lang.IllegalStateException", result.attributes["_error_cause_1_type"])
        val causeMsg = result.attributes["_error_cause_1_message"]!!
        assertTrue("empty cause message should fall back to toString()", causeMsg.contains("IllegalStateException"))
    }

    @Test
    fun exactlyFiveCausesAllSurface() {
        // Boundary: a chain exactly maxCauseDepth (5) deep emits all five and no
        // sixth, with depth incrementing per cause.
        var deepest: Throwable = RuntimeException("bottom")
        for (i in 5 downTo 1) {
            deepest = RuntimeException("level $i", deepest)
        }
        // Chain under `deepest`: level1 → level2 → level3 → level4 → level5 → bottom.
        val result = ErrorExtraction.extract(error = deepest, userMessage = null)
        assertEquals("level 2", result.attributes["_error_cause_1_message"])
        assertEquals("bottom", result.attributes["_error_cause_5_message"])
        assertNull(result.attributes["_error_cause_6_type"])
    }

    @Test
    fun noCauseEmitsNoCauseKeys() {
        val result = ErrorExtraction.extract(error = RuntimeException("standalone"), userMessage = null)
        assertNull(result.attributes["_error_cause_1_type"])
        assertNull(result.attributes["_error_cause_1_message"])
    }

    @Test
    fun cyclicCauseDoesNotLoop() {
        // A → B → A: the walk must terminate at the cycle, not spin forever.
        val a = RuntimeException("A")
        val b = RuntimeException("B", a)
        a.initCause(b) // close the loop A.cause = B, B.cause = A
        val result = ErrorExtraction.extract(error = a, userMessage = null)
        // First hop A.cause = B is recorded; the next hop back to A is the
        // original error, so the loop guard (`current !== error`) stops it.
        assertEquals("java.lang.RuntimeException", result.attributes["_error_cause_1_type"])
        assertEquals("B", result.attributes["_error_cause_1_message"])
        // No runaway: depth never reaches 5 worth of A/B ping-pong.
        assertNull(result.attributes["_error_cause_3_type"])
    }

    @Test
    fun constantsMatchSpec() {
        assertEquals(5, ErrorExtraction.MAX_CAUSE_DEPTH)
        assertEquals(16000, ErrorExtraction.MAX_STACK_LENGTH)
    }
}
