package com.owlmetry.android

import android.content.Context
import android.content.pm.ApplicationInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Public entry point to the Owlmetry SDK. It can be [configure]d — building an
 * [OwlConfiguration], collecting [DeviceInfo], generating a session id, deriving
 * `isDev`, and starting the [EventTransport] — and it owns **persistent
 * identity** ([setUser] / [clearUser], a Keychain-equivalent anonymous id via
 * [IdentityStore]) and the **logging API**: [info] / [debug] / [warn] / [error]
 * (free-text and [Throwable] overloads).
 *
 * Each log call snapshots configured state, echoes to the console
 * ([ConsoleLogger]), builds a [LogEvent] ([EventBuilder]), passes it through the
 * [DuplicateFilter], and enqueues it on the transport. An in-flight log-task
 * gate ([awaitInFlightLogTasks]) lets [setUser] and the configure-time startup
 * reclaim wait for pending log coroutines to reach the transport before issuing
 * the server-side identity *claim* — mirroring the Swift `Owl` singleton, which
 * guards the same race to avoid orphaned anonymous-user rows.
 *
 * Identity resolution is "saved real user id, otherwise the persistent anonymous
 * id"; [currentUserId] returns whichever is active — exactly the value stamped
 * onto outgoing events.
 */
public object Owl {
    /** os_log subsystem analog — used by later logging phases. */
    public const val LOG_SUBSYSTEM: String = "com.owlmetry.sdk"

    private val lock = Any()

    private var state: State? = null

    /**
     * SDK-wide coroutine scope — the analog of the structured-concurrency root
     * the Swift actors live under. The flush loop, auto-flush Tasks, offline
     * debounce, and identity claim all run here. A [SupervisorJob] keeps one
     * failing child from tearing the scope down. Recreated each [configure];
     * cancelled on [resetForTesting].
     */
    private var sdkScope: CoroutineScope? = null

    /**
     * Test seam: when non-null, [configure] wires this [HttpClient] into the
     * [EventTransport] instead of the production [DefaultHttpClient]. The analog
     * of the Swift tests intercepting `URLSession.shared` via a global
     * `URLProtocol` (the SDK has no client-injection on its public `configure`,
     * so a mock is installed out-of-band). Production code never sets this;
     * [resetForTesting] leaves it untouched so a test can install it once.
     */
    internal var httpClientOverrideForTesting: HttpClient? = null

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

