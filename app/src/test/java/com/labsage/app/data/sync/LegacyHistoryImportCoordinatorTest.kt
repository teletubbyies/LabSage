package com.labsage.app.data.sync

import com.labsage.app.data.remote.LegacyImportRequest
import com.labsage.app.data.remote.LegacyConversationDto
import com.labsage.app.data.remote.LegacyMessageDto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyHistoryImportCoordinatorTest {
    @Test
    fun `completion is recorded only after backend success and retry is idempotent`() = runTest {
        val completion = FakeCompletionStore()
        var remoteCalls = 0
        var shouldFail = true
        var currentBinding = binding("user-a", "server-a")
        val coordinator = LegacyHistoryImportCoordinator(
            remote = LegacyImportRemoteSource { _, _ ->
                remoteCalls++
                if (shouldFail) error("offline")
            },
            local = LegacyImportLocalSource { LegacyImportRequest(emptyList(), emptyList()) },
            completionStore = completion,
            isBindingCurrent = { it == currentBinding }
        )

        assertTrue(runCatching { coordinator.ensureImported(currentBinding) }.isFailure)
        assertFalse(completion.isCompleted("user-a", "server-a"))

        shouldFail = false
        coordinator.ensureImported(currentBinding)
        assertTrue(completion.isCompleted("user-a", "server-a"))
        coordinator.ensureImported(currentBinding)
        assertEquals(2, remoteCalls)

        currentBinding = binding("user-a", "server-b")
        coordinator.ensureImported(currentBinding)
        assertEquals(3, remoteCalls)
        assertTrue(completion.isCompleted("user-a", "server-b"))
    }

    @Test
    fun `large history is parent-first batched and marked after every batch`() = runTest {
        val completion = FakeCompletionStore()
        val received = mutableListOf<LegacyImportRequest>()
        val snapshot = LegacyImportRequest(
            conversations = (1..1_001).map {
                LegacyConversationDto("conversation-$it", "title", false, 1, 1)
            },
            messages = (1..2_001).map {
                LegacyMessageDto(
                    id = "message-$it",
                    conversationId = "conversation-1",
                    role = "user",
                    content = "content",
                    status = "completed",
                    clientCreatedAt = 1,
                    createdAt = 1,
                    updatedAt = 1
                )
            }
        )
        val binding = binding("user-a", "server-a")
        val coordinator = LegacyHistoryImportCoordinator(
            remote = LegacyImportRemoteSource { _, request -> received += request },
            local = LegacyImportLocalSource { snapshot },
            completionStore = completion,
            isBindingCurrent = { it == binding }
        )

        coordinator.ensureImported(binding)

        assertEquals(listOf(500, 500, 1), received.take(3).map { it.conversations.size })
        assertTrue(received.take(3).all { it.messages.isEmpty() })
        assertEquals(listOf(2_000, 1), received.drop(3).map { it.messages.size })
        assertTrue(received.drop(3).all { it.conversations.isEmpty() })
        assertTrue(completion.isCompleted("user-a", "server-a"))
    }

    @Test
    fun `account switch after snapshot prevents upload and completion`() = runTest {
        val completion = FakeCompletionStore()
        val original = binding("user-a", "server-a")
        var current = original
        var remoteCalls = 0
        val coordinator = LegacyHistoryImportCoordinator(
            remote = LegacyImportRemoteSource { _, _ -> remoteCalls++ },
            local = LegacyImportLocalSource {
                current = binding("user-b", "server-b")
                LegacyImportRequest(emptyList(), emptyList())
            },
            completionStore = completion,
            isBindingCurrent = { it == current }
        )

        assertTrue(runCatching { coordinator.ensureImported(original) }.isFailure)
        assertEquals(0, remoteCalls)
        assertFalse(completion.isCompleted("user-a", "server-a"))
    }

    private fun binding(user: String, server: String) =
        LegacyImportBinding(userId = user, serverKey = server)

    private class FakeCompletionStore : LegacyImportCompletionStore {
        private val completed = mutableSetOf<Pair<String, String>>()

        override suspend fun isCompleted(userId: String, serverKey: String): Boolean =
            userId to serverKey in completed

        override suspend fun markCompleted(userId: String, serverKey: String) {
            completed += userId to serverKey
        }
    }
}
