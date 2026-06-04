package com.owlmetry.android

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins [OwlLogLevel]'s raw `wire` strings, which serialize directly into the
 * event `level` field and must stay byte-identical to the Swift SDK's
 * `OwlLogLevel: String` raw values (`info`/`debug`/`warn`/`error`).
 */
class OwlLogLevelTest {

    @Test
    fun wireValuesAreLowercaseSwiftRawValues() {
        assertEquals("info", OwlLogLevel.INFO.wire)
        assertEquals("debug", OwlLogLevel.DEBUG.wire)
        assertEquals("warn", OwlLogLevel.WARN.wire)
        assertEquals("error", OwlLogLevel.ERROR.wire)
    }

    @Test
    fun exactlyTheSwiftCasesExistInDeclarationOrder() {
        // Guards against an accidental added/removed/reordered case relative to
        // Swift's enum (info, debug, warn, error).
        assertEquals(
            listOf("INFO", "DEBUG", "WARN", "ERROR"),
            OwlLogLevel.entries.map { it.name },
        )
        assertEquals(
            listOf("info", "debug", "warn", "error"),
            OwlLogLevel.entries.map { it.wire },
        )
    }
}
