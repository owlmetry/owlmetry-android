package com.owlmetry.android

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.min
import kotlin.math.pow

/**
 * Batches, buffers, retries, and POSTs events to the Owlmetry ingest API. The
 * Android analog of the Swift SDK's `EventTransport` actor.
 *
 * Swift's `EventTransport` is an `actor` (serialized access to `buffer`), with a
 * background flush `Task` looping every 5 s, batching 20 events per `/v1/ingest`
 * POST, gzip-compressing bodies ≥ 512 B, retrying transport/5xx failures up to 5
 * times with exponential backoff capped at 30 s, and routing undelivered batches
 * to the [OfflineQueue]. This port mirrors all of that:
 *  - a [Mutex] serializes `buffer` mutation (the actor analog under the
 *    coroutines-only dependency rule),
 *  - a flush loop launched on [scope] (`while isActive { delay(5s); flush() }`)
 *    is the analog of Swift's flush `Task`,
 *  - HTTP runs on [HttpURLConnection] (the framework-only analog of `URLSession`)
 *    dispatched to [Dispatchers.IO].
 *
 * `claimIdentity` reproduces the production-critical in-flight-send drain: it
 * waits for every parallel `/v1/ingest` POST started by the auto-flush /
 * periodic flush / flushAll loop to return before POSTing the claim, so the
 * server's `UPDATE events` can't run while ingest POSTs are mid-transaction and
 * orphan rows under the anon id. See CLAUDE.md "Identity".
 */
