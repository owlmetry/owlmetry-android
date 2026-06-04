package com.owlmetry.android.compose

import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType

/**
 * Lightweight haptic feedback helper used by the SDK's Compose surfaces
 * ([OwlFeedbackView] and, in later phases, the questionnaire flow). The Android
 * analog of the Swift `OwlHaptics`.
 *
 * Swift fires a `UIImpactFeedbackGenerator(style: .light)` on every button
 * action. The Compose analog is [HapticFeedback.performHapticFeedback] from
 * `LocalHapticFeedback`; [HapticFeedbackType.LongPress] is the lightest tactile
 * confirm Compose exposes that maps cleanly across API levels. Wrapped in a
 * `runCatching` so a device without a vibrator (or with haptics disabled in
 * system settings) is a silent no-op — callers invoke unconditionally, matching
 * Swift's no-op fallback.
 */
internal fun HapticFeedback.owlTap() {
    runCatching { performHapticFeedback(HapticFeedbackType.LongPress) }
}
