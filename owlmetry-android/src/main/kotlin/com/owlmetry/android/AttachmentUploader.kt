package com.owlmetry.android

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.net.URLConnection
import java.security.MessageDigest

/**
 * Uploads event attachments to the Owlmetry ingest API. The Android analog of
 * Swift's `AttachmentUploader` actor.
 *
 * Two-step protocol, mirroring Swift exactly:
 *  1. **Reserve** — POST `v1/ingest/attachment` with metadata
 *     (`client_event_id`, filename, content type, size, sha256, `is_dev`,
 *     optional `user_id`). The server validates per-user/per-project quotas and
 *     returns `{ attachment_id, upload_url }`.
 *  2. **Upload** — PUT the raw bytes to `upload_url` with
 *     `Content-Type: application/octet-stream`, retried once on 5xx/transport
 *     failure (4xx is terminal — the server already rejected it).
 *
 * Uploads are **serialized through a FIFO drain coroutine** (Swift uses a single
 * `Task` chained off the actor): [enqueue] appends to [pending] and, if no drain
 * is running, spawns one that processes items one at a time until the queue
 * empties. This bounds memory (one attachment in flight) and mirrors Swift's
 * `queue: Task<Void, Never>?` single-flight semantics.
 *
 * Failures are logged and swallowed — an attachment that can't be loaded,
 * exceeds the SDK hard cap, is empty, or is rejected by the server is skipped,
 * never crashing the host app. Real quota enforcement is server-side; the
 * [sdkHardCapBytes] (default 2 GB) is an absolute safety net.
 *
 * HTTP runs through the same injectable [HttpClient] seam as [EventTransport],
 * so tests assert the reserve+upload flow without a live server.
 */
