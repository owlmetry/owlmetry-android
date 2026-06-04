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
        // The bundle id, snapshotted so the questionnaire dismiss/save paths can
        // read it without re-resolving the configuration.
        val bundleId: String,
        // Persistent launch/foreground/first-launch counters backing the
        // questionnaire trigger conditions. Also published to
        // OwlQuestionnaireState.shared so the Compose gate can read it.
        val questionnaireState: OwlQuestionnaireState,
        // Uploads event attachments (reserve + PUT). Held so the logging path
        // can enqueue attachments tagged with the event's client_event_id.
        val attachmentUploader: AttachmentUploader,
        // Process-lifecycle observer that flushes the transport on background
        // and bumps the foreground counter. Null when flushOnBackground is off
        // (matches Swift). Held so it can be stopped on reset/re-configure.
        val lifecycleObserver: LifecycleObserver?,
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
        val questionnaireState = OwlQuestionnaireState(appContext)
        val attachmentUploader = AttachmentUploader(
            endpoint = configuration.endpoint.toURL(),
            apiKey = configuration.apiKey,
            scope = scope,
            httpClient = httpClientOverrideForTesting ?: DefaultHttpClient,
        )
        // The process-lifecycle flush observer — only when flushOnBackground is
        // on (Swift gates the same way). Constructed here but registered after
        // the state is published (it touches the main-thread lifecycle owner).
        val lifecycleObserver: LifecycleObserver? =
            if (configuration.flushOnBackground) {
                LifecycleObserver(transport = transport, scope = scope)
            } else {
                null
            }

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
                bundleId = configuration.bundleId,
                questionnaireState = questionnaireState,
                attachmentUploader = attachmentUploader,
                lifecycleObserver = lifecycleObserver,
            )
            // Publish the state handle so the Compose trigger gate + the static
            // launchCount/foregroundCount accessors can read counters without a
            // Context. Mirrors Swift's `OwlQuestionnaireState.shared`.
            OwlQuestionnaireState.shared = questionnaireState
        }

        // Start the flush loop + the dedup-cleanup loop. Both run on the SDK
        // scope; the filter's cleanup mirrors Swift's `filter.start()`.
        transport.start()
        duplicateFilter.start(scope)

        // Register the background-flush observer with the process lifecycle.
        // Done after publishing state so the first ON_START it may immediately
        // receive (if the app is already foregrounded) sees a configured SDK.
        // Mirrors Swift's `lifecycleObserver?.start()`.
        lifecycleObserver?.start()

        // Bump the launch counter (idempotent per process) + record the install
        // timestamp on first launch. Backs the questionnaire trigger conditions.
        // Mirrors Swift's `OwlQuestionnaireState.shared.markConfiguredOnce()` at
        // the tail of `configureWith`.
        questionnaireState.markConfiguredOnce()

        // Emit the session-start event. Mirrors Swift's
        // `log("sdk:session_started", ...)` at the end of `configureWith`,
        // including the `_launch_ms` attribute when a process-start time is
        // available. Swift reads process start via sysctl; the Android analog is
        // `Process.getStartUptimeMillis()` (API 24+) against the same uptime
        // clock — process start → now (this configure), the time-to-SDK-ready
        // telemetry. Best-effort: omitted when non-positive or unavailable.
        val launchMs = processLaunchDurationMs()
        log(
            message = "sdk:session_started",
            level = OwlLogLevel.INFO,
            screenName = null,
            attributes = launchMs?.let { mapOf("_launch_ms" to it.toString()) },
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
        attachments: List<OwlAttachment>? = null,
        file: String = DEFAULT_FILE,
        function: String = DEFAULT_FUNCTION,
        line: Int = 0,
    ) {
        log(message, OwlLogLevel.INFO, screenName, cleanAttributes(attributes), attachments, file, function, line)
    }

    /** Log a **debug**-level event. See [info] for parameter semantics. Mirrors Swift `Owl.debug(_:)`. */
    public fun debug(
        message: String,
        screenName: String? = null,
        attributes: Map<String, String?> = emptyMap(),
        attachments: List<OwlAttachment>? = null,
        file: String = DEFAULT_FILE,
        function: String = DEFAULT_FUNCTION,
        line: Int = 0,
    ) {
        log(message, OwlLogLevel.DEBUG, screenName, cleanAttributes(attributes), attachments, file, function, line)
    }

    /** Log a **warn**-level event. See [info] for parameter semantics. Mirrors Swift `Owl.warn(_:)`. */
    public fun warn(
        message: String,
        screenName: String? = null,
        attributes: Map<String, String?> = emptyMap(),
        attachments: List<OwlAttachment>? = null,
        file: String = DEFAULT_FILE,
        function: String = DEFAULT_FUNCTION,
        line: Int = 0,
    ) {
        log(message, OwlLogLevel.WARN, screenName, cleanAttributes(attributes), attachments, file, function, line)
    }

    /** Log an **error**-level event from a free-text message. See [info] for parameter semantics. Mirrors Swift `Owl.error(_:)`. */
    public fun error(
        message: String,
        screenName: String? = null,
        attributes: Map<String, String?> = emptyMap(),
        attachments: List<OwlAttachment>? = null,
        file: String = DEFAULT_FILE,
        function: String = DEFAULT_FUNCTION,
        line: Int = 0,
    ) {
        log(message, OwlLogLevel.ERROR, screenName, cleanAttributes(attributes), attachments, file, function, line)
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
        attachments: List<OwlAttachment>? = null,
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

        log(extracted.message, OwlLogLevel.ERROR, screenName, merged, attachments, file, function, line)
    }

    // MARK: - User Properties

    /**
     * Set custom properties on the current user. Properties are **merged**
     * server-side — existing keys not in this call are preserved. Pass an empty
     * string value to remove a property. Mirrors Swift `Owl.setUserProperties`.
     *
     * No-op before [configure] (no resolved user id / transport yet). When
     * configured, the POST is gated behind [awaitInFlightLogTasks] — symmetric
     * with [setUser] — so any in-flight `Owl.log(...)` coroutines reach the
     * transport first and the server's `app_users` upsert sequencing stays
     * consistent.
     */
    public fun setUserProperties(properties: Map<String, String>) {
        val (userId, transport) = synchronized(lock) {
            val s = state ?: return
            s.defaultUserId to s.transport
        }
        sdkScope?.launch {
            awaitInFlightLogTasks()
            transport.setUserProperties(userId = userId, properties = properties)
        }
    }

    // MARK: - Questionnaires

    /**
     * Total number of times [configure] has completed since install (one bump
     * per process). Backs `OwlQuestionnaireCondition.Launches`. Mirrors Swift
     * `Owl.launchCount`. Zero before the first [configure].
     */
    public val launchCount: Int
        get() = OwlQuestionnaireState.shared?.launchCount ?: 0

    /**
     * Total number of foreground transitions since install. Backs
     * `OwlQuestionnaireCondition.Foregrounds`. Mirrors Swift `Owl.foregroundCount`.
     */
    public val foregroundCount: Int
        get() = OwlQuestionnaireState.shared?.foregroundCount ?: 0

    /**
     * Epoch-millis timestamp of the first-ever [configure] on this install, or
     * null if none recorded. Mirrors Swift `Owl.firstLaunchAt` (a `Date` there;
     * epoch millis here, matching [OwlQuestionnaireState]).
     */
    public val firstLaunchAt: Long?
        get() = OwlQuestionnaireState.shared?.firstLaunchAt

    /**
     * Fetch a questionnaire by slug, plus any in-progress draft the caller
     * already started. The result's `questionnaire` is null when the user is
     * ineligible (already responded, globally dismissed, or the questionnaire is
     * inactive — in that case `ineligibleReason` is set). Mirrors Swift
     * `Owl.fetchQuestionnaire(slug:force:)`.
     *
     * Pass [force] = true to ask the server to ignore `alreadyResponded` and
     * `globallyDismissed` — useful for previewing the questionnaire UI in debug
     * builds without resetting state. `inactive` is still respected.
     *
     * @throws OwlQuestionnaireError.NotConfigured if [configure] has not run.
     * @throws OwlQuestionnaireError.SlugNotFound if the slug doesn't exist.
     * @throws OwlQuestionnaireError.ServerError / TransportFailure on failure.
     */
    public suspend fun fetchQuestionnaire(
        slug: String,
        force: Boolean = false,
    ): OwlQuestionnaireFetchResult {
        val snapshot = transportSnapshot() ?: throw OwlQuestionnaireError.NotConfigured
        return when (val outcome = snapshot.transport.fetchQuestionnaire(slug = slug, userId = snapshot.userId, force = force)) {
            is QuestionnaireFetchOutcome.Success -> outcome.result
            is QuestionnaireFetchOutcome.Failure -> throw outcome.error
        }
    }

    /**
     * Save a partial answer set ([isComplete] false) or finalize a submission
     * ([isComplete] true). The server upserts by `(project, slug, user_id)`, so
     * the SDK doesn't track the response id across calls — every save sends the
     * full accumulated answer set and the server merges. The returned receipt's
     * `wasSubmitted` is `true` exactly once per response (the call that flipped
     * `submitted_at` from null) and drives the flow container's success
     * transition. Telemetry: emits `sdk:questionnaire_submitted` only on the
     * flip — silent on partial saves to avoid flooding the event stream. Mirrors
     * Swift `Owl.saveQuestionnaireResponse`.
     */
    public suspend fun saveQuestionnaireResponse(
        slug: String,
        answers: Map<String, OwlQuestionnaireAnswerValue>,
        isComplete: Boolean,
    ): OwlQuestionnaireReceipt {
        val snapshot = transportSnapshot() ?: throw OwlQuestionnaireError.NotConfigured

        val outcome = snapshot.transport.saveQuestionnaireResponse(
            slug = slug,
            userId = snapshot.userId,
            sessionId = snapshot.sessionId,
            answers = answers,
            isComplete = isComplete,
            deviceInfo = snapshot.deviceInfo,
            environment = snapshot.deviceInfo.platform.wire,
            appVersion = snapshot.deviceInfo.appVersion,
            isDev = snapshot.isDev,
        )
        return when (outcome) {
            is QuestionnaireSaveOutcome.Success -> {
                if (outcome.receipt.wasSubmitted) {
                    log(
                        message = "sdk:questionnaire_submitted",
                        level = OwlLogLevel.INFO,
                        screenName = null,
                        attributes = mapOf("slug" to slug),
                        file = "Owl.kt",
                        function = "saveQuestionnaireResponse",
                        line = 0,
                    )
                }
                outcome.receipt
            }
            is QuestionnaireSaveOutcome.Failure -> throw outcome.error
        }
    }

    /**
     * Globally opt the current user out of every questionnaire. Idempotent.
     * Survives reinstall server-side (stored in `app_users.properties`). Mirrors
     * Swift `Owl.dismissQuestionnaires`. Returns the server's `dismissed_at`.
     *
     * @throws OwlQuestionnaireError.NotConfigured if [configure] has not run.
     */
    public suspend fun dismissQuestionnaires(): java.util.Date {
        val snapshot = transportSnapshot()
        val userId = snapshot?.userId
        if (snapshot == null || userId == null) throw OwlQuestionnaireError.NotConfigured
        return when (val outcome = snapshot.transport.submitQuestionnaireDismiss(userId = userId)) {
            is QuestionnaireDismissOutcome.Success -> {
                log(
                    message = "sdk:questionnaire_dismissed",
                    level = OwlLogLevel.INFO,
                    screenName = null,
                    attributes = null,
                    file = "Owl.kt",
                    function = "dismissQuestionnaires",
                    line = 0,
                )
                outcome.dismissedAt
            }
            is QuestionnaireDismissOutcome.Failure -> throw outcome.error
        }
    }

    // Process-local dedupe so a single launch doesn't re-present the same slug
    // even if the gate's evaluation re-enters or two modifiers with the same
    // slug both fire. Cross-launch dedupe is the server's job. Guarded by
    // [shownLock]; mirrors Swift's `shownSlugs` set.
    private val shownLock = Any()
    private val shownSlugs = HashSet<String>()

    /** Whether [slug] has already been presented in this process. */
    public fun questionnaireWasShownThisProcess(slug: String): Boolean =
        synchronized(shownLock) { shownSlugs.contains(slug) }

    /** Mark [slug] as presented in this process. */
    public fun markQuestionnaireShown(slug: String) {
        synchronized(shownLock) { shownSlugs.add(slug) }
    }

    /**
     * Debug-only — clears the in-process "already shown this slug" cache so the
     * Compose trigger can re-evaluate without an app relaunch. Intended for QA
     * and demo apps. Server-side eligibility (already-responded,
     * globally-dismissed) is NOT affected — combine with
     * `Owl.clearUser(newAnonymousId = true)` to test against a fresh user.
     * Mirrors Swift `Owl._debugClearShownQuestionnaires`.
     */
    public fun debugClearShownQuestionnaires() {
        synchronized(shownLock) { shownSlugs.clear() }
    }

    /** Snapshot of the configured state the questionnaire calls need. */
    private data class QuestionnaireTransportSnapshot(
        val transport: EventTransport,
        val userId: String?,
        val sessionId: String,
        val deviceInfo: DeviceInfo,
        val isDev: Boolean,
    )

    /**
     * Snapshot the transport-side state for a questionnaire call under [lock],
     * or null (warned once) before [configure]. Mirrors Swift's
     * `transportSnapshot()`.
     */
    private fun transportSnapshot(): QuestionnaireTransportSnapshot? = synchronized(lock) {
        val s = state
        if (s == null) {
            if (!hasWarnedNotConfigured) {
                hasWarnedNotConfigured = true
                android.util.Log.w(
                    ConsoleLogger.TAG,
                    "Owl.configure() has not been called. Questionnaire call dropped.",
                )
            }
            return@synchronized null
        }
        QuestionnaireTransportSnapshot(
            transport = s.transport,
            userId = s.defaultUserId,
            sessionId = s.sessionId,
            deviceInfo = s.deviceInfo,
            isDev = s.isDev,
        )
    }

    // MARK: - Feedback

    /**
     * Submit free-text user feedback to Owlmetry. Mirrors Swift
     * `Owl.sendFeedback`.
     *
     * Unlike events, feedback is **not** offline-queued — it's a one-shot,
     * interactive submission: the caller awaits the result (a returned
     * [OwlFeedbackReceipt]) and surfaces success/failure in its own UI. The
     * server auto-attaches session id, user id, app version, SDK version,
     * environment, device model, and OS version from the configured state; the
     * caller supplies only the message and optional contact details.
     *
     * Whitespace is trimmed from [message]; an empty/whitespace-only message
     * throws [OwlFeedbackError.EmptyMessage] before any network call. [name] and
     * [email] are trimmed and dropped when blank.
     *
     * On success an audit event `sdk:feedback_submitted` is emitted into the
     * normal event stream (so feedback is observable alongside other activity),
     * tagged `has_email` / `has_name`.
     *
     * @throws OwlFeedbackError.NotConfigured if [configure] has not been called.
     * @throws OwlFeedbackError.EmptyMessage if [message] is blank.
     * @throws OwlFeedbackError.ServerError on a non-2xx response.
     * @throws OwlFeedbackError.TransportFailure on a network/encode/decode failure.
     */
    public suspend fun sendFeedback(
        message: String,
        name: String? = null,
        email: String? = null,
    ): OwlFeedbackReceipt {
        val trimmed = message.trim()
        if (trimmed.isEmpty()) throw OwlFeedbackError.EmptyMessage

        val snapshot: FeedbackSnapshot? = synchronized(lock) {
            val s = state
            if (s == null) {
                if (!hasWarnedNotConfigured) {
                    hasWarnedNotConfigured = true
                    android.util.Log.w(
                        ConsoleLogger.TAG,
                        "Owl.configure() has not been called. sendFeedback dropped.",
                    )
                }
                return@synchronized null
            }
            FeedbackSnapshot(
                transport = s.transport,
                bundleId = s.configuration.bundleId,
                deviceInfo = s.deviceInfo,
                userId = s.defaultUserId,
                sessionId = s.sessionId,
                isDev = s.isDev,
            )
        }

        if (snapshot == null) throw OwlFeedbackError.NotConfigured
        val transport = snapshot.transport

        fun trimNil(s: String?): String? {
            if (s == null) return null
            val t = s.trim()
            return if (t.isEmpty()) null else t
        }

        val cleanedName = trimNil(name)
        val cleanedEmail = trimNil(email)

        val body = FeedbackRequestBody(
            bundleId = snapshot.bundleId,
            message = trimmed,
            sessionId = snapshot.sessionId,
            userId = snapshot.userId,
            submitterName = cleanedName,
            submitterEmail = cleanedEmail,
            appVersion = snapshot.deviceInfo.appVersion,
            sdkName = OwlmetryVersion.NAME,
            sdkVersion = OwlmetryVersion.CURRENT,
            environment = snapshot.deviceInfo.platform.wire,
            deviceModel = snapshot.deviceInfo.deviceModel,
            osVersion = snapshot.deviceInfo.osVersion,
            isDev = snapshot.isDev,
        )

        return when (val result = transport.submitFeedback(body)) {
            is FeedbackResult.Success -> {
                // Audit trail — feedback submission is observable in the event
                // stream. Pass an explicit, stable source so source_module is
                // "Owl.kt:sendFeedback" (Swift uses #file/#function/#line of the
                // SDK call site here, not the consumer's location).
                log(
                    message = "sdk:feedback_submitted",
                    level = OwlLogLevel.INFO,
                    screenName = null,
                    attributes = mapOf(
                        "has_email" to (if (cleanedEmail != null) "true" else "false"),
                        "has_name" to (if (cleanedName != null) "true" else "false"),
                    ),
                    file = "Owl.kt",
                    function = "sendFeedback",
                    line = 0,
                )
                result.receipt
            }
            is FeedbackResult.Failure -> throw result.error
        }
    }

    /** The bundle of configured state [sendFeedback] needs, snapshotted under the lock. */
    private data class FeedbackSnapshot(
        val transport: EventTransport,
        val bundleId: String,
        val deviceInfo: DeviceInfo,
        val userId: String?,
        val sessionId: String,
        val isDev: Boolean,
    )

    // MARK: - Funnel Steps

    /**
     * Record a funnel step. Emits an **info**-level event with message
     * `"step:<stepName>"`. Mirrors Swift `Owl.step(_:)`.
     *
     * See [info] for [attributes] / [screenName] / call-site parameter
     * semantics. [attributes] values may be null; nulls are dropped.
     */
    public fun step(
        stepName: String,
        attributes: Map<String, String?> = emptyMap(),
        file: String = DEFAULT_FILE,
        function: String = DEFAULT_FUNCTION,
        line: Int = 0,
    ) {
        info("step:$stepName", attributes = attributes, file = file, function = function, line = line)
    }

    /**
     * Record a funnel step.
     *
     * @deprecated Use [step] instead. Retained for source compatibility with the
     * legacy `track:` prefix (see [ConsoleLogger]); forwards to [step], so the
     * emitted message is `"step:<stepName>"`, identical to Swift's deprecated
     * `Owl.track(_:)` which also forwards to `step`.
     */
    @Deprecated("Use step(stepName, ...) instead.", ReplaceWith("step(stepName, attributes, file, function, line)"))
    public fun track(
        stepName: String,
        attributes: Map<String, String?> = emptyMap(),
        file: String = DEFAULT_FILE,
        function: String = DEFAULT_FUNCTION,
        line: Int = 0,
    ) {
        step(stepName, attributes = attributes, file = file, function = function, line = line)
    }

    // MARK: - Structured Metrics

    /**
     * Start a tracked operation. Returns an [OwlOperation] whose [complete][OwlOperation.complete],
     * [fail][OwlOperation.fail], or [cancel][OwlOperation.cancel] method should be
     * called when the operation finishes. Mirrors Swift `Owl.startOperation`.
     *
     * The [metric] slug should contain only lowercase letters, numbers, and
     * hyphens (e.g. `"photo-conversion"`, `"api-request"`). Invalid characters
     * are auto-corrected via [normalizeSlug] with a warning logged.
     *
     * Emits an **info**-level `metric:<slug>:start` event tagged with the new
     * operation's `tracking_id`.
     */
    public fun startOperation(
        metric: String,
        attributes: Map<String, String?> = emptyMap(),
        file: String = DEFAULT_FILE,
        function: String = DEFAULT_FUNCTION,
        line: Int = 0,
    ): OwlOperation {
        val slug = normalizeSlug(metric)
        val op = OwlOperation(metric = slug)
        val attrs = LinkedHashMap<String, String?>(attributes)
        attrs["tracking_id"] = op.trackingId
        info("metric:$slug:start", attributes = attrs, file = file, function = function, line = line)
        return op
    }

    /**
     * Record a single-shot metric (no lifecycle). Emits an **info**-level
     * `metric:<slug>:record` event. Mirrors Swift `Owl.recordMetric`.
     *
     * The [metric] slug should contain only lowercase letters, numbers, and
     * hyphens (e.g. `"onboarding"`, `"checkout"`). Invalid characters are
     * auto-corrected via [normalizeSlug] with a warning logged.
     */
    public fun recordMetric(
        metric: String,
        attributes: Map<String, String?> = emptyMap(),
        file: String = DEFAULT_FILE,
        function: String = DEFAULT_FUNCTION,
        line: Int = 0,
    ) {
        val slug = normalizeSlug(metric)
        info("metric:$slug:record", attributes = attributes, file = file, function = function, line = line)
    }

    /** Matches a slug already containing only lowercase letters, numbers, and hyphens. */
    private val slugRegex = Regex("^[a-z0-9-]+$")

    /**
     * Normalize a metric/funnel slug to lowercase letters, numbers, and hyphens
     * only; logs a warning (via Logcat — the analog of Swift's `logger.warning`)
     * when the slug was modified. Mirrors Swift's private `normalizeSlug`:
     * already-valid slugs pass through untouched; otherwise lowercase →
     * replace every run of invalid chars (each char individually) with `-` →
     * collapse `--+` to `-` → strip leading/trailing `-`.
     */
    internal fun normalizeSlug(slug: String): String {
        if (slugRegex.matches(slug)) return slug
        var normalized = slug.lowercase()
        // Swift replaces each invalid char with a single "-" (no global collapse
        // in the first pass), then collapses runs — replicate exactly so the
        // result is byte-identical.
        normalized = normalized.replace(Regex("[^a-z0-9-]"), "-")
        normalized = normalized.replace(Regex("-{2,}"), "-")
        normalized = normalized.trim('-')
        android.util.Log.w(
            ConsoleLogger.TAG,
            "Metric slug \"$slug\" was auto-corrected to \"$normalized\". " +
                "Slugs should contain only lowercase letters, numbers, and hyphens.",
        )
        return normalized
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
        attachments: List<OwlAttachment>? = null,
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
                attachmentUploader = s.attachmentUploader,
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

        // Upload any attachments tagged with this event's client_event_id, on a
        // separate coroutine so the event-enqueue path above isn't blocked by
        // the (potentially large) upload. Mirrors Swift's separate
        // `Task { await uploader.enqueue(...) }`. The uploader serializes its own
        // queue, so concurrent log calls with attachments don't race.
        if (!attachments.isNullOrEmpty()) {
            scope.launch {
                snapshot.attachmentUploader.enqueue(
                    clientEventId = event.clientEventId,
                    userId = snapshot.userId,
                    isDev = snapshot.isDev,
                    attachments = attachments,
                )
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
        val attachmentUploader: AttachmentUploader,
    )

    /**
     * `is_dev` derivation. The Swift SDK keys off the consuming app's build
     * configuration; on Android the analog is the host app's
     * `FLAG_DEBUGGABLE` — true for debug builds, false for release. This reads
     * the *consuming app's* manifest flag, not the SDK's own BuildConfig.
     */
    internal fun resolveIsDev(context: Context): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /**
     * Milliseconds from process start to now, or null if non-positive /
     * unavailable. The Android analog of Swift's sysctl-based
     * `processLaunchDurationMs()`. `Process.getStartUptimeMillis()` (API 24+,
     * our minSdk) returns the process start instant on the same monotonic
     * uptime clock as `SystemClock.uptimeMillis()`, so the difference is a clean
     * "time since the process launched" measurement — exactly the signal Swift
     * captures, used for the `_launch_ms` attribute on `sdk:session_started`.
     */
    private fun processLaunchDurationMs(): Long? {
        val startUptime = android.os.Process.getStartUptimeMillis()
        val ms = android.os.SystemClock.uptimeMillis() - startUptime
        return if (ms > 0) ms else null
    }

    /**
     * Flush all buffered events and stop the background-flush observer. The
     * suspending analog of Swift's `Owl.shutdown()`. Call before a deliberate
     * teardown (e.g. process exit in a test harness) to drain the buffer; the
     * SDK can be [configure]d again afterward.
     *
     * No-op before [configure]. Unlike [resetForTesting] this does not tear down
     * the SDK scope or clear identity — it only stops the lifecycle observer and
     * drives the transport's own `shutdown()` (cancel flush loop + `flushAll`).
     */
    public suspend fun shutdown() {
        val (transport, observer) = synchronized(lock) {
            val s = state ?: return
            s.transport to s.lifecycleObserver
        }
        observer?.stop()
        transport.shutdown()
    }

    /** Test/teardown hook — clears configured state + any stashed identity ops. */
    internal fun resetForTesting() {
        val drainsToResume: List<CompletableDeferred<Unit>>
        synchronized(lock) {
            state?.lifecycleObserver?.stop()
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
            OwlQuestionnaireState.shared = null
        }
        synchronized(shownLock) { shownSlugs.clear() }
        for (waiter in drainsToResume) waiter.complete(Unit)
    }
}
