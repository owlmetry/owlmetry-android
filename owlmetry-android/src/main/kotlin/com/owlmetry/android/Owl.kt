package com.owlmetry.android

import android.content.Context
import android.content.pm.ApplicationInfo
import java.util.UUID

/**
 * Public entry point to the Owlmetry SDK. Phase 2 skeleton: it can be
 * [configure]d — building an [OwlConfiguration], collecting [DeviceInfo],
 * generating a session id, and deriving `isDev` — but transport and the
 * logging API land in later phases. Mirrors the Swift `Owl` singleton surface
 * at a high level; method-level parity (log/metric/funnel/identity) comes later.
 */
public object Owl {
    /** os_log subsystem analog — used by later logging phases. */
    public const val LOG_SUBSYSTEM: String = "com.owlmetry.sdk"

    private val lock = Any()

    private var state: State? = null

    /** Internal snapshot of everything [configure] resolves. */
    internal data class State(
        val configuration: OwlConfiguration,
        val deviceInfo: DeviceInfo,
        val sessionId: String,
        val isDev: Boolean,
        // Identity placeholders. Phase 3 wires Keychain-equivalent anon-id
        // persistence + setUser; for now these hold the in-memory ids.
        var anonymousUserId: String?,
        var currentUserId: String?,
    )

    /** The active session id, or null before [configure]. */
    public val sessionId: String?
        get() = synchronized(lock) { state?.sessionId }

    /**
     * The resolved end-user id — the real user id once set, otherwise the
     * anonymous id — or null before [configure]. Mirrors Swift's notion of the
     * id stamped onto events.
     */
    public val currentUserId: String?
        get() = synchronized(lock) {
            state?.let { it.currentUserId ?: it.anonymousUserId }
        }

    /**
     * Configure the SDK. Builds + validates the configuration, snapshots device
     * info, mints a session id, and derives `isDev` from the host app's
     * debuggable flag. Throws [OwlConfigurationError] on invalid endpoint / API
     * key / missing bundle id (mirrors Swift's throwing `configure`).
     *
     * TODO(Phase 3): persist/restore the anonymous id (Keychain analog →
     * EncryptedSharedPreferences) instead of minting a fresh one each launch.
     * TODO(Phase 4): start the EventTransport flush loop here.
     */
    @Throws(OwlConfigurationError::class)
    public fun configure(
        context: Context,
        endpoint: String,
        apiKey: String,
        flushOnBackground: Boolean = true,
        compressionEnabled: Boolean = true,
        networkTrackingEnabled: Boolean = true,
        consoleLogging: Boolean = true,
        attributionEnabled: Boolean = true,
    ) {
        val appContext = context.applicationContext ?: context
        val configuration = OwlConfiguration.create(
            context = appContext,
            endpoint = endpoint,
            apiKey = apiKey,
            flushOnBackground = flushOnBackground,
            compressionEnabled = compressionEnabled,
            networkTrackingEnabled = networkTrackingEnabled,
            consoleLogging = consoleLogging,
            attributionEnabled = attributionEnabled,
        )
        val deviceInfo = DeviceInfo.collect(appContext)
        val isDev = resolveIsDev(appContext)

        synchronized(lock) {
            state = State(
                configuration = configuration,
                deviceInfo = deviceInfo,
                sessionId = UUID.randomUUID().toString(),
                isDev = isDev,
                // TODO(Phase 3): restore from persistent storage.
                anonymousUserId = "owl_anon_${UUID.randomUUID()}",
                currentUserId = null,
            )
        }
    }

    /**
     * `is_dev` derivation. The Swift SDK keys off the consuming app's build
     * configuration; on Android the analog is the host app's
     * `FLAG_DEBUGGABLE` — true for debug builds, false for release. This reads
     * the *consuming app's* manifest flag, not the SDK's own BuildConfig.
     */
    internal fun resolveIsDev(context: Context): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** Test/teardown hook — clears configured state. */
    internal fun resetForTesting() {
        synchronized(lock) { state = null }
    }
}
