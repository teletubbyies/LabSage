package com.labsage.app.data.remote

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.ProtocolException

class StreamingRequestBodyTest {

    @Test
    fun `streams exact content with declared metadata`() {
        val markdown = "# Paper\n\nNotes".encodeToByteArray()
        val mediaType = "text/markdown; charset=utf-8".toMediaType()
        val body = StreamingRequestBody(markdown.size.toLong(), mediaType) {
            Buffer().write(markdown)
        }
        val sink = Buffer()

        body.writeTo(sink)

        assertEquals(markdown.size.toLong(), body.contentLength())
        assertEquals(mediaType, body.contentType())
        assertEquals(markdown.decodeToString(), sink.readUtf8())
    }

    @Test
    fun `opens a fresh source when request body is replayed`() {
        val markdown = "# Replayable".encodeToByteArray()
        var opens = 0
        val body = StreamingRequestBody(
            markdown.size.toLong(),
            "text/markdown".toMediaType()
        ) {
            opens++
            Buffer().write(markdown)
        }

        val first = Buffer()
        val second = Buffer()
        body.writeTo(first)
        body.writeTo(second)

        assertEquals(2, opens)
        assertEquals(first.readUtf8(), second.readUtf8())
    }

    @Test
    fun `rejects a source shorter than declared length`() {
        val body = StreamingRequestBody(5, "text/markdown".toMediaType()) {
            Buffer().writeUtf8("four")
        }

        assertThrows(ProtocolException::class.java) {
            body.writeTo(Buffer())
        }
    }

    @Test
    fun `rejects a source longer than declared length without writing overflow`() {
        val body = StreamingRequestBody(4, "text/markdown".toMediaType()) {
            Buffer().writeUtf8("five!")
        }
        val sink = Buffer()

        assertThrows(ProtocolException::class.java) {
            body.writeTo(sink)
        }
        assertEquals("five", sink.readUtf8())
    }

    @Test
    fun `multipart contract keeps file field filename and content`() {
        val body = StreamingRequestBody(7, "text/markdown".toMediaType()) {
            Buffer().writeUtf8("# Notes")
        }
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "notes.md", body)
            .build()
        val sink = Buffer()

        multipart.writeTo(sink)
        val wire = sink.readUtf8()

        check(wire.contains("""name="file"; filename="notes.md""""))
        check(wire.contains("Content-Type: text/markdown"))
        check(wire.contains("# Notes"))
    }

    @Test
    fun `negative provider size is treated as unknown`() {
        assertEquals(null, normalizedUploadSize(-1))
        assertEquals(null, normalizedUploadSize(null))
        assertEquals(0L, normalizedUploadSize(0))
        assertEquals(42L, normalizedUploadSize(42))
    }

    @Test
    fun `bounded copy accepts content at limit`() {
        val input = ByteArray(16) { it.toByte() }
        val output = ByteArrayOutputStream()

        val copied = copyWithByteLimit(
            ByteArrayInputStream(input),
            output,
            maxBytes = input.size.toLong(),
            bufferSize = 4
        )

        assertEquals(input.size.toLong(), copied)
        assertEquals(input.toList(), output.toByteArray().toList())
    }

    @Test
    fun `bounded copy rejects content beyond limit without writing overflow`() {
        val output = ByteArrayOutputStream()

        assertThrows(UploadTooLargeException::class.java) {
            copyWithByteLimit(
                ByteArrayInputStream(ByteArray(17)),
                output,
                maxBytes = 16,
                bufferSize = 4
            )
        }
        assertEquals(16, output.size())
    }

    @Test
    fun `streaming request body rejects unknown length`() {
        assertThrows(IllegalArgumentException::class.java) {
            StreamingRequestBody(-1, "text/markdown".toMediaType()) { Buffer() }
        }
    }
}
