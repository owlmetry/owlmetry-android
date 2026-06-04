package com.owlmetry.android

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

/**
 * [GzipCompressor] produces RFC-1952 gzip that the server (and any standard
 * gzip decoder) can inflate. Pins the round-trip and the empty-input passthrough
 * that mirrors Swift's `guard !isEmpty else { return self }`. Pure JVM — no
 * org.json, so no Robolectric needed.
 */
class GzipCompressorTest {

    @Test
    fun `compressed bytes inflate back to the original`() {
        val original = ("the quick brown fox jumps over the lazy dog ".repeat(50))
            .toByteArray(Charsets.UTF_8)
        val compressed = GzipCompressor.gzip(original)

        val inflated = GZIPInputStream(ByteArrayInputStream(compressed)).use { it.readBytes() }
        assertArrayEquals(original, inflated)
    }

    @Test
    fun `output begins with the gzip magic header`() {
        val compressed = GzipCompressor.gzip("hello".toByteArray())
        // RFC 1952 §2.3.1: ID1=0x1f, ID2=0x8b, CM=0x08 (deflate).
        assertEquals(0x1f.toByte(), compressed[0])
        assertEquals(0x8b.toByte(), compressed[1])
        assertEquals(0x08.toByte(), compressed[2])
    }

    @Test
    fun `empty input passes through unchanged`() {
        val empty = ByteArray(0)
        assertArrayEquals(empty, GzipCompressor.gzip(empty))
    }

    @Test
    fun `empty input returns the same array instance`() {
        // Mirrors Swift's `guard !isEmpty else { return self }` — no gzip
        // envelope is built around nothing; the exact input is handed back.
        val empty = ByteArray(0)
        assertSame(empty, GzipCompressor.gzip(empty))
    }

    @Test
    fun `single byte input round-trips`() {
        val original = byteArrayOf(0x42)
        val compressed = GzipCompressor.gzip(original)
        val inflated = GZIPInputStream(ByteArrayInputStream(compressed)).use { it.readBytes() }
        assertArrayEquals(original, inflated)
    }

    @Test
    fun `binary (non-text) payload round-trips intact`() {
        // gzip must be byte-exact for arbitrary bytes, not just UTF-8 text.
        val original = ByteArray(1024) { (it * 31 xor 0x5A).toByte() }
        val compressed = GzipCompressor.gzip(original)
        val inflated = GZIPInputStream(ByteArrayInputStream(compressed)).use { it.readBytes() }
        assertArrayEquals(original, inflated)
    }

    @Test
    fun `repetitive payload actually shrinks`() {
        val original = "AAAAAAAAAA".repeat(500).toByteArray()
        val compressed = GzipCompressor.gzip(original)
        assertTrue("expected compression to shrink a repetitive payload", compressed.size < original.size)
    }
}
