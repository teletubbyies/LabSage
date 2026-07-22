package com.labsage.app.data.remote

import com.google.gson.Gson
import com.labsage.app.ui.chat.ChatTerminalFailure
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatIdContractTest {
    private val gson = Gson()

    @Test
    fun streamRequestCarriesStableIdsAndTimestamp() {
        val json = gson.toJson(
            ChatStreamRequest(
                id = "conversation-uuid",
                question = "question",
                clientMessageId = "message-uuid",
                clientRequestId = "request-uuid",
                clientCreatedAt = 1234L
            )
        )

        assertTrue(json.contains("\"Id\":\"conversation-uuid\""))
        assertTrue(json.contains("\"ClientMessageId\":\"message-uuid\""))
        assertTrue(json.contains("\"ClientRequestId\":\"request-uuid\""))
        assertTrue(json.contains("\"ClientCreatedAt\":1234"))
    }

    @Test
    fun structuredDonePreservesServerStringIdentity() {
        val dataSource = OkHttpSseDataSource(
            baseUrl = "http://localhost",
            sseClient = OkHttpClient(),
            gson = gson
        )
        val event = dataSource.buildEvent(
            "done",
            """{"conversationId":"conversation-uuid","userMessageId":"user-uuid","assistantMessageId":"assistant-uuid","status":"completed","updatedAt":99,"syncVersion":7}"""
        )

        assertEquals(
            SseEvent.Done(
                conversationId = "conversation-uuid",
                userMessageId = "user-uuid",
                assistantMessageId = "assistant-uuid",
                status = "completed",
                updatedAt = 99L,
                syncVersion = 7L
            ),
            event
        )
        assertTrue(dataSource.buildEvent("done", "not-json") is SseEvent.Error)
    }

    @Test
    fun structuredDonePreservesPartialAndFailedTerminalStates() {
        val dataSource = OkHttpSseDataSource(
            baseUrl = "http://localhost",
            sseClient = OkHttpClient(),
            gson = gson
        )

        listOf("partial", "failed").forEach { status ->
            val event = dataSource.buildEvent(
                "done",
                """{"conversationId":"conversation-uuid","userMessageId":"user-uuid","assistantMessageId":"assistant-$status","status":"$status","updatedAt":101,"syncVersion":8}"""
            ) as SseEvent.Done

            assertEquals(status, event.status)
            assertEquals("assistant-$status", event.assistantMessageId)
            assertEquals(8L, event.syncVersion)
        }
    }

    @Test
    fun malformedDoneAfterChunksKeepsContentAsPartialTerminalState() {
        val dataSource = OkHttpSseDataSource(
            baseUrl = "http://localhost",
            sseClient = OkHttpClient(),
            gson = gson
        )

        assertTrue(dataSource.buildEvent("done", "not-json") is SseEvent.Error)
        val failure = ChatTerminalFailure.from("already generated")
        assertEquals("already generated", failure.content)
        assertEquals("partial", failure.status)
        assertTrue(failure.isPartial)
    }

    @Test
    fun structurallyInvalidDoneIsRejectedEvenWhenJsonParses() {
        val dataSource = OkHttpSseDataSource(
            baseUrl = "http://localhost",
            sseClient = OkHttpClient(),
            gson = gson
        )
        val invalidPayloads = listOf(
            """{"conversationId":"c","userMessageId":"u","updatedAt":1,"syncVersion":1}""",
            """{"conversationId":"","userMessageId":"u","assistantMessageId":"a","status":"completed","updatedAt":1,"syncVersion":1}""",
            """{"conversationId":"c","userMessageId":"u","assistantMessageId":"a","status":"unknown","updatedAt":1,"syncVersion":1}""",
            """{"conversationId":"c","userMessageId":"u","assistantMessageId":"a","status":"completed","updatedAt":0,"syncVersion":0}"""
        )

        invalidPayloads.forEach { payload ->
            assertTrue(dataSource.buildEvent("done", payload) is SseEvent.Error)
        }
    }

    @Test
    fun mismatchedDoneWithoutChunksBecomesFailedTerminalState() {
        val failure = ChatTerminalFailure.from("")

        assertEquals("failed", failure.status)
        assertTrue(!failure.isPartial)
    }
}