internal class EventTransport(
    endpoint: URL,
    private val apiKey: String,
    private val bundleId: String,
    private val compressionEnabled: Boolean,
    private val offlineQueue: OfflineQueue,
    private val networkMonitor: Reachability,
    private val scope: CoroutineScope,
    private val httpClient: HttpClient = DefaultHttpClient,
    // The dispatcher blocking HTTP runs on — [Dispatchers.IO] in production,
    // overridable in tests so HTTP shares the test scheduler and stays
    // deterministic. The analog of injecting a `URLSession` into the Swift
    // transport to make sends synchronous under test.
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val ingestUrl = endpoint.appendPath("v1/ingest")
    private val claimUrl = endpoint.appendPath("v1/identity/claim")
    private val propertiesUrl = endpoint.appendPath("v1/identity/properties")
    private val feedbackUrl = endpoint.appendPath("v1/feedback")

    private val bufferMutex = Mutex()
    private val buffer = ArrayList<LogEvent>()
    private var flushJob: Job? = null

    // In-flight ingest sends. `claimIdentity` waits for this to drain to zero
    // before POSTing the claim — mirrors Swift's `inFlightSendCount` +
    // `sendDrainContinuations`. Guarded by [inFlightMutex].
    private val inFlightMutex = Mutex()
    private var inFlightSendCount = 0
    private val sendDrainContinuations = ArrayList<Continuation<Unit>>()

    companion object {
        private const val TAG = "Owlmetry.transport"

        private const val BATCH_SIZE = 20
        private const val MAX_BUFFER_SIZE = 10_000
        private const val FLUSH_INTERVAL_MS = 5_000L
        private const val MAX_RETRIES = 5
        private const val MAX_BACKOFF_SECONDS = 30.0
        private const val COMPRESSION_THRESHOLD = 512
    }

    /** Start the periodic flush loop. Idempotent. Mirrors Swift's `start()`. */
    fun start() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            while (isActive) {
                delay(FLUSH_INTERVAL_MS)
                flush()
            }
        }
    }

    /** Cancel the flush loop and drain everything. Mirrors Swift's `shutdown()`. */
    suspend fun shutdown() {
        flushJob?.cancel()
        flushJob = null
        flushAll()
    }

    /** Buffer one event, auto-flushing at [BATCH_SIZE]. Mirrors Swift's `enqueue(_:)`. */
    suspend fun enqueue(event: LogEvent) {
        val shouldFlush = bufferMutex.withLock {
            buffer.add(event)
            trimBuffer()
            buffer.size >= BATCH_SIZE
        }
        if (shouldFlush) scope.launch { flush() }
    }

    /** Buffer a batch, auto-flushing at [BATCH_SIZE]. Mirrors Swift's `enqueue(_:[])`. */
    suspend fun enqueue(events: List<LogEvent>) {
        if (events.isEmpty()) return
        val shouldFlush = bufferMutex.withLock {
            buffer.addAll(events)
            trimBuffer()
            buffer.size >= BATCH_SIZE
        }
        if (shouldFlush) scope.launch { flush() }
    }

    /** Caller holds [bufferMutex]. Drop the oldest past [MAX_BUFFER_SIZE]. */
    private fun trimBuffer() {
        if (buffer.size > MAX_BUFFER_SIZE) {
            val overflow = buffer.size - MAX_BUFFER_SIZE
            repeat(overflow) { buffer.removeAt(0) }
        }
    }

    /**
     * Flush one batch. Prepends any offline-queued events, takes the first
     * [BATCH_SIZE], and POSTs them — routing back to the offline queue on
     * failure or when offline. Mirrors Swift's `flush()`.
     */
    suspend fun flush() {
        val offlineEvents = offlineQueue.drain()

        val batch = bufferMutex.withLock {
            if (offlineEvents.isNotEmpty()) buffer.addAll(0, offlineEvents)
            if (buffer.isEmpty()) return
            val take = min(BATCH_SIZE, buffer.size)
            val out = ArrayList(buffer.subList(0, take))
            repeat(take) { buffer.removeAt(0) }
            out
        }

        if (!networkMonitor.isConnected) {
            handleUndelivered(batch)
            return
        }

        if (!send(batch)) {
            handleUndelivered(batch)
        }
    }

    /**
     * Drain everything in batches until the buffer is empty. Used on shutdown
     * and before an identity claim. Mirrors Swift's `flushAll()`.
     */
    suspend fun flushAll() {
        val offlineEvents = offlineQueue.drain()
        bufferMutex.withLock {
            if (offlineEvents.isNotEmpty()) buffer.addAll(0, offlineEvents)
        }

        while (true) {
            val batch = bufferMutex.withLock {
                if (buffer.isEmpty()) return
                val take = min(BATCH_SIZE, buffer.size)
                val out = ArrayList(buffer.subList(0, take))
                repeat(take) { buffer.removeAt(0) }
                out
            }

            if (!networkMonitor.isConnected) {
                // Offline mid-drain: push this batch + the remainder back to the
                // offline queue and stop, matching Swift's `batch + buffer` path.
                val remainder = bufferMutex.withLock {
                    val rest = ArrayList(buffer)
                    buffer.clear()
                    rest
                }
                handleUndelivered(batch + remainder)
                return
            }

            if (!send(batch)) {
                handleUndelivered(batch)
            }
        }
    }

    /** Route an undelivered batch to the offline queue. Mirrors Swift's `handleUndelivered`. */
    private suspend fun handleUndelivered(batch: List<LogEvent>) {
        if (batch.isEmpty()) return
        offlineQueue.enqueue(batch)
    }

    /**
     * Move the in-memory buffer to the offline queue and force a disk write.
     * Called when the host app backgrounds (if `flushOnBackground` is off) so
     * buffered events survive process death. Mirrors Swift's `persistBufferToDisk`.
     */
    suspend fun persistBufferToDisk() {
        val pending = bufferMutex.withLock {
            if (buffer.isEmpty()) return
            val out = ArrayList(buffer)
            buffer.clear()
            out
        }
        offlineQueue.enqueue(pending)
        offlineQueue.persistNow()
    }

    /**
     * Retroactively associate previously-sent anonymous events with a real
     * user. Flushes the buffer, then waits for every in-flight ingest POST to
     * return before POSTing the claim — so the server's `UPDATE events` can't
     * miss rows still mid-transaction. Mirrors Swift's `claimIdentity`.
     */
    suspend fun claimIdentity(anonymousId: String, userId: String) {
        flushAll()
        awaitInFlightSends()

        val body = JSONObject().apply {
            put("anonymous_id", anonymousId)
            put("user_id", userId)
        }
        val request = makeRequest(claimUrl, body.toString().toByteArray(Charsets.UTF_8))
        val ok = performWithRetry(request, "Claim")
        if (ok) {
            Log.i(TAG, "Identity claimed: $anonymousId -> $userId")
        } else {
            Log.e(TAG, "Identity claim failed after $MAX_RETRIES attempts")
        }
    }

    /**
     * Set user properties on the server. Mirrors Swift's `setUserProperties`.
     */
    suspend fun setUserProperties(userId: String, properties: Map<String, String>) {
        val body = JSONObject().apply {
            put("user_id", userId)
            put("properties", JSONObject(properties as Map<*, *>))
        }
        val request = makeRequest(propertiesUrl, body.toString().toByteArray(Charsets.UTF_8))
        val ok = performWithRetry(request, "Properties")
        if (ok) {
            Log.i(TAG, "User properties set for $userId")
        } else {
            Log.e(TAG, "User properties update failed for $userId")
        }
    }

    /**
     * One-shot synchronous feedback submission. Mirrors Swift's
     * `submitFeedback(_:)`: encodes the body, POSTs it **once** (no retry, no
     * offline queueing — the caller handles errors and decides whether to retry),
     * and on 2xx parses `{ id, created_at }` into an [OwlFeedbackReceipt]. Unlike
     * ingest, feedback is interactive: the user is staring at a spinner, so a
     * single attempt with a typed failure is the right contract rather than
     * silently retrying for 30s.
     *
     * Returns [FeedbackResult.Success] with the receipt, or [FeedbackResult.Failure]
     * carrying a typed [OwlFeedbackError] (server non-2xx with the body verbatim,
     * or a transport/encode/decode failure).
     */
    suspend fun submitFeedback(payload: FeedbackRequestBody): FeedbackResult {
        val httpBody = runCatching { payload.toJsonString().toByteArray(Charsets.UTF_8) }
            .getOrElse {
                return FeedbackResult.Failure(
                    OwlFeedbackError.TransportFailure("encoding failed: ${it.message}"),
                )
            }
        val request = makeRequest(feedbackUrl, httpBody)

        val response = withContext(ioDispatcher) {
            runCatching { httpClient.execute(request) }
        }

        response.onSuccess { http ->
            if (http.statusCode in 200..299) {
                val receipt = runCatching {
                    OwlFeedbackReceipt.fromJson(JSONObject(http.body ?: ""))
                }.getOrElse {
                    return FeedbackResult.Failure(
                        OwlFeedbackError.TransportFailure("decode failed: ${it.message}"),
                    )
                }
                return FeedbackResult.Success(receipt)
            }
            return FeedbackResult.Failure(
                OwlFeedbackError.ServerError(statusCode = http.statusCode, body = http.body),
            )
        }.onFailure { error ->
            return FeedbackResult.Failure(
                OwlFeedbackError.TransportFailure(error.message ?: error.toString()),
            )
        }

        // Unreachable — onSuccess/onFailure both return — but the compiler can't
        // see that through the Result lambdas.
        return FeedbackResult.Failure(OwlFeedbackError.TransportFailure("no response"))
    }

    /**
     * POST one ingest batch, tracking it as in-flight so [claimIdentity] can
     * drain. Mirrors Swift's `send(_:)`.
     */
    private suspend fun send(events: List<LogEvent>): Boolean {
        val body = IngestRequestBody(bundleId, events).toJsonString().toByteArray(Charsets.UTF_8)
        val request = makeRequest(ingestUrl, body)

        inFlightMutex.withLock { inFlightSendCount += 1 }
        try {
            return performWithRetry(request, "Ingest")
        } finally {
            val waiters = inFlightMutex.withLock {
                inFlightSendCount -= 1
                if (inFlightSendCount == 0) {
                    val w = ArrayList(sendDrainContinuations)
                    sendDrainContinuations.clear()
                    w
                } else {
                    emptyList()
                }
            }
            for (waiter in waiters) waiter.resume(Unit)
        }
    }

    /**
     * Suspend until every in-flight ingest send has returned. Mirrors Swift's
     * `awaitInFlightSends()` (the continuation-drain pattern).
     */
    private suspend fun awaitInFlightSends() {
        val mustWait = inFlightMutex.withLock { inFlightSendCount > 0 }
        if (!mustWait) return
        suspendCoroutine { continuation: Continuation<Unit> ->
            scope.launch {
                val resumeNow = inFlightMutex.withLock {
                    if (inFlightSendCount == 0) {
                        true
                    } else {
                        sendDrainContinuations.add(continuation)
                        false
                    }
                }
                if (resumeNow) continuation.resume(Unit)
            }
        }
    }

    // MARK: - HTTP

    private fun makeRequest(url: URL, body: ByteArray): HttpRequest {
        val headers = LinkedHashMap<String, String>()
        headers["Content-Type"] = "application/json"
        headers["Authorization"] = "Bearer $apiKey"

        val finalBody: ByteArray
        if (compressionEnabled && body.size >= COMPRESSION_THRESHOLD) {
            finalBody = GzipCompressor.gzip(body)
            headers["Content-Encoding"] = "gzip"
        } else {
            finalBody = body
        }
        return HttpRequest(url = url, method = "POST", headers = headers, body = finalBody)
    }

    /**
     * Execute [request] with retries. Does not retry 4xx (client errors won't
     * succeed); retries transport failures + 5xx up to [MAX_RETRIES] with
     * exponential backoff capped at [MAX_BACKOFF_SECONDS]. Mirrors Swift's
     * `performWithRetry`.
     */
    private suspend fun performWithRetry(request: HttpRequest, label: String): Boolean {
        for (attempt in 0 until MAX_RETRIES) {
            val result = withContext(ioDispatcher) {
                runCatching { httpClient.execute(request) }
            }

            result.onSuccess { response ->
                val code = response.statusCode
                if (code in 200..299) {
                    parseIngestRejections(response.body)?.let { rejected ->
                        if (rejected > 0) Log.w(TAG, "Server rejected $rejected events")
                    }
                    return true
                }
                if (code in 400..499) {
                    Log.w(TAG, "$label returned $code, not retrying")
                    return false
                }
                Log.w(TAG, "$label returned $code, attempt ${attempt + 1}/$MAX_RETRIES")
            }.onFailure { error ->
                Log.w(TAG, "$label failed: ${error.message}, attempt ${attempt + 1}/$MAX_RETRIES")
            }

            if (attempt < MAX_RETRIES - 1) {
                val backoff = min(2.0.pow(attempt), MAX_BACKOFF_SECONDS)
                delay((backoff * 1000).toLong())
            }
        }
        return false
    }

    /** Pull `rejected` out of a 2xx ingest body if present. Best-effort. */
    private fun parseIngestRejections(body: String?): Int? {
        if (body.isNullOrEmpty()) return null
        return runCatching { JSONObject(body).optInt("rejected", 0) }.getOrNull()
    }
}

