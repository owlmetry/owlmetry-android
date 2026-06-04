package com.owlmetry.android

import java.io.File

/**
 * A binary file to upload alongside an event (typically an error). The Android
 * analog of Swift's `OwlAttachment`.
 *
 * Swift models the payload as an `enum Source { case fileURL(URL); case data(Data) }`.
 * Kotlin's analog is the nested sealed [Source] hierarchy — [FileSource] (read
 * lazily off disk at upload time, so a large attachment isn't held in memory
 * until the upload coroutine drains it) and [DataSource] (in-memory bytes).
 *
 * Construct via the two factory entry points:
 *  - [file] — attach a file on disk; [name] defaults to the file's name.
 *  - [bytes] — attach raw in-memory bytes; [name] is required.
 *
 * [contentType] is optional; when null the [AttachmentUploader] infers a MIME
 * type from the file extension, falling back to `application/octet-stream`
 * (mirrors Swift's `UTType(filenameExtension:)` default).
 */
public class OwlAttachment private constructor(
    public val source: Source,
    public val name: String,
    public val contentType: String?,
) {
    /** Where the attachment's bytes come from. Mirrors Swift's `OwlAttachment.Source`. */
    public sealed interface Source {
        /** Bytes read off disk at upload time. Mirrors Swift's `.fileURL`. */
        public data class FileSource(val file: File) : Source

        /** In-memory bytes. Mirrors Swift's `.data`. */
        public class DataSource(public val bytes: ByteArray) : Source {
            // ByteArray has identity equals/hashCode; provide structural ones so
            // the source behaves like a value (used in tests + dedup checks).
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is DataSource) return false
                return bytes.contentEquals(other.bytes)
            }

            override fun hashCode(): Int = bytes.contentHashCode()
        }
    }

    public companion object {
        /**
         * Attach a file on disk. [name] defaults to the file's own name; pass an
         * explicit [name] to override the server-visible filename. The file is
         * read lazily by the [AttachmentUploader], not here. Mirrors Swift's
         * `OwlAttachment(fileURL:name:contentType:)`.
         */
        public fun file(
            file: File,
            name: String? = null,
            contentType: String? = null,
        ): OwlAttachment = OwlAttachment(
            source = Source.FileSource(file),
            name = name ?: file.name,
            contentType = contentType,
        )

        /**
         * Attach raw in-memory bytes. [name] is required (there's no filename to
         * derive). Mirrors Swift's `OwlAttachment(data:name:contentType:)`.
         */
        public fun bytes(
            bytes: ByteArray,
            name: String,
            contentType: String? = null,
        ): OwlAttachment = OwlAttachment(
            source = Source.DataSource(bytes),
            name = name,
            contentType = contentType,
        )
    }
}
