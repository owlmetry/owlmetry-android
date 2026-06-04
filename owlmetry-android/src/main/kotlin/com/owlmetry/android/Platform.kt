package com.owlmetry.android

/**
 * The event `environment` discriminator.
 *
 * Swift's `OwlPlatform` enumerates ios/ipados/macos/watchos because one Apple
 * binary can run on several form factors. Android has a single environment, so
 * this collapses to one case. The raw `wire` value ("android") is what
 * serializes into the `environment` JSON field — it matches the server's
 * accepted environments (apple, android, web, backend → specific ios, ipados,
 * macos, android, web, backend).
 */
public enum class OwlPlatform(public val wire: String) {
    ANDROID("android"),
}
