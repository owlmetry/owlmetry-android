package com.owlmetry.android

/**
 * SDK identity stamped onto every event as `sdk_name` + `sdk_version`.
 *
 * `current` is bumped by the release workflow before each tag (mirrors the
 * Swift SDK's `OwlmetryVersion.current`). Keep the literal on its own line so
 * the `sed` bump step in `.github/workflows/release.yml` can rewrite it.
 */
public object OwlmetryVersion {
    public const val NAME: String = "owlmetry-android"
    public const val CURRENT: String = "0.1.0"
}
