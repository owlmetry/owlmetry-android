package com.owlmetry.android

import android.content.Context
import android.content.pm.ApplicationInfo
import java.util.UUID

/**
 * Public entry point to the Owlmetry SDK. Through Phase 3 it can be
 * [configure]d — building an [OwlConfiguration], collecting [DeviceInfo],
 * generating a session id, and deriving `isDev` — and it now owns **persistent
 * identity**: a Keychain-equivalent anonymous id ([IdentityStore]), plus the
 * [setUser] / [clearUser] surface that mirrors the Swift `Owl` singleton.
 * Transport (the server-side identity *claim*) and the full logging API land in
 * later phases; the identity *state* they will read is established here.
 *
 * Mirrors the Swift `Owl` singleton: identity resolution is "saved real user id,
 * otherwise the persistent anonymous id", and [currentUserId] returns whichever
 * is active — exactly the value Swift stamps onto outgoing events.
 */
public object Owl {
    /** os_log subsystem analog — used by later logging phases. */
    public const val LOG_SUBSYSTEM: String = "com.owlmetry.sdk"

    private val lock = Any()

    private var state: State? = null

    // Pre-configure identity ops, applied at the next configure(). iOS persists
    // setUser/clearUser immediately (Keychain/UserDefaults are globally
    // available); Android persistence needs a Context that only exists from
    // configure() onward, so a pre-configure call is stashed here and written to
    // durable storage at configure() time — so the next configure() resolves to
    // it exactly as Swift's unconditional IdentityManager persistence does.
    // Last write wins. Residual divergence: if the process dies between a
    // pre-configure setUser/clearUser and configure(), the stashed op is lost
    // (Swift persists durably). In practice configure() runs at app startup
    // before any identity call, so this is vanishingly rare.
    private var pendingUserId: String? = null
    private var pendingClearUser: Boolean = false
    private var pendingNewAnonymousId: Boolean = false

    /** Internal snapshot of everything [configure] resolves. */
    internal data class State(
        val configuration: OwlConfiguration,
        val deviceInfo: DeviceInfo,
        val sessionId: String,
        val isDev: Boolean,
        // Persistent identity store (SharedPreferences-backed). Held so
        // setUser/clearUser can persist + reset ids after configure.
        val identityStore: IdentityStore,
        // The persistent anonymous id resolved at configure (and re-minted by
        // clearUser(newAnonymousId = true)).
        var anonymousId: String,
        // The resolved id stamped onto events: the real user id once set,
        // otherwise the anonymous id. Mirrors Swift's `defaultUserId`.
        var defaultUserId: String,
    )

    /** The active session id, or null before [configure]. */
    public val sessionId: String?
        get() = synchronized(lock) { state?.sessionId }

    /**
     * The current user id used on outgoing events — the real user id if
     * [setUser] has been called, otherwise the device's persistent anonymous
     * id. `null` before [configure]. Mirrors Swift `Owl.currentUserId`.
     */
    public val currentUserId: String?
        get() = synchronized(lock) { state?.defaultUserId }

    /**
     * Configure the SDK. Builds + validates the configuration, snapshots device
     * info, mints a session id, derives `isDev` from the host app's debuggable
     * flag, and **resolves persistent identity**: reads (or generates) the
     * anonymous id and prefers any saved real user id over it. Throws
     * [OwlConfigurationError] on invalid endpoint / API key / missing bundle id
     * (mirrors Swift's throwing `configure`).
     *
     * TODO(Phase 4): start the EventTransport flush loop here, and — when a
     * previously saved real user id differs from the anonymous id — fire the
     * idempotent startup identity *claim* against the server (Swift's
     * `transport.claimIdentity` reclaim in `configureWith`). The persisted ids
     * this reads are already in place.
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

        val identityStore = IdentityStore(appContext)

        synchronized(lock) {
            // Apply any identity op issued before configure() so it persists and
            // resolves into this session, mirroring Swift's unconditional
            // IdentityManager persistence (only the server claim is gated on
            // transport). Last write wins; clear takes precedence over set.
            if (pendingClearUser) {
                identityStore.clearUserId()
                if (pendingNewAnonymousId) identityStore.resetAnonymousId()
            } else {
                pendingUserId?.let { identityStore.saveUserId(it) }
            }
            pendingUserId = null
            pendingClearUser = false
            pendingNewAnonymousId = false

            // Resolve identity: saved real user id > persistent anonymous id —
            // byte-for-byte the Swift `configureWith` resolution.
            val anonId = identityStore.anonymousId()
            val resolvedUserId = identityStore.savedUserId() ?: anonId

            state = State(
                configuration = configuration,
                deviceInfo = deviceInfo,
                sessionId = UUID.randomUUID().toString(),
                isDev = isDev,
                identityStore = identityStore,
                anonymousId = anonId,
                defaultUserId = resolvedUserId,
            )
        }
    }

    // MARK: - User Identity

    /**
     * Set the real user identifier (call after your app's login). Persists the
     * id and makes it the resolved [currentUserId] for future events. Mirrors
     * Swift `Owl.setUser(_:)`, which persists unconditionally.
     *
     * Called before [configure], the id is stashed and persisted at the next
     * [configure] so that session resolves to the real user — matching Swift,
     * where `IdentityManager.saveUserId` runs even pre-configure and only the
     * server *claim* is gated on transport.
     *
     * TODO(Phase 4): after persisting (when configured), await any in-flight log
     * tasks then fire `transport.claimIdentity(anonymousId, identifier)` so
     * previously-sent anonymous events are retroactively associated with this
     * user (Swift's claim path). The identity *state* update is complete here.
     */
    public fun setUser(identifier: String) {
        synchronized(lock) {
            val s = state
            if (s == null) {
                // Pre-configure: stash so the next configure() persists +
                // resolves to this id (Swift persists immediately via Keychain;
                // Android needs configure()'s Context).
                pendingUserId = identifier
                pendingClearUser = false
                return
            }
            s.identityStore.saveUserId(identifier)
            s.defaultUserId = identifier
        }
    }

    /**
     * Clear the user identifier (call on logout). Reverts the resolved
     * [currentUserId] to the anonymous device id for future events. Pass
     * [newAnonymousId] = true to mint a fresh anonymous id (use when the device
     * may be shared between users). Mirrors Swift `Owl.clearUser(newAnonymousId:)`,
     * which clears (and optionally resets) unconditionally.
     *
     * Called before [configure], the logout is stashed and applied at the next
     * [configure], matching Swift's pre-configure persistence.
     */
    public fun clearUser(newAnonymousId: Boolean = false) {
        synchronized(lock) {
            val s = state
            if (s == null) {
                // Pre-configure: stash the logout so the next configure() applies
                // it (clear takes precedence over a stashed setUser).
                pendingClearUser = true
                pendingNewAnonymousId = newAnonymousId
                pendingUserId = null
                return
            }
            s.identityStore.clearUserId()
            if (newAnonymousId) {
                val fresh = s.identityStore.resetAnonymousId()
                s.anonymousId = fresh
                s.defaultUserId = fresh
            } else {
                s.defaultUserId = s.anonymousId
            }
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

    /** Test/teardown hook — clears configured state + any stashed identity ops. */
    internal fun resetForTesting() {
        synchronized(lock) {
            state = null
            pendingUserId = null
            pendingClearUser = false
            pendingNewAnonymousId = false
        }
    }
}