internal class AttachmentUploader(
    endpoint: URL,
    private val apiKey: String,
    private val scope: CoroutineScope,
    private val httpClient: HttpClient = DefaultHttpClient,
    private val sdkHardCapBytes: Long = DEFAULT_SDK_HARD_CAP_BYTES,
    // The dispatcher blocking HTTP + disk reads run on — [Dispatchers.IO] in
    // production, overridable in tests so uploads share the test scheduler and
    // stay deterministic.
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val ingestAttachmentUrl: URL = endpoint.appendAttachmentPath("v1/ingest/attachment")

    // FIFO of attachments awaiting upload + the single drain coroutine. Both
    // guarded by [queueMutex] — the actor analog under the coroutines-only dep
    // rule. `drainJob == null` means no drain is running.
    private val queueMutex = Mutex()
    private val pending = ArrayDeque<PendingUpload>()
    private var drainJob: Job? = null

    private data class PendingUpload(
        val clientEventId: String,
        val userId: String?,
        val isDev: Boolean,
        val attachment: OwlAttachment,
    )

    private data class ReserveResponse(
        val attachmentId: String,
        val uploadUrl: String,
    )

    /**
     * Enqueue [attachments] for upload, all tagged with the same
     * [clientEventId] / [userId] / [isDev]. Returns immediately; the drain
     * coroutine does the work. Mirrors Swift's `enqueue(clientEventId:...)`.
     */
    suspend fun enqueue(
        clientEventId: String,
        userId: String?,
        isDev: Boolean,
        attachments: List<OwlAttachment>,
    ) {
        if (attachments.isEmpty()) return
        queueMutex.withLock {
            for (attachment in attachments) {
                pending.addLast(PendingUpload(clientEventId, userId, isDev, attachment))
            }
            // Single-flight: only spawn a drain when none is running. An active
            // drain picks up the items we just appended on its next loop turn.
            if (drainJob?.isActive != true) {
                drainJob = scope.launch { drain() }
            }
        }
    }

    /** Drain the queue one item at a time until empty, then clear the job. */
    private suspend fun drain() {
        while (true) {
            val next = queueMutex.withLock {
                if (pending.isEmpty()) {
                    drainJob = null
                    return
                }
                pending.removeFirst()
            }
            uploadOne(next)
        }
    }

    private suspend fun uploadOne(item: PendingUpload) {
        val loaded = withContext(ioDispatcher) {
            runCatching { loadBytes(item.attachment) }
        }.getOrElse { error ->
            Log.w(TAG, "Failed to load attachment \"${item.attachment.name}\": ${error.message}")
            return
        }
        val (bytes, contentType) = loaded

        if (bytes.isEmpty()) {
            Log.w(TAG, "Skipping empty attachment \"${item.attachment.name}\"")
            return
        }
        if (bytes.size.toLong() > sdkHardCapBytes) {
            Log.w(
                TAG,
                "Attachment \"${item.attachment.name}\" is ${bytes.size} bytes, " +
                    "exceeds SDK hard cap $sdkHardCapBytes. Skipping upload.",
            )
            return
        }

        val sha = sha256Hex(bytes)
        val reserve = reserve(
            clientEventId = item.clientEventId,
            userId = item.userId,
            filename = item.attachment.name,
            contentType = contentType,
            sizeBytes = bytes.size,
            sha256 = sha,
            isDev = item.isDev,
        ) ?: return

        val uploadUrl = runCatching { URL(reserve.uploadUrl) }.getOrNull()
        if (uploadUrl == null) {
            Log.w(TAG, "Attachment reservation returned invalid upload URL")
            return
        }

        put(uploadUrl, bytes, item.attachment.name)
    }

    private suspend fun reserve(
        clientEventId: String,
        userId: String?,
        filename: String,
        contentType: String,
        sizeBytes: Int,
        sha256: String,
        isDev: Boolean,
    ): ReserveResponse? {
        val body = JSONObject().apply {
            put("client_event_id", clientEventId)
            put("original_filename", filename)
            put("content_type", contentType)
            put("size_bytes", sizeBytes)
            put("sha256", sha256)
            put("is_dev", isDev)
            if (userId != null) put("user_id", userId)
        }

        val request = HttpRequest(
            url = ingestAttachmentUrl,
            method = "POST",
            headers = mapOf(
                "Content-Type" to "application/json",
                "Authorization" to "Bearer $apiKey",
            ),
            body = body.toString().toByteArray(Charsets.UTF_8),
        )

        val response = withContext(ioDispatcher) { runCatching { httpClient.execute(request) } }
            .getOrElse { error ->
                Log.w(TAG, "Attachment reserve network error: ${error.message}")
                return null
            }

        if (response.statusCode !in 200..299) {
            Log.w(TAG, "Attachment reserve for $filename rejected (${response.statusCode})")
            return null
        }

        return runCatching {
            val json = JSONObject(response.body ?: "")
            ReserveResponse(
                attachmentId = json.getString("attachment_id"),
                uploadUrl = json.getString("upload_url"),
            )
        }.getOrElse {
            Log.w(TAG, "Attachment reserve response decode failed: ${it.message}")
            null
        }
    }

    private suspend fun put(url: URL, body: ByteArray, attachmentName: String) {
        val request = HttpRequest(
            url = url,
            method = "PUT",
            headers = mapOf(
                "Content-Type" to "application/octet-stream",
                "Authorization" to "Bearer $apiKey",
            ),
            body = body,
        )

        // One retry on 5xx/transport failure; 4xx is terminal. Mirrors Swift's
        // `for attempt in 0..<2`.
        for (attempt in 0 until UPLOAD_ATTEMPTS) {
            val result = withContext(ioDispatcher) { runCatching { httpClient.execute(request) } }
            result.onSuccess { response ->
                val code = response.statusCode
                if (code in 200..299) return
                if (code in 400..499) {
                    Log.w(TAG, "Attachment upload for $attachmentName rejected ($code)")
                    return
                }
                Log.w(TAG, "Attachment upload for $attachmentName returned $code, attempt ${attempt + 1}")
            }.onFailure { error ->
                Log.w(TAG, "Attachment upload network error: ${error.message}")
            }
        }
    }

    /** Read the attachment's bytes + resolve its content type. */
    private fun loadBytes(attachment: OwlAttachment): Pair<ByteArray, String> =
        when (val source = attachment.source) {
            is OwlAttachment.Source.DataSource ->
                source.bytes to (attachment.contentType ?: defaultContentType(attachment.name))
            is OwlAttachment.Source.FileSource ->
                source.file.readBytes() to (attachment.contentType ?: defaultContentType(source.file.name))
        }

    /**
     * Infer a MIME type from the filename extension, falling back to
     * `application/octet-stream`. The Android analog of Swift's
     * `UTType(filenameExtension:)?.preferredMIMEType`. Uses the framework's
     * built-in `URLConnection.guessContentTypeFromName` (no extra dependency).
     */
    private fun defaultContentType(filename: String): String =
        URLConnection.guessContentTypeFromName(filename) ?: "application/octet-stream"

    /** Lowercase hex SHA-256 of [data]. Mirrors Swift's `sha256Hex` (CommonCrypto). */
    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xff
            sb.append(HEX[v ushr 4])
            sb.append(HEX[v and 0x0f])
        }
        return sb.toString()
    }

    companion object {
        private const val TAG = "Owlmetry.attachments"
        private const val UPLOAD_ATTEMPTS = 2
        private val HEX = "0123456789abcdef".toCharArray()

        // Absolute SDK safety net (2 GB). Real enforcement is server-side
        // against the project's per-user and project quotas. `Long` (not `Int`)
        // because 2 GB exceeds Int.MAX_VALUE.
        const val DEFAULT_SDK_HARD_CAP_BYTES: Long = 2L * 1024 * 1024 * 1024
    }
}

/** Append a path segment to a base URL, normalizing a single slash boundary. */
private fun URL.appendAttachmentPath(path: String): URL {
    val base = toString().trimEnd('/')
    return URL("$base/${path.trimStart('/')}")
}
