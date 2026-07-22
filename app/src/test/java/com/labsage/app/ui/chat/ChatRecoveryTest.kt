package com.labsage.app.ui.chat

import com.labsage.app.data.local.entity.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatRecoveryTest {
    @Test
    fun recoversLatestPartialTurnByClientRequestId() {
        val user = message("user-1", "user", "question", "request-1")
        val partial = message(
            id = "assistant-1",
            role = "assistant",
            content = "half",
            requestId = "request-1",
            status = "partial",
            isPartial = true
        )

        val recovered = ChatRecovery.from(listOf(user, partial))

        assertEquals(user, recovered?.user)
        assertEquals(partial, recovered?.assistant)
    }

    @Test
    fun recoversEmptyFailedTurnSoItCanBeRetriedAfterProcessDeath() {
        val user = message("user-1", "user", "question", "request-1")
        val failed = message(
            id = "assistant-1",
            role = "assistant",
            content = "",
            requestId = "request-1",
            status = "failed"
        )

        assertEquals(failed, ChatRecovery.from(listOf(user, failed))?.assistant)
    }

    @Test
    fun recoversStaleStreamingContentAfterProcessDeath() {
        val user = message("user-1", "user", "question", "request-1")
        val streaming = message(
            "assistant-1", "assistant", "half", "request-1", "streaming", isPartial = true
        )

        assertEquals(streaming, ChatRecovery.from(listOf(user, streaming))?.assistant)
    }

    @Test
    fun ignoresEmptyStreamingCompletedOrUnpairedAssistantRows() {
        val user = message("user-1", "user", "question", "request-1")
        val emptyStreaming = message(
            "assistant-1", "assistant", "", "request-1", "streaming", isPartial = true
        )
        val completed = message(
            "assistant-2", "assistant", "done", "request-1", "completed"
        )
        val unpaired = message(
            "assistant-3", "assistant", "half", "different-request", "partial", isPartial = true
        )

        assertNull(ChatRecovery.from(listOf(user, emptyStreaming)))
        assertNull(ChatRecovery.from(listOf(user, completed)))
        assertNull(ChatRecovery.from(listOf(user, unpaired)))
    }

    @Test
    fun doesNotResurrectAnOldFailureAfterANewerCompletedReply() {
        val user = message("user-1", "user", "old question", "request-1")
        val failed = message("assistant-1", "assistant", "", "request-1", "failed")
        val newerUser = message("user-2", "user", "new question", "request-2")
        val completed = message("assistant-2", "assistant", "done", "request-2", "completed")

        assertNull(ChatRecovery.from(listOf(user, failed, newerUser, completed)))
    }

    private fun message(
        id: String,
        role: String,
        content: String,
        requestId: String,
        status: String = "completed",
        isPartial: Boolean = false
    ) = MessageEntity(
        id = id,
        conversationId = "conversation-1",
        role = role,
        content = content,
        status = status,
        isPartial = isPartial,
        clientRequestId = requestId
    )
}
