package com.owlmetry.android

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * Gzip compression for request bodies. The Android analog of the Swift SDK's
 * `Data.gzipped()` extension.
 *
 * Swift hand-rolls the RFC-1952 gzip envelope (10-byte header + raw deflate from
 * `COMPRESSION_ZLIB` + CRC32/size trailer) because Apple's `Compression`
 * framework only emits raw zlib. The JDK ships [GZIPOutputStream], which writes
 * a fully-formed gzip stream (same header/deflate/trailer) directly — so the
 * Android side is a thin wrapper rather than a manual byte assembly, while
 * producing the same `Content-Encoding: gzip` payload the server decodes.
 *
 * Mirrors Swift's behavior on empty input: returns the (empty) input unchanged
 * rather than emitting a gzip envelope around nothing.
 */
internal object GzipCompressor {
    /**
     * Compress [data] to gzip. Returns the original bytes untouched when empty,
     * matching Swift's `guard !isEmpty else { return self }`.
     */
    fun gzip(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        val out = ByteArrayOutputStream(data.size / 2 + 32)
        GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }
}
