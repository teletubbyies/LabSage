package com.labsage.app.data.remote

import com.google.gson.Gson
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SseCompletionTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun cleanEofWithoutTerminalEventEmitsRetryableErrorAfterChunks() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("event: message\ndata: kept chunk\n\n")
        )

        val events = collectOneStream()

        assertEquals(SseEvent.Message("kept chunk"), events.first())
        assertTrue(events.last() is SseEvent.Error)
        assertTrue((events.last() as SseEvent.Error).retryable)
    }

    @Test
    fun cleanEofBeforeFirstChunkStillEmitsRetryableError() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("")
        )

        val events = collectOneStream()

        assertEquals(1, events.size)
        assertTrue(events.single() is SseEvent.Error)
        assertTrue((events.single() as SseEvent.Error).retryable)
    }

    @Test
    fun doneEventCarriesChunkReferences() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    """
                    event: done
                    data: {"conversationId":"c1","userMessageId":"u1","assistantMessageId":"a1","status":"completed","updatedAt":9,"syncVersion":4,"references":[{"ref":"1","chunk_id":"ck-1","source":"a.md","title":"方法","content_hash":"ab12","content":"片段正文"},{"ref":"","chunk_id":"ck-2","source":"b.md","title":"无编号","content_hash":"cd34"},{"ref":"2","chunk_id":"ck-3","source":"c.md","title":"结果","content_hash":"ef56","content":"x"}]}
                    """.trimIndent() + "\n\n"
                )
        )

        val events = collectOneStream()
        val done = events.filterIsInstance<SseEvent.Done>().single()

        // The blank-ref item is dropped; the rest map through with defaults.
        assertEquals(
            listOf(
                com.labsage.app.domain.model.MessageReference(
                    ref = "1", chunkId = "ck-1", source = "a.md",
                    title = "方法", contentHash = "ab12", preview = "片段正文"
                ),
                com.labsage.app.domain.model.MessageReference(
                    ref = "2", chunkId = "ck-3", source = "c.md",
                    title = "结果", contentHash = "ef56", preview = "x"
                )
            ),
            done.references
        )
    }

    @Test
    fun doneEventWithoutReferencesYieldsEmptyList() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    """
                    event: done
                    data: {"conversationId":"c1","userMessageId":"u1","assistantMessageId":"a1","status":"completed","updatedAt":9,"syncVersion":4}
                    """.trimIndent() + "\n\n"
                )
        )

        val events = collectOneStream()
        val done = events.filterIsInstance<SseEvent.Done>().single()
        assertTrue(done.references.isEmpty())
    }

    private suspend fun collectOneStream(): List<SseEvent> {
        val dataSource = OkHttpSseDataSource(
            baseUrl = server.url("/").toString().removeSuffix("/"),
            sseClient = OkHttpClient(),
            gson = Gson()
        )
        return withTimeout(5_000) {
            dataSource.streamChat(
                ChatStreamRequest(
                    id = "conversation-id",
                    question = "question",
                    clientMessageId = "message-id",
                    clientRequestId = "request-id",
                    clientCreatedAt = 1
                )
            ).toList()
        }
    }
}