/**
 * Outcome of a one-shot [EventTransport.submitFeedback] call. The Kotlin analog
 * of Swift's `Result<OwlFeedbackReceipt, OwlFeedbackError>` returned by
 * `submitFeedback`. Internal — [Owl.sendFeedback] unwraps this into a returned
 * receipt or a thrown [OwlFeedbackError].
 */
internal sealed interface FeedbackResult {
    data class Success(val receipt: OwlFeedbackReceipt) : FeedbackResult
    data class Failure(val error: OwlFeedbackError) : FeedbackResult
}

/** A minimal HTTP request — the framework-agnostic input to [HttpClient]. */
internal data class HttpRequest(
    val url: URL,
    val method: String,
    val headers: Map<String, String>,
    val body: ByteArray?,
) {
    // data class with a ByteArray needs hand-written equals/hashCode; only
    // identity matters at the call sites, but provide them so the type behaves.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HttpRequest) return false
        return url == other.url && method == other.method && headers == other.headers &&
            (body?.contentEquals(other.body ?: ByteArray(0)) ?: (other.body == null))
    }

    override fun hashCode(): Int {
        var result = url.hashCode()
        result = 31 * result + method.hashCode()
        result = 31 * result + headers.hashCode()
        result = 31 * result + (body?.contentHashCode() ?: 0)
        return result
    }
}

