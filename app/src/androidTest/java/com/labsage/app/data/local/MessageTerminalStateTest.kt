package com.labsage.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.labsage.app.data.local.entity.ConversationEntity
import com.labsage.app.data.local.entity.MessageEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessageTerminalStateTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
            database.conversationDao().insert(
                ConversationEntity(
                    id = "conversation-uuid",
                    title = "Conversation",
                    ownerUserId = "test-user",
                    ownerServerKey = "server-a"
                )
            )
        }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun terminalTransitionsAndServerIdentityArePersistedInRoom() = runBlocking {
        val dao = database.messageDao()
        dao.insert(
            MessageEntity(
                id = "local-assistant-uuid",
                conversationId = "conversation-uuid",
                role = "assistant",
                content = "half",
                status = "streaming",
                isPartial = true,
                clientRequestId = "request-uuid"
            )
        )

        dao.markTerminal(
            userId = "test-user",
            messageId = "local-assistant-uuid",
            content = "half answer",
            status = "partial",
            isPartial = true,
            errorMessage = "connection lost"
        )
        var row = dao.getMessagesByConversation("test-user", "conversation-uuid").first().single()
        assertEquals("partial", row.status)
        assertTrue(row.isPartial)
        assertEquals("connection lost", row.errorMessage)

        dao.markStreaming("test-user", "local-assistant-uuid")
        row = dao.getMessagesByConversation("test-user", "conversation-uuid").first().single()
        assertEquals("streaming", row.status)
        assertTrue(row.isPartial)
        assertNull(row.errorMessage)

        dao.adoptServerIdentity(
            userId = "test-user",
            localId = "local-assistant-uuid",
            serverId = "server-assistant-uuid",
            status = "completed",
            syncVersion = 9,
            serverUpdatedAt = 1234
        )
        row = dao.getMessagesByConversation("test-user", "conversation-uuid").first().single()
        assertEquals("server-assistant-uuid", row.id)
        assertEquals("completed", row.status)
        assertEquals(9L, row.syncVersion)
        assertEquals(1234L, row.serverUpdatedAt)
        assertFalse(row.isPartial)

        dao.markTerminal(
            userId = "test-user",
            messageId = "server-assistant-uuid",
            content = "",
            status = "failed",
            isPartial = false,
            errorMessage = "server failed"
        )
        row = dao.getMessagesByConversation("test-user", "conversation-uuid").first().single()
        // A late local callback must not downgrade an authoritative server completion.
        assertEquals("completed", row.status)
        assertFalse(row.isPartial)
        assertNull(row.errorMessage)
    }
}
