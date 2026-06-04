package com.owlmetry.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList

/**
 * End-to-end attachment behavior through the public [Owl] API, mirroring the
 * Swift attachment integration: `Owl.error(..., attachments: [...])` emits the
 * event AND drives a reserve (`POST v1/ingest/attachment`) tagged with that
 * event's `client_event_id`, followed by the upload PUT. Also confirms an error
 * logged without attachments triggers no attachment traffic.
 *
 * Robolectric for real org.json + filesystem; a recording [HttpClient] captures
 * ingest, reserve, and upload requests. The SDK runs its own scope, so a drain
 * is forced via `setUser` (→ claim → flushAll) and polled.
 */
@RunWith(RobolectricTestRunner::class)
class OwlAttachmentTest {

    private class RecordingHttpClient : HttpClient {
        val requests = CopyOnWriteArrayList<HttpRequest>()

        override fun execute(request: HttpRequest): HttpResponse {
            requests.add(request)
            val path = request.url.path
            return when {
                path.endsWith("/v1/identity/claim") -> HttpResponse(200, """{"claimed":true,"events_reassigned_count":0}""")
                path.endsWith("/v1/ingest/attachment") -> HttpResponse(
                    200,
                    JSONObject().put("attachment_id", "att_1").put("upload_url", "https://uploads.example.com/blob/1").toString(),
                )
                path.endsWith("/v1/ingest") -> HttpResponse(200, """{"accepted":1,"rejected":0}""")
                request.method == "PUT" -> HttpResponse(200, "")
                else -> HttpResponse(200, "{}")
            }
        }

        fun reserves() = requests.filter { it.url.path.endsWith("/v1/ingest/attachment") }
        fun puts() = requests.filter { it.method == "PUT" }
        fun ingestEvents(): List<JSONObject> {
            val out = ArrayList<JSONObject>()
            for (req in requests) {
                if (!req.url.path.endsWith("/v1/ingest")) continue
                val text = req.body?.toString(Charsets.UTF_8) ?: continue
                val arr = JSONObject(text).getJSONArray("events")
                for (i in 0 until arr.length()) out.add(arr.getJSONObject(i))
            }
            return out
        }
    }

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private lateinit var http: RecordingHttpClient

    @Before
    fun setUp() {
        Owl.resetForTesting()
        http = RecordingHttpClient()
        Owl.httpClientOverrideForTesting = http
        IdentityStore(context).clearUserId()
    }

    @After
    fun tearDown() {
        Owl.resetForTesting()
        Owl.httpClientOverrideForTesting = null
    }

    private fun configure() {
        Owl.configure(
            context = context,
            endpoint = "https://ingest.example.com",
            apiKey = "owl_client_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            compressionEnabled = false,
            networkTrackingEnabled = false,
            consoleLogging = false,
            attributionEnabled = false,
        )
    }

    private fun pollUntil(timeoutMs: Long, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(20)
        }
    }

    @Test
    fun `error with attachment reserves and uploads tagged with the event id`() {
        configure()
        Owl.error(
            message = "render crash",
            attachments = listOf(OwlAttachment.bytes("screenshot-bytes".toByteArray(), name = "shot.png", contentType = "image/png")),
        )
        // Drain the event stream so the event POST lands; the attachment upload
        // runs on its own coroutine. Wait for BOTH the attachment path (reserve +
        // PUT) AND the ingest event to land before asserting — they flush on
        // separate coroutines, so polling only the attachment path races the
        // ingest flush and makes the client_event_id assertion flaky under load.
        Owl.setUser("real-${System.nanoTime()}")

        pollUntil(5_000) {
            http.reserves().isNotEmpty() &&
                http.puts().isNotEmpty() &&
                http.ingestEvents().any { it.getString("message") == "render crash" }
        }

        // The reserve happened.
        assertTrue("no attachment reserve fired", http.reserves().isNotEmpty())
        val reserve = JSONObject(http.reserves().first().body!!.toString(Charsets.UTF_8))
        assertEquals("shot.png", reserve.getString("original_filename"))
        assertEquals("image/png", reserve.getString("content_type"))

        // The reserve's client_event_id matches the emitted event's id.
        val errorEvent = http.ingestEvents().firstOrNull { it.getString("message") == "render crash" }
        assertNotNull("error event not found in ingest stream", errorEvent)
        assertEquals(
            errorEvent!!.getString("client_event_id"),
            reserve.getString("client_event_id"),
        )

        // And the upload PUT carried the exact bytes.
        assertTrue(http.puts().isNotEmpty())
        assertTrue("screenshot-bytes".toByteArray().contentEquals(http.puts().first().body!!))
    }

    @Test
    fun `error without attachments triggers no attachment traffic`() {
        configure()
        Owl.error(message = "plain error")
        Owl.setUser("real-${System.nanoTime()}")

        pollUntil(2_000) { http.ingestEvents().any { it.getString("message") == "plain error" } }

        assertTrue("an error without attachments must not reserve", http.reserves().isEmpty())
        assertTrue("an error without attachments must not upload", http.puts().isEmpty())
    }
}
