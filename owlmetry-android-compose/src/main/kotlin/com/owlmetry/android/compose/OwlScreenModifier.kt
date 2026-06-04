package com.owlmetry.android.compose

import android.os.SystemClock
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import com.owlmetry.android.Owl

/**
 * Automatically track screen appearances and time-on-screen. The Compose analog
 * of Swift's `.owlScreen(_:)` view modifier.
 *
 * Attach to the outermost composable of each screen:
 * ```kotlin
 * @Composable
 * fun HomeScreen() {
 *     Column(modifier = Modifier.owlScreen("Home")) { ... }
 * }
 * ```
 *
 * On enter (the composable joining the composition) it emits a **debug**
 * `sdk:screen_appeared` event with the given [name] as the `screenName`. On exit
 * (leaving the composition) it emits `sdk:screen_disappeared` with a
 * `_duration_ms` attribute recording how long the screen was visible — byte-for-byte
 * the Swift `onAppear` / `onDisappear` pair.
 *
 * Implemented via [composed] + [DisposableEffect] (keyed on [name]) so the
 * appear/disappear pair is bound to the composition lifetime of the node the
 * modifier decorates — the closest Compose analog of SwiftUI's view-lifecycle
 * `onAppear`/`onDisappear`. The visible-duration clock uses [SystemClock.uptimeMillis]
 * (monotonic, immune to wall-clock changes), matching Swift's `Date()` delta
 * intent without its wall-clock drift risk.
 */
public fun Modifier.owlScreen(name: String): Modifier = composed {
    DisposableEffect(name) {
        val appearedAt = SystemClock.uptimeMillis()
        Owl.debug("sdk:screen_appeared", screenName = name)
        onDispose {
            val durationMs = SystemClock.uptimeMillis() - appearedAt
            Owl.debug(
                "sdk:screen_disappeared",
                screenName = name,
                attributes = mapOf("_duration_ms" to durationMs.toString()),
            )
        }
    }
    this
}
