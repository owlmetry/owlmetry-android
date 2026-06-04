package com.owlmetry.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

/**
 * [ConsoleLogger.print] side-effecting Logcat sink, mirroring the suppression +
 * level-mapping half of Swift's `printToConsole`. [ConsoleLogger.format] is
 * covered purely in [ConsoleLoggerTest]; here we exercise the real
 * [android.util.Log] path under Robolectric (which captures Logcat via
 * [ShadowLog]) to confirm:
 *  - suppressed events (`sdk:` / `metric:…:start`) emit NOTHING, and
 *  - each level routes to the matching Logcat priority with the formatted line.
 *
 * Also pins a couple of rewrite edge cases the format tests don't cover (empty
 * body after a `step:` / `metric:` prefix) so the substring math can't regress.
 */
@RunWith(RobolectricTestRunner::class)
class ConsoleLoggerPrintTest {

    private fun logs() = ShadowLog.getLogsForTag(ConsoleLogger.TAG)

    @Test
    fun sdkEventEmitsNothingToLogcat() {
        ShadowLog.clear()
        ConsoleLogger.print("sdk:session_started", OwlLogLevel.INFO, null)
        assertEquals("sdk: events must be suppressed (no Logcat line)", 0, logs().size)
    }

    @Test
    fun metricStartEmitsNothingToLogcat() {
        ShadowLog.clear()
        ConsoleLogger.print("metric:checkout:start", OwlLogLevel.INFO, null)
        assertEquals("metric:…:start must be suppressed", 0, logs().size)
    }

    @Test
    fun infoRoutesToInfoPriority() {
        ShadowLog.clear()
        ConsoleLogger.print("hello", OwlLogLevel.INFO, null)
        val entries = logs()
        assertEquals(1, entries.size)
        assertEquals(android.util.Log.INFO, entries[0].type)
        assertEquals("🦉  INFO  hello", entries[0].msg)
    }

    @Test
    fun debugRoutesToDebugPriority() {
        ShadowLog.clear()
        ConsoleLogger.print("d", OwlLogLevel.DEBUG, null)
        val entries = logs()
        assertEquals(1, entries.size)
        assertEquals(android.util.Log.DEBUG, entries[0].type)
    }

    @Test
    fun warnRoutesToWarnPriority() {
        ShadowLog.clear()
        ConsoleLogger.print("w", OwlLogLevel.WARN, null)
        val entries = logs()
        assertEquals(1, entries.size)
        assertEquals(android.util.Log.WARN, entries[0].type)
    }

    @Test
    fun errorRoutesToErrorPriority() {
        ShadowLog.clear()
        ConsoleLogger.print("e", OwlLogLevel.ERROR, null)
        val entries = logs()
        assertEquals(1, entries.size)
        assertEquals(android.util.Log.ERROR, entries[0].type)
    }

    @Test
    fun printEmitsTheSameLineFormatProduces() {
        ShadowLog.clear()
        val attrs = mapOf("z" to "1", "a" to "2")
        ConsoleLogger.print("hi", OwlLogLevel.WARN, attrs)
        val entries = logs()
        assertEquals(1, entries.size)
        assertEquals(ConsoleLogger.format("hi", OwlLogLevel.WARN, attrs), entries[0].msg)
    }

    // Rewrite edge cases for the substring math (no dedicated format coverage).

    @Test
    fun stepPrefixWithEmptyBodyRewritesToBareStep() {
        assertEquals("🦉  INFO  step: ", ConsoleLogger.format("step:", OwlLogLevel.INFO, null))
    }

    @Test
    fun metricPrefixWithTrailingColonSplitsIntoEmptyPhase() {
        // "metric:name:" → name + empty phase → "metric: name " (trailing space).
        assertEquals("🦉  INFO  metric: name ", ConsoleLogger.format("metric:name:", OwlLogLevel.INFO, null))
    }

    @Test
    fun metricSuppressionOnlyTriggersOnStartSuffix() {
        // "metric:x:started" ends with "started" not ":start", so it is NOT
        // suppressed — only the exact ":start" terminal suffix is.
        assertNull(ConsoleLogger.format("metric:x:start", OwlLogLevel.INFO, null))
        assertEquals(
            "🦉  INFO  metric: x started",
            ConsoleLogger.format("metric:x:started", OwlLogLevel.INFO, null),
        )
    }
}
