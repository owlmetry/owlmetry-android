package com.owlmetry.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ConsoleLogger.format] message-rewriting + suppression rules, mirroring the
 * Swift `Owl.printToConsole` logic. Pure — [format] does no I/O — so this runs
 * as a plain JVM test (no Robolectric; `android.util.Log` is never touched).
 */
class ConsoleLoggerTest {

    @Test
    fun sdkEventsAreSuppressed() {
        assertNull(ConsoleLogger.format("sdk:session_started", OwlLogLevel.INFO, null))
    }

    @Test
    fun metricStartIsSuppressed() {
        assertNull(ConsoleLogger.format("metric:photo-conversion:start", OwlLogLevel.INFO, null))
    }

    @Test
    fun plainInfoIsFormatted() {
        val line = ConsoleLogger.format("hello world", OwlLogLevel.INFO, null)
        assertEquals("🦉  INFO  hello world", line)
    }

    @Test
    fun levelTagsArePaddedToFiveChars() {
        assertTrue(ConsoleLogger.format("m", OwlLogLevel.INFO, null)!!.contains("INFO "))
        assertTrue(ConsoleLogger.format("m", OwlLogLevel.DEBUG, null)!!.contains("DEBUG"))
        assertTrue(ConsoleLogger.format("m", OwlLogLevel.WARN, null)!!.contains("WARN "))
        assertTrue(ConsoleLogger.format("m", OwlLogLevel.ERROR, null)!!.contains("ERROR"))
    }

    @Test
    fun stepPrefixIsRewritten() {
        assertEquals("🦉  INFO  step: checkout", ConsoleLogger.format("step:checkout", OwlLogLevel.INFO, null))
    }

    @Test
    fun legacyTrackPrefixIsRewrittenToStep() {
        assertEquals("🦉  INFO  step: signup", ConsoleLogger.format("track:signup", OwlLogLevel.INFO, null))
    }

    @Test
    fun metricPhaseIsRewritten() {
        assertEquals(
            "🦉  INFO  metric: api-request complete",
            ConsoleLogger.format("metric:api-request:complete", OwlLogLevel.INFO, null),
        )
    }

    @Test
    fun metricWithoutPhaseIsRewritten() {
        assertEquals(
            "🦉  INFO  metric: onboarding",
            ConsoleLogger.format("metric:onboarding", OwlLogLevel.INFO, null),
        )
    }

    @Test
    fun attributesAreSortedAndAppended() {
        val line = ConsoleLogger.format("hi", OwlLogLevel.WARN, mapOf("z" to "1", "a" to "2"))
        assertEquals("🦉  WARN  hi {a=2, z=1}", line)
    }

    @Test
    fun emptyAttributesAreOmitted() {
        assertEquals("🦉  ERROR boom", ConsoleLogger.format("boom", OwlLogLevel.ERROR, emptyMap()))
    }
}
