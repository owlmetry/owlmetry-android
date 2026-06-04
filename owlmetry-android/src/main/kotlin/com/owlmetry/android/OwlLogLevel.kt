package com.owlmetry.android

/**
 * Severity of a logged event. Mirrors the Swift SDK's `OwlLogLevel` — the raw
 * `wire` string is what serializes into the `level` JSON field, so it must stay
 * byte-identical to the Swift `String` raw values (`info`/`debug`/`warn`/`error`).
 */
public enum class OwlLogLevel(public val wire: String) {
    INFO("info"),
    DEBUG("debug"),
    WARN("warn"),
    ERROR("error"),
}