/** A minimal HTTP response. */
internal data class HttpResponse(
    val statusCode: Int,
    val body: String?,
)

/**
 * The transport's HTTP seam — the analog of injecting a `URLSession` into the
 * Swift `EventTransport`. The default is [DefaultHttpClient] ([HttpURLConnection]);
 * tests substitute a fake to assert batching/retry/gzip without a live server.
 */
internal fun interface HttpClient {
    /** Execute synchronously; callers wrap in [Dispatchers.IO]. Throws on transport failure. */
    fun execute(request: HttpRequest): HttpResponse
}

/** Production [HttpClient] over [HttpURLConnection] — framework-only, no OkHttp. */
internal object DefaultHttpClient : HttpClient {
    override fun execute(request: HttpRequest): HttpResponse {
        val connection = (request.url.openConnection() as HttpURLConnection).apply {
            requestMethod = request.method
            connectTimeout = 15_000
            readTimeout = 30_000
            for ((key, value) in request.headers) setRequestProperty(key, value)
            if (request.body != null) {
                doOutput = true
                outputStream.use { it.write(request.body) }
            }
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use(BufferedReader::readText)
            return HttpResponse(statusCode = code, body = body)
        } finally {
            connection.disconnect()
        }
    }
}

/** Append a path segment to a base URL, normalizing a single slash boundary. */
private fun URL.appendPath(path: String): URL {
    val base = toString().trimEnd('/')
    return URL("$base/${path.trimStart('/')}")
}
