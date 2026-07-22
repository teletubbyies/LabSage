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
