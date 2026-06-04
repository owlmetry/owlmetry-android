package com.owlmetry.android

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistent state every questionnaire trigger condition reads from. The Android
 * analog of the Swift SDK's `OwlQuestionnaireState`.
 *
 * Swift stores the launch / foreground counters and install timestamp in the
 * same `UserDefaults` suite as `IdentityManager`'s real-user-id. The framework
 * analog here is [SharedPreferences] — and we deliberately reuse the SDK's own
 * preference file ([IdentityStore.PREFS_NAME]) so install scope matches the
 * identity store, exactly as Swift shares the suite.
 *
 * All counters increment idempotently per process via an in-memory flag so a hot
 * reload of `Owl.configure(...)` doesn't double-count the launch.
 *
 * Unlike the Swift `shared` singleton (which can resolve `UserDefaults.standard`
 * lazily), Android storage needs a [Context], so the instance is created at
 * `Owl.configure(...)` time and held by [Owl]. A process-wide [shared] handle is
 * still published from `configure(...)` so the Compose trigger gate and the
 * static [Owl] accessors can read the counters without threading a [Context]
 * through the call sites.
 */
public class OwlQuestionnaireState internal constructor(
    private val prefs: SharedPreferences,
) {
    /** Convenience constructor resolving the SDK's private preference file. */
    internal constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(
            IdentityStore.PREFS_NAME,
            Context.MODE_PRIVATE,
        ),
    )

    private val lock = Any()
    private var didMarkConfigured = false

    /**
     * Called once per process from the tail of [Owl.configure]. Increments
     * `launch_count` and sets `first_launch_at` the first time. Idempotent per
     * process via [didMarkConfigured]. Mirrors Swift's `markConfiguredOnce`.
     */
    public fun markConfiguredOnce(now: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            if (didMarkConfigured) return
            didMarkConfigured = true

            val next = prefs.getInt(KEY_LAUNCH_COUNT, 0) + 1
            val editor = prefs.edit().putInt(KEY_LAUNCH_COUNT, next)
            if (!prefs.contains(KEY_FIRST_LAUNCH_AT)) {
                editor.putLong(KEY_FIRST_LAUNCH_AT, now)
            }
            editor.apply()
        }
    }

    /**
     * Called from the lifecycle observer on every foreground transition.
     * Mirrors Swift's `incrementForeground`. The process-cold-launch path
     * doesn't double-fire because the host reports a foreground transition only
     * after the first background→foreground.
     */
    public fun incrementForeground() {
        synchronized(lock) {
            val next = prefs.getInt(KEY_FOREGROUND_COUNT, 0) + 1
            prefs.edit().putInt(KEY_FOREGROUND_COUNT, next).apply()
        }
    }

    /** Total `Owl.configure(...)` completions since install. */
    public val launchCount: Int
        get() = prefs.getInt(KEY_LAUNCH_COUNT, 0)

    /** Total foreground transitions since install. */
    public val foregroundCount: Int
        get() = prefs.getInt(KEY_FOREGROUND_COUNT, 0)

    /**
     * Epoch-millis timestamp of the first-ever `Owl.configure(...)` on this
     * install, or null if none recorded. Swift stores a `Date`; the Android
     * analog is epoch millis (the [Snapshot] derivations work in either unit).
     */
    public val firstLaunchAt: Long?
        get() {
            // `contains` is the authoritative presence check — epoch 0 is a valid
            // (if unrealistic) timestamp, so don't treat 0 as "absent".
            if (!prefs.contains(KEY_FIRST_LAUNCH_AT)) return null
            return prefs.getLong(KEY_FIRST_LAUNCH_AT, 0L)
        }

    /**
     * Immutable snapshot for pure condition evaluation. Used by the trigger gate
     * so conditions don't re-read [SharedPreferences] mid-evaluation. Mirrors
     * Swift's `snapshot(now:)` + `Snapshot`.
     */
    public fun snapshot(now: Long = System.currentTimeMillis()): Snapshot =
        Snapshot(
            launchCount = launchCount,
            foregroundCount = foregroundCount,
            firstLaunchAt = firstLaunchAt,
            now = now,
        )

    /** Pure value snapshot of the persistent trigger state. */
    public data class Snapshot(
        public val launchCount: Int,
        public val foregroundCount: Int,
        public val firstLaunchAt: Long?,
        public val now: Long,
    ) {
        public fun daysSinceFirstLaunch(): Double {
            val first = firstLaunchAt ?: return 0.0
            return (now - first) / MILLIS_PER_DAY
        }

        public fun hoursSinceFirstLaunch(): Double {
            val first = firstLaunchAt ?: return 0.0
            return (now - first) / MILLIS_PER_HOUR
        }

        private companion object {
            const val MILLIS_PER_DAY: Double = 86_400_000.0
            const val MILLIS_PER_HOUR: Double = 3_600_000.0
        }
    }

    /** Debug-only escape hatch for the demo app's "Reset persistent state" button. */
    public fun debugReset() {
        synchronized(lock) {
            prefs.edit()
                .remove(KEY_LAUNCH_COUNT)
                .remove(KEY_FOREGROUND_COUNT)
                .remove(KEY_FIRST_LAUNCH_AT)
                .apply()
            didMarkConfigured = false
        }
    }

    public companion object {
        internal const val KEY_LAUNCH_COUNT: String = "owlmetry.questionnaire.launch_count"
        internal const val KEY_FOREGROUND_COUNT: String = "owlmetry.questionnaire.foreground_count"
        internal const val KEY_FIRST_LAUNCH_AT: String = "owlmetry.questionnaire.first_launch_at"

        /**
         * The process-wide state handle, published by [Owl.configure] so the
         * Compose trigger gate + the static [Owl] launch/foreground accessors can
         * read counters without a [Context]. Null before the first
         * `configure(...)`. The closest analog of Swift's `static let shared`,
         * adapted to Android's Context requirement.
         */
        @Volatile
        public var shared: OwlQuestionnaireState? = null
            internal set
    }
}
