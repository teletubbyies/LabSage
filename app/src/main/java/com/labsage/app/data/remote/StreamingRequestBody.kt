package com.labsage.app.data.remote

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import okio.Source
import java.io.InputStream
import java.io.OutputStream
import java.net.ProtocolException

/**
 * A replayable request body that opens a fresh [Source] for every write.
 *
 * Opening lazily keeps ContentResolver I/O on OkHttp's worker thread and makes
 * the body safe to replay after an authentication challenge.
 */
internal class StreamingRequestBody(
    private val length: Long,
    private val mediaType: MediaType,
    private val openSource: () -> Source
) : RequestBody() {

    init {
        require(length >= 0) { "Streaming request body requires a known length" }
    }

    override fun contentType(): MediaType = mediaType

    override fun contentLength(): Long = length

    override fun writeTo(sink: BufferedSink) {
        openSource().use { source ->
            val buffer = Buffer()
            var written = 0L
            while (written < length) {
                val readLimit = minOf(DEFAULT_BUFFER_SIZE.toLong(), length - written)
                val read = source.read(buffer, readLimit)
                if (read < 0) break
                sink.write(buffer, read)
                written += read
            }
            if (written != length) {
                throw ProtocolException(
                    "Selected file ended after $written bytes; expected $length bytes"
                )
            }
            // Probe without forwarding the byte so a stale provider size cannot
            // silently produce a request body longer than Content-Length.
            if (source.read(buffer, 1) >= 0) {
                throw ProtocolException(
                    "Selected file is larger than its declared size ($length bytes)"
                )
            }
        }
    }
}

internal fun normalizedUploadSize(size: Long?): Long? = size?.takeIf { it >= 0 }

/**
 * Copies at most [maxBytes] without ever allocating an array proportional to
 * the input size. Throws before writing bytes beyond the configured limit.
 */
internal fun copyWithByteLimit(
    input: InputStream,
    output: OutputStream,
    maxBytes: Long,
    bufferSize: Int = DEFAULT_BUFFER_SIZE
): Long {
    require(maxBytes >= 0) { "maxBytes must be non-negative" }
    require(bufferSize > 0) { "bufferSize must be positive" }

    val buffer = ByteArray(bufferSize)
    var copied = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        if (read == 0) continue
        if (copied + read > maxBytes) {
            throw UploadTooLargeException(maxBytes)
        }
        output.write(buffer, 0, read)
        copied += read
    }
    return copied
}

internal class UploadTooLargeException(maxBytes: Long) :
    IllegalArgumentException("Markdown file exceeds the ${maxBytes / (1024 * 1024)} MiB limit")
