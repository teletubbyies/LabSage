package com.labsage.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReferencesCodecTest {

    private val sample = listOf(
        MessageReference(
            ref = "1",
            chunkId = "ck-1",
            source = "protocol.md",
            title = "采样协议",
            contentHash = "9f2c7ab1d0e8",
            preview = "每 500 ms 持久化一次"
        ),
        MessageReference(
            ref = "2",
            chunkId = "ck-2",
            source = "notes.md",
            title = "结论",
            contentHash = "11aa22bb33cc",
            preview = null
        )
    )

    @Test
    fun encodeDecodeRoundTrips() {
        val decoded = ReferencesCodec.decode(ReferencesCodec.encode(sample))
        assertEquals(sample, decoded)
    }

    @Test
    fun encodeEmptyListProducesNullSoColumnStaysClean() {
        assertNull(ReferencesCodec.encode(emptyList()))
    }

    @Test
    fun decodeToleratesNullBlankAndMalformedJson() {
        assertEquals(emptyList<MessageReference>(), ReferencesCodec.decode(null))
        assertEquals(emptyList<MessageReference>(), ReferencesCodec.decode(""))
        assertEquals(emptyList<MessageReference>(), ReferencesCodec.decode("   "))
        assertEquals(emptyList<MessageReference>(), ReferencesCodec.decode("{not json"))
        assertEquals(emptyList<MessageReference>(), ReferencesCodec.decode("null"))
    }

    @Test
    fun decodeDropsItemsWithoutUsableRefAndDefaultsMissingFields() {
        // First item has no ref at all; the others carry only a ref.
        val json = """
            [
              {"chunk_id":"ck-1","source":"a.md"},
              {"ref":"3"},
              {"ref":"4"}
            ]
        """.trimIndent()

        val decoded = ReferencesCodec.decode(json)

        // The blank-ref item is filtered; the others fall back to defaults
        // instead of injecting null into non-null fields.
        assertEquals(
            listOf(
                MessageReference(ref = "3", chunkId = "", source = "", title = "", contentHash = ""),
                MessageReference(ref = "4", chunkId = "", source = "", title = "", contentHash = "")
            ),
            decoded
        )
    }

    @Test
    fun decodeTruncatesOverlongPreview() {
        val json = """[{"ref":"1","content":"${"x".repeat(1000)}"}]"""
        val decoded = ReferencesCodec.decode(json)
        assertEquals(240, decoded.single().preview!!.length)
    }
}