    // In-flight `Owl.log(...)` coroutine accounting — the analog of Swift's
    // `inFlightLogTasks` / `pendingLogDrains`. Bumped synchronously in [log]
    // before the enqueue coroutine spawns, decremented when that coroutine
    // reaches the transport buffer; setUser + the configure-time startup
    // reclaim await it draining to zero (via [awaitInFlightLogTasks]) before
    // POSTing the claim, so the claim's flushAll() can't win the race against a
    // log event still hopping DuplicateFilter → EventTransport and POST against
    // an empty events table (the orphaned-anon-user bug — see CLAUDE.md
    // "Identity"). Lives outside [State] because logs can be issued (and
    // counted) only while configured, but the waiters must survive a teardown
    // race cleanly; guarded by [lock].
    private var inFlightLogTasks: Int = 0
    private val pendingLogDrains = ArrayList<CompletableDeferred<Unit>>()

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
        // The batching/retrying event transport. Its flush loop is started at
        // configure() and it owns the offline queue + network monitor.
        val transport: EventTransport,
        // The live reachability monitor, held so it can be torn down on reset.
        val networkMonitor: NetworkMonitor,
        // Drops runaway duplicate events before they hit the transport buffer.
        // Started at configure(); its cleanup loop runs on the SDK scope.
        val duplicateFilter: DuplicateFilter,
    )

    /**
     * The active [EventTransport] once configured, else null. Internal — later
     * phases (logging, metrics, claim, properties) enqueue through it.
     */
    internal val transport: EventTransport?
        get() = synchronized(lock) { state?.transport }

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
     * Starts the [EventTransport] flush loop and — when a previously saved real
     * user id differs from the anonymous id — fires the idempotent startup
     * identity *claim* against the server (Swift's `transport.claimIdentity`
     * reclaim in `configureWith`), retroactively attributing anonymous events
     * sent before this user was known.
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

        // Build the SDK's coroutine scope + transport plumbing. Done outside the
        // lock (it touches the framework + filesystem) then published under it.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val networkMonitor = NetworkMonitor.create(appContext)
        val offlineQueue = OfflineQueue(directory = appContext.filesDir, scope = scope)
        val transport = EventTransport(
            endpoint = configuration.endpoint.toURL(),
            apiKey = configuration.apiKey,
            bundleId = configuration.bundleId,
            compressionEnabled = configuration.compressionEnabled,
            offlineQueue = offlineQueue,
            networkMonitor = networkMonitor,
            scope = scope,
            httpClient = httpClientOverrideForTesting ?: DefaultHttpClient,
        )
        val duplicateFilter = DuplicateFilter()

        var claimAnon: String? = null
        var claimUser: String? = null

        synchronized(lock) {
            sdkScope = scope
            // Re-arm the one-shot "not configured" warning for this session
            // (Swift resets `hasWarnedNotConfigured = false` in `configureWith`).
            hasWarnedNotConfigured = false
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
            val savedUserId = identityStore.savedUserId()
            val resolvedUserId = savedUserId ?: anonId

            // Startup reclaim: if a real user id is on file and differs from the
            // anon id, claim it so anonymous events sent on a prior launch (or
            // before this user logged in) attribute to the real user. Fired
            // after the lock is released. Mirrors Swift's `configureWith`.
            if (savedUserId != null && savedUserId != anonId) {
                claimAnon = anonId
                claimUser = savedUserId
            }

            state = State(
                configuration = configuration,
                deviceInfo = deviceInfo,
                sessionId = UUID.randomUUID().toString(),
                isDev = isDev,
                identityStore = identityStore,
                anonymousId = anonId,
                defaultUserId = resolvedUserId,
                transport = transport,
                networkMonitor = networkMonitor,
                duplicateFilter = duplicateFilter,
            )
        }

        // Start the flush loop + the dedup-cleanup loop. Both run on the SDK
        // scope; the filter's cleanup mirrors Swift's `filter.start()`.
        transport.start()
        duplicateFilter.start(scope)

        // Emit the session-start event. Mirrors Swift's
        // `log("sdk:session_started", ...)` at the end of `configureWith`. The
        // process-launch-duration attribute Swift attaches via sysctl has no
        // portable Android equivalent, so it's omitted (the event itself is the
        // load-bearing signal; `_launch_ms` is best-effort telemetry).
        log(
            message = "sdk:session_started",
            level = OwlLogLevel.INFO,
            screenName = null,
            attributes = null,
        )

        // Fire the optional startup claim outside the lock — claimIdentity
        // suspends (flushAll + drain in-flight sends) and must not hold the
        // monitor lock. Gate it behind any in-flight log Tasks (the
        // session-start event above is one) so the claim's flushAll can't POST
        // against an empty events table before that event reaches the buffer —
        // mirrors Swift's `await Self.awaitInFlightLogTasks()` before the
        // startup reclaim.
        val anon = claimAnon
        val user = claimUser
        if (anon != null && user != null) {
            scope.launch {
                awaitInFlightLogTasks()
                transport.claimIdentity(anonymousId = anon, userId = user)
            }
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
     * When configured, after persisting it fires
     * `transport.claimIdentity(anonymousId, identifier)` so previously-sent
     * anonymous events are retroactively associated with this user (Swift's
     * claim path). Before POSTing the claim it awaits any in-flight
     * `Owl.log(...)` coroutines ([awaitInFlightLogTasks]) so the claim's
     * `flushAll` can't race ahead of events still hopping
     * DuplicateFilter → EventTransport — exactly Swift's barrier, which prevents
     * the orphaned-anon-user bug (see CLAUDE.md "Identity").
     */
    public fun setUser(identifier: String) {
        val (transport, anonId) = synchronized(lock) {
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
            s.transport to s.anonymousId
        }
        // Fire the claim outside the lock — claimIdentity suspends (flushAll +
        // in-flight-send drain). Mirrors Swift's
        // `Task { await awaitInFlightLogTasks(); claimIdentity(...) }`.
        sdkScope?.launch {
            awaitInFlightLogTasks()
            transport.claimIdentity(anonymousId = anonId, userId = identifier)
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

    // MARK: - Logging

    /**
     * Log an **info**-level event. [attributes] values may be null — null
     * entries are dropped (see [cleanAttributes]) so optional strings flow
     * through without unwrapping at the call site. [screenName] tags the event
     * with the current screen. Mirrors Swift `Owl.info(_:)`.
     *
     * Before [configure] the call is a warned no-op (matches Swift's
     * "events are being dropped" path). Source location (`_file`/`_function`/
     * `_line`) is captured from [file]/[function]/[line] — callers normally let
     * these default; there is no Kotlin equivalent of Swift's `#file`/`#line`
     * literals, so they default to placeholders unless the caller supplies them.
     */
    public fun info(
        message: String,
        screenName: String? = null,
        attributes: Map<String, String?> = emptyMap(),
        file: String = DEFAULT_FILE,
        function: String = DEFAULT_FUNCTION,
        line: Int = 0,
    ) {
        log(message, OwlLogLevel.INFO, screenName, cleanAttributes(attributes), file, function, line)
    }

    /** Log a **debug**-level event. See [info] for parameter semantics. Mirrors Swift `Owl.debug(_:)`. */
    public fun debug(
        message: String,
        screenName: String? = null,
        attributes: Map<String, String?> = emptyMap(),
        file: String = DEFAULT_FILE,
        function: String = DEFAULT_FUNCTION,
        line: Int = 0,
    ) {
        log(message, OwlLogLevel.DEBUG, screenName, cleanAttributes(attributes), file, function, line)
    }

    /** Log a **warn**-level event. See [info] for parameter semantics. Mirrors Swift `Owl.warn(_:)`. */
    public fun warn(
        message: String,
        screenName: String? = null,
        attributes: Map<String, String?> = emptyMap(),
        file: String = DEFAULT_FILE,
        function: String = DEFAULT_FUNCTION,
        line: Int = 0,
    ) {
        log(message, OwlLogLevel.WARN, screenName, cleanAttributes(attributes), file, function, line)
    }

    /** Log an **error**-level event from a free-text message. See [info] for parameter semantics. Mirrors Swift `Owl.error(_:)`. */
    public fun error(
        message: String,
        screenName: String? = null,
        attributes: Map<String, String?> = emptyMap(),
        file: String = DEFAULT_FILE,
        function: String = DEFAULT_FUNCTION,
        line: Int = 0,
    ) {
        log(message, OwlLogLevel.ERROR, screenName, cleanAttributes(attributes), file, function, line)
    }

    /**
     * Report a [Throwable]. Extracts the runtime type, the JVM stack trace, and
     * the `Throwable.cause` chain (up to 5 deep) into `_error_*` reserved
     * attributes. The server's issue tracker uses `_error_type` as a fingerprint
     * discriminator so different error classes with the same wording stay on
     * separate issues. Mirrors Swift `Owl.error(_:Error)`.
     *
     * Pass an optional [message] to override the auto-derived event message with
     * caller context (e.g. `Owl.error(e, "while loading photos")`). SDK-owned
     * `_error_*` keys take precedence over any caller-provided values of the
     * same name, to keep fingerprinting + dashboard rendering consistent.
     */
    public fun error(
        error: Throwable,
        message: String? = null,
        screenName: String? = null,
        attributes: Map<String, String?> = emptyMap(),
        file: String = DEFAULT_FILE,
        function: String = DEFAULT_FUNCTION,
        line: Int = 0,
    ) {
        val extracted = ErrorExtraction.extract(error = error, userMessage = message)

        val merged = LinkedHashMap<String, String>()
        cleanAttributes(attributes)?.let { merged.putAll(it) }
        // SDK-owned `_error_*` keys overwrite any caller-provided same-named
        // values (Swift merges `extracted.attributes` on top).
        merged.putAll(extracted.attributes)

        log(extracted.message, OwlLogLevel.ERROR, screenName, merged, file, function, line)
    }

    // MARK: - Logging internals

    /** Fallback source-location defaults — Kotlin has no `#file`/`#function` literal. */
    private const val DEFAULT_FILE: String = "Unknown"
    private const val DEFAULT_FUNCTION: String = "unknown"

    /** Frames in this package are SDK internals — skipped during call-site capture. */
    private const val SDK_PACKAGE: String = "com.owlmetry.android"

    /**
     * Best-effort call-site capture — the Android analog of Swift's compile-time
     * `#file`/`#function`/`#line`. Walks the current stack to the first frame
     * outside the SDK package and returns (fileName, methodName, lineNumber),
     * e.g. ("MainActivity.kt", "onCreate", 42) → source_module
     * "MainActivity.kt:onCreate:42". Falls back to the placeholders when no
     * caller frame is found (e.g. an SDK-internal emit with no app frame). Only
     * invoked when the caller didn't pass an explicit call site, so apps that
     * care about the stack-fill cost can supply file/function/line directly.
     */
    private fun resolveCallSite(): Triple<String, String, Int> {
        for (frame in Throwable().stackTrace) {
            if (!frame.className.startsWith(SDK_PACKAGE)) {
                val fileName = frame.fileName ?: (frame.className.substringAfterLast('.') + ".kt")
                return Triple(fileName, frame.methodName, frame.lineNumber)
            }
        }
        return Triple(DEFAULT_FILE, DEFAULT_FUNCTION, 0)
    }

    /**
     * Filter out null values from caller-supplied [attributes] so optional
     * strings can flow through `attributes:` without unwrapping at the call
     * site. Returns null when nothing remains, matching the "no custom
     * attributes" path through the pipeline. Mirrors Swift's `cleanAttributes`.
     */
    internal fun cleanAttributes(attributes: Map<String, String?>): Map<String, String>? {
        val filtered = LinkedHashMap<String, String>(attributes.size)
        for ((key, value) in attributes) {
            if (value != null) filtered[key] = value
        }
        return if (filtered.isEmpty()) null else filtered
    }

    /**
     * Suspend until every in-flight [log] coroutine has reached the
     * [EventTransport] buffer. Used by [setUser] and the configure-time startup
     * reclaim so outbound writes that depend on prior events being ingestible
     * never race ahead of them. Returns immediately when the counter is already
     * zero. Mirrors Swift's `awaitInFlightLogTasks()`.
     */
    internal suspend fun awaitInFlightLogTasks() {
        val deferred: CompletableDeferred<Unit> = synchronized(lock) {
            if (inFlightLogTasks == 0) return
            CompletableDeferred<Unit>().also { pendingLogDrains.add(it) }
        }
        deferred.await()
    }

    /**
     * The shared event-emit path behind every public logging method. Snapshots
     * configured state under [lock], echoes to the console, builds the
     * [LogEvent] (stamping identity, session, device info, network status,
     * source location), bumps the in-flight counter, then spawns a coroutine
     * that runs the [DuplicateFilter] gate and enqueues onto the transport —
     * decrementing the counter and releasing any [awaitInFlightLogTasks] waiters
     * on exit. Mirrors Swift's private `Owl.log(...)`.
     *
     * Before [configure] this is a no-op (warned once via Logcat), matching
     * Swift's "Owl.configure() has not been called. Events are being dropped."
     */
    internal fun log(
        message: String,
        level: OwlLogLevel,
        screenName: String?,
        attributes: Map<String, String>?,
        file: String = DEFAULT_FILE,
        function: String = DEFAULT_FUNCTION,
        line: Int = 0,
    ) {
        val snapshot: LogSnapshot = synchronized(lock) {
            val s = state
            if (s == null) {
                if (!hasWarnedNotConfigured) {
                    hasWarnedNotConfigured = true
                    android.util.Log.w(
                        ConsoleLogger.TAG,
                        "Owl.configure() has not been called. Events are being dropped.",
                    )
                }
                return
            }
            // Bump the in-flight counter synchronously so a setUser/claim
            // coroutine spawned right after this `log` call sees
            // inFlightLogTasks > 0 and waits for us. The coroutine body
            // decrements + signals waiters on exit.
            inFlightLogTasks += 1
            LogSnapshot(
                deviceInfo = s.deviceInfo,
                transport = s.transport,
                duplicateFilter = s.duplicateFilter,
                userId = s.defaultUserId,
                sessionId = s.sessionId,
                isDev = s.isDev,
                networkStatus = s.networkMonitor.status.wire,
                consoleLogging = s.configuration.consoleLogging,
                scope = sdkScope,
            )
        }

        if (snapshot.consoleLogging) {
            ConsoleLogger.print(message, level, attributes)
        }

        // Kotlin has no #file/#function/#line. When the caller didn't supply an
        // explicit call site, derive it from the stack (the first frame outside
        // the SDK package) so source_module is a real "File.kt:method:line" like
        // Swift's auto-captured location — not a constant placeholder, which
        // would flatten the issue-tracker source dimension for every event.
        val (srcFile, srcFunction, srcLine) =
            if (file == DEFAULT_FILE && function == DEFAULT_FUNCTION && line == 0) {
                resolveCallSite()
            } else {
                Triple(file, function, line)
            }

        val event = EventBuilder.build(
            message = message,
            level = level,
            screenName = screenName,
            customAttributes = attributes,
            userId = snapshot.userId,
            sessionId = snapshot.sessionId,
            deviceInfo = snapshot.deviceInfo,
            isDev = snapshot.isDev,
            networkStatus = snapshot.networkStatus,
            file = srcFile,
            function = srcFunction,
            line = srcLine,
        )

        val scope = snapshot.scope
        if (scope == null) {
            // No scope to enqueue on (mid-teardown race) — undo the bump and
            // release waiters so the gate can't hang.
            decrementInFlightLogTasks()
            return
        }
        scope.launch {
            try {
                if (snapshot.duplicateFilter.shouldAllow(event)) {
                    snapshot.transport.enqueue(event)
                }
            } finally {
                decrementInFlightLogTasks()
            }
        }
    }

    /** Decrement the in-flight counter and release waiters once it hits zero. */
    private fun decrementInFlightLogTasks() {
        val waiters: List<CompletableDeferred<Unit>> = synchronized(lock) {
            if (inFlightLogTasks > 0) inFlightLogTasks -= 1
            if (inFlightLogTasks != 0) return@synchronized emptyList()
            val pending = pendingLogDrains.toList()
            pendingLogDrains.clear()
            pending
        }
        for (waiter in waiters) waiter.complete(Unit)
    }

    /** One-shot "not configured" warning guard, mirroring Swift's `hasWarnedNotConfigured`. */
    private var hasWarnedNotConfigured: Boolean = false

    /** The bundle of configured state [log] needs, snapshotted under the lock. */
    private data class LogSnapshot(
        val deviceInfo: DeviceInfo,
        val transport: EventTransport,
        val duplicateFilter: DuplicateFilter,
        val userId: String?,
        val sessionId: String,
        val isDev: Boolean,
        val networkStatus: String,
        val consoleLogging: Boolean,
        val scope: CoroutineScope?,
    )

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
        val drainsToResume: List<CompletableDeferred<Unit>>
        synchronized(lock) {
            state?.networkMonitor?.stop()
            sdkScope?.cancel()
            sdkScope = null
            state = null
            pendingUserId = null
            pendingClearUser = false
            pendingNewAnonymousId = false
            // Cancelling the scope orphans any spawned log coroutines before
            // they decrement the counter, so reset the in-flight accounting and
            // release any waiters so a `awaitInFlightLogTasks()` left suspended
            // across a reset can't hang forever.
            inFlightLogTasks = 0
            drainsToResume = pendingLogDrains.toList()
            pendingLogDrains.clear()
            hasWarnedNotConfigured = false
        }
        for (waiter in drainsToResume) waiter.complete(Unit)
    }
}
