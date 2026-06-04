package com.owlmetry.android

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [AttachmentUploader] behavior, mirroring the Swift `AttachmentUploaderTests`:
 *  - the two-step reserve (`POST v1/ingest/attachment`) → upload
 *    (`PUT upload_url`) flow, with the reserve body carrying the SHA-256, size,
 *    content type, `is_dev`, and optional `user_id`;
 *  - SHA-256 computed over the exact bytes;
 *  - empty attachments + over-cap attachments skipped without any network call;
 *  - the PUT retried once on 5xx, not retried on 4xx;
 *  - a file-source attachment read off disk with an inferred content type;
 *  - serialized FIFO drain across multiple enqueued attachments.
 *
 * Robolectric for real org.json. A recording [HttpClient] (the Android analog of
 * the Swift `URLProtocol` stub) captures reserve + upload requests. The uploader
 * runs on an [UnconfinedTestDispatcher] tied to the test scheduler so the drain
 * coroutine executes eagerly and is fully settled by `advanceUntilIdle()`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AttachmentUploaderTest {

    /** Recording HTTP client with a scripted reserve/upload reply per path. */
    private class FakeHttpClient : HttpClient {
        val requests = CopyOnWriteArrayList<HttpRequest>()
        // Scripted PUT status codes consumed in order; defaults to 200.
        val putStatuses = ArrayDeque<Int>()
        var reserveStatus: Int = 200
        var reserveUploadUrl: String = "https://uploads.example.com/blob/abc"

        override fun execute(request: HttpRequest): HttpResponse {
            requests.add(request)
            return when {
                request.url.path.endsWith("/v1/ingest/attachment") -> {
                    if (reserveStatus in 200..299) {
                        HttpResponse(
                            reserveStatus,
                            JSONObject()
                                .put("attachment_id", "att_123")
                                .put("upload_url", reserveUploadUrl)
                                .toString(),
                        )
                    } else {
                        HttpResponse(reserveStatus, """{"error":"rejected"}""")
                    }
                }
                request.method == "PUT" -> HttpResponse(putStatuses.removeFirstOrNull() ?: 200, "")
                else -> HttpResponse(200, "{}")
            }
        }

        fun reserves() = requests.filter { it.url.path.endsWith("/v1/ingest/attachment") }
        fun puts() = requests.filter { it.method == "PUT" }
    }

    private lateinit var http: FakeHttpClient
    private lateinit var tmpDir: File

    @Before
    fun setUp() {
        http = FakeHttpClient()
        tmpDir = File.createTempFile("owl-att", "").let { it.delete(); it.mkdirs(); it }
    }

    @After
    fun tearDown() {
        tmpDir.deleteRecursively()
    }

    /**
     * Build an uploader whose drain coroutine + HTTP both run on an unconfined
     * test dispatcher, so the work runs eagerly under the test scheduler.
     */
    private fun TestScope.makeUploader(
        scope: CoroutineScope,
        sdkHardCapBytes: Long = AttachmentUploader.DEFAULT_SDK_HARD_CAP_BYTES,
    ) = AttachmentUploader(
        endpoint = URL("https://ingest.example.com"),
        apiKey = "owl_client_abc",
        scope = scope,
        httpClient = http,
        sdkHardCapBytes = sdkHardCapBytes,
        ioDispatcher = UnconfinedTestDispatcher(testScheduler),
    )

    private fun expectedSha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    @Test
    fun `data attachment reserves then uploads with correct metadata`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        val uploader = makeUploader(backgroundScope)
        val bytes = "hello attachment".toByteArray()

        uploader.enqueue(
            clientEventId = "evt-1",
            userId = "user-9",
            isDev = true,
            attachments = listOf(OwlAttachment.bytes(bytes, name = "log.txt", contentType = "text/plain")),
        )
        advanceUntilIdle()

        // One reserve, one upload.
        assertEquals(1, http.reserves().size)
        assertEquals(1, http.puts().size)

        // Reserve body carries the metadata.
        val reserve = JSONObject(http.reserves().first().body!!.toString(Charsets.UTF_8))
        assertEquals("evt-1", reserve.getString("client_event_id"))
        assertEquals("user-9", reserve.getString("user_id"))
        assertEquals("log.txt", reserve.getString("original_filename"))
        assertEquals("text/plain", reserve.getString("content_type"))
        assertEquals(bytes.size, reserve.getInt("size_bytes"))
        assertEquals(expectedSha(bytes), reserve.getString("sha256"))
        assertTrue(reserve.getBoolean("is_dev"))

        // Reserve POST is JSON + bearer; upload PUT is octet-stream of the exact bytes.
        val reserveReq = http.reserves().first()
        assertEquals("POST", reserveReq.method)
        assertEquals("Bearer owl_client_abc", reserveReq.headers["Authorization"])
        val put = http.puts().first()
        assertEquals("PUT", put.method)
        assertEquals("application/octet-stream", put.headers["Content-Type"])
        assertEquals("https://uploads.example.com/blob/abc", put.url.toString())
        assertTrue(bytes.contentEquals(put.body!!))
    }

    @Test
    fun `reserve omits user_id when null`() = runTest(UnconfinedTestDispatcher()) {
        val uploader = makeUploader(backgroundScope)

        uploader.enqueue(
            clientEventId = "evt-2",
            userId = null,
            isDev = false,
            attachments = listOf(OwlAttachment.bytes("x".toByteArray(), name = "a.bin")),
        )
        advanceUntilIdle()

        val reserve = JSONObject(http.reserves().first().body!!.toString(Charsets.UTF_8))
        assertFalse("user_id must be omitted when null", reserve.has("user_id"))
        assertFalse(reserve.getBoolean("is_dev"))
    }

    @Test
    fun `empty attachment is skipped without any network call`() = runTest(UnconfinedTestDispatcher()) {
        val uploader = makeUploader(backgroundScope)

        uploader.enqueue(
            clientEventId = "evt-3",
            userId = null,
            isDev = false,
            attachments = listOf(OwlAttachment.bytes(ByteArray(0), name = "empty.bin")),
        )
        advanceUntilIdle()

        assertTrue("empty attachment must not reserve", http.reserves().isEmpty())
        assertTrue("empty attachment must not upload", http.puts().isEmpty())
    }

    @Test
    fun `over-cap attachment is skipped without any network call`() = runTest(UnconfinedTestDispatcher()) {
        // tiny cap so a 5-byte attachment trips it
        val uploader = makeUploader(backgroundScope, sdkHardCapBytes = 4)

        uploader.enqueue(
            clientEventId = "evt-4",
            userId = null,
            isDev = false,
            attachments = listOf(OwlAttachment.bytes("12345".toByteArray(), name = "big.bin")),
        )
        advanceUntilIdle()

        assertTrue("over-cap attachment must not reserve", http.reserves().isEmpty())
    }

    @Test
    fun `upload retries once on 5xx`() = runTest(UnconfinedTestDispatcher()) {
        http.putStatuses.addAll(listOf(500, 200)) // first PUT 500, retry 200
        val uploader = makeUploader(backgroundScope)

        uploader.enqueue(
            clientEventId = "evt-5",
            userId = null,
            isDev = false,
            attachments = listOf(OwlAttachment.bytes("data".toByteArray(), name = "r.bin")),
        )
        advanceUntilIdle()

        assertEquals("PUT should be retried once after a 5xx", 2, http.puts().size)
    }

    @Test
    fun `upload does not retry on 4xx`() = runTest(UnconfinedTestDispatcher()) {
        http.putStatuses.addAll(listOf(400, 200))
        val uploader = makeUploader(backgroundScope)

        uploader.enqueue(
            clientEventId = "evt-6",
            userId = null,
            isDev = false,
            attachments = listOf(OwlAttachment.bytes("data".toByteArray(), name = "r.bin")),
        )
        advanceUntilIdle()

        assertEquals("PUT must not be retried after a 4xx", 1, http.puts().size)
    }

    @Test
    fun `rejected reserve skips the upload`() = runTest(UnconfinedTestDispatcher()) {
        http.reserveStatus = 413 // quota exhausted
        val uploader = makeUploader(backgroundScope)

        uploader.enqueue(
            clientEventId = "evt-7",
            userId = null,
            isDev = false,
            attachments = listOf(OwlAttachment.bytes("data".toByteArray(), name = "r.bin")),
        )
        advanceUntilIdle()

        assertEquals(1, http.reserves().size)
        assertTrue("upload must not run when reserve is rejected", http.puts().isEmpty())
    }

    @Test
    fun `file-source attachment is read off disk with inferred content type`() = runTest(UnconfinedTestDispatcher()) {
        val file = File(tmpDir, "screenshot.png")
        val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)
        file.writeBytes(pngBytes)

        val uploader = makeUploader(backgroundScope)

        uploader.enqueue(
            clientEventId = "evt-8",
            userId = null,
            isDev = false,
            attachments = listOf(OwlAttachment.file(file)),
        )
        advanceUntilIdle()

        val reserve = JSONObject(http.reserves().first().body!!.toString(Charsets.UTF_8))
        // Name defaults to the file's name.
        assertEquals("screenshot.png", reserve.getString("original_filename"))
        // Content type inferred from the .png extension.
        assertEquals("image/png", reserve.getString("content_type"))
        assertEquals(expectedSha(pngBytes), reserve.getString("sha256"))
        // The PUT body is the file's exact bytes.
        assertTrue(pngBytes.contentEquals(http.puts().first().body!!))
    }

    @Test
    fun `unknown extension falls back to application octet-stream`() = runTest(UnconfinedTestDispatcher()) {
        val uploader = makeUploader(backgroundScope)

        // No contentType supplied and an extension org.json/URLConnection can't
        // map → application/octet-stream. Mirrors Swift's UTType default.
        uploader.enqueue(
            clientEventId = "evt-ct1",
            userId = null,
            isDev = false,
            attachments = listOf(OwlAttachment.bytes("blob".toByteArray(), name = "data.zzzz")),
        )
        advanceUntilIdle()

        val reserve = JSONObject(http.reserves().first().body!!.toString(Charsets.UTF_8))
        assertEquals("application/octet-stream", reserve.getString("content_type"))
    }

    @Test
    fun `explicit contentType overrides the inferred type`() = runTest(UnconfinedTestDispatcher()) {
        val uploader = makeUploader(backgroundScope)

        // The filename's extension would infer text/plain, but the caller's
        // explicit contentType wins. Mirrors Swift's `contentType ?? default`.
        uploader.enqueue(
            clientEventId = "evt-ct2",
            userId = null,
            isDev = false,
            attachments = listOf(
                OwlAttachment.bytes("payload".toByteArray(), name = "report.txt", contentType = "application/x-owl"),
            ),
        )
        advanceUntilIdle()

        val reserve = JSONObject(http.reserves().first().body!!.toString(Charsets.UTF_8))
        assertEquals("application/x-owl", reserve.getString("content_type"))
    }

    @Test
    fun `invalid upload url skips the PUT`() = runTest(UnconfinedTestDispatcher()) {
        // Reserve succeeds but returns a malformed upload_url → no PUT fires, no
        // crash. Mirrors Swift's `guard let uploadUrl = URL(string:)` bail.
        http.reserveUploadUrl = "not a url"
        val uploader = makeUploader(backgroundScope)

        uploader.enqueue(
            clientEventId = "evt-badurl",
            userId = null,
            isDev = false,
            attachments = listOf(OwlAttachment.bytes("data".toByteArray(), name = "r.bin")),
        )
        advanceUntilIdle()

        assertEquals("reserve should still fire", 1, http.reserves().size)
        assertTrue("a malformed upload_url must not produce a PUT", http.puts().isEmpty())
    }

    @Test
    fun `reserve network error skips the upload`() = runTest(UnconfinedTestDispatcher()) {
        // The reserve POST throws (transport failure) → swallowed, no PUT.
        // Mirrors Swift's `catch { logger.warning(...) ; return nil }`.
        val throwingHttp = object : HttpClient {
            val reserves = CopyOnWriteArrayList<HttpRequest>()
            val puts = CopyOnWriteArrayList<HttpRequest>()
            override fun execute(request: HttpRequest): HttpResponse {
                if (request.url.path.endsWith("/v1/ingest/attachment")) {
                    reserves.add(request)
                    throw java.io.IOException("connection reset")
                }
                if (request.method == "PUT") puts.add(request)
                return HttpResponse(200, "")
            }
        }
        val uploader = AttachmentUploader(
            endpoint = URL("https://ingest.example.com"),
            apiKey = "owl_client_abc",
            scope = backgroundScope,
            httpClient = throwingHttp,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        uploader.enqueue(
            clientEventId = "evt-neterr",
            userId = null,
            isDev = false,
            attachments = listOf(OwlAttachment.bytes("data".toByteArray(), name = "r.bin")),
        )
        advanceUntilIdle()

        assertEquals("reserve was attempted", 1, throwingHttp.reserves.size)
        assertTrue("a reserve transport error must not produce a PUT", throwingHttp.puts.isEmpty())
    }

    @Test
    fun `reserve carries the correct size_bytes for the exact payload`() = runTest(UnconfinedTestDispatcher()) {
        val uploader = makeUploader(backgroundScope)
        val bytes = ByteArray(4096) { (it % 251).toByte() }

        uploader.enqueue(
            clientEventId = "evt-size",
            userId = null,
            isDev = false,
            attachments = listOf(OwlAttachment.bytes(bytes, name = "blob.bin")),
        )
        advanceUntilIdle()

        val reserve = JSONObject(http.reserves().first().body!!.toString(Charsets.UTF_8))
        assertEquals(4096, reserve.getInt("size_bytes"))
        assertEquals(expectedSha(bytes), reserve.getString("sha256"))
        assertTrue("PUT body must be the exact bytes", bytes.contentEquals(http.puts().first().body!!))
    }

    @Test
    fun `multiple attachments drain in FIFO order`() = runTest(UnconfinedTestDispatcher()) {
        val uploader = makeUploader(backgroundScope)

        uploader.enqueue(
            clientEventId = "evt-9",
            userId = null,
            isDev = false,
            attachments = listOf(
                OwlAttachment.bytes("one".toByteArray(), name = "1.txt"),
                OwlAttachment.bytes("two".toByteArray(), name = "2.txt"),
                OwlAttachment.bytes("three".toByteArray(), name = "3.txt"),
            ),
        )
        advanceUntilIdle()

        val names = http.reserves().map {
            JSONObject(it.body!!.toString(Charsets.UTF_8)).getString("original_filename")
        }
        assertEquals(listOf("1.txt", "2.txt", "3.txt"), names)
        assertEquals(3, http.puts().size)
    }
}
