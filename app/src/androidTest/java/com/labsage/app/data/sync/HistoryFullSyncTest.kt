package com.labsage.app.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.labsage.app.data.local.AppDatabase
import com.labsage.app.data.local.entity.ConversationEntity
import com.labsage.app.data.local.entity.MessageEntity
import com.labsage.app.data.remote.ConversationDto
import com.labsage.app.data.remote.MessageDto
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryFullSyncTest {
    private lateinit var database: AppDatabase
    private lateinit var store: RoomHistorySyncStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        store = RoomHistorySyncStore(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun cacheIsImmediateFullSyncIsIdempotentAndAccountsStayIsolated() = runBlocking {
        val conversations = database.conversationDao()
        conversations.insert(conversation("cached-a", "user-a", syncVersion = 4))
        conversations.insert(conversation("cached-b", "user-b", syncVersion = 5))
        conversations.insert(conversation("legacy", owner = null, syncVersion = 0))

        val beforeNetwork = conversations.getAllConversations("user-a").first()
        assertEquals(listOf("cached-a"), beforeNetwork.map { it.id })

        store.claimLegacyConversations("user-a")
        val offlineAfterClaim = conversations.getAllConversations("user-a").first()
        assertEquals(setOf("cached-a", "legacy"), offlineAfterClaim.map { it.id }.toSet())

        val remote = listOf(remoteConversation("server-a", syncVersion = 10))
        store.syncConversations("user-a", remote)
        store.syncConversations("user-a", remote)

        val userA = conversations.getAllConversations("user-a").first()
        assertEquals(setOf("legacy", "server-a"), userA.map { it.id }.toSet())
        assertFalse(userA.any { it.id == "cached-a" })
        assertEquals(listOf("cached-b"), conversations.getAllConversations("user-b").first().map { it.id })
        assertTrue(userA.all { it.ownerUserId == "user-a" })
    }

    @Test
    fun messageBatchUpsertIsIdempotentAndRejectsCrossConversationPayload() = runBlocking {
        database.conversationDao().insert(conversation("conversation-a", "user-a", syncVersion = 1))
        database.messageDao().insert(
            MessageEntity(
                id = "local-message",
                conversationId = "conversation-a",
                role = "user",
                content = "offline",
                clientRequestId = "local-request",
                syncVersion = 0
            )
        )
        val remote = listOf(remoteMessage("server-message", "conversation-a", syncVersion = 8))

        store.syncMessages("user-a", "conversation-a", remote)
        store.syncMessages("user-a", "conversation-a", remote)

        val messages = database.messageDao()
            .getMessagesByConversation("user-a", "conversation-a").first()
        assertEquals(setOf("local-message", "server-message"), messages.map { it.id }.toSet())
        assertEquals(8L, messages.single { it.id == "server-message" }.syncVersion)

        val invalid = remote + remoteMessage("wrong", "other-conversation", syncVersion = 9)
        val failure = runCatching { store.syncMessages("user-a", "conversation-a", invalid) }
        assertTrue(failure.isFailure)
        assertEquals(
            setOf("local-message", "server-message"),
            database.messageDao().getMessagesByConversation("user-a", "conversation-a")
                .first().map { it.id }.toSet()
        )
    }

    @Test
    fun directDeepLinkIntoEmptyRoomSyncsParentBeforeMessages() = runBlocking {
        var conversationCalls = 0
        var messageCalls = 0
        val coordinator = HistorySyncCoordinator(
            remote = object : HistoryRemoteSource {
                override suspend fun getConversations(): List<ConversationDto> {
                    conversationCalls++
                    return listOf(remoteConversation("deep-link-conversation", syncVersion = 3))
                }

                override suspend fun getMessages(conversationId: String): List<MessageDto> {
                    messageCalls++
                    assertEquals("deep-link-conversation", conversationId)
                    return listOf(remoteMessage("deep-link-message", conversationId, syncVersion = 4))
                }
            },
            local = store
        )

        coordinator.syncMessages("user-a", "deep-link-conversation")

        assertEquals(1, conversationCalls)
        assertEquals(1, messageCalls)
        assertEquals(
            "deep-link-conversation",
            database.conversationDao()
                .getAllConversations("user-a").first().single().id
        )
        assertEquals(
            "deep-link-message",
            database.messageDao()
                .getMessagesByConversation("user-a", "deep-link-conversation")
                .first().single().id
        )
    }

    @Test
    fun anotherAccountCannotAttachMessagesThroughAnOldDeepLink() = runBlocking {
        val conversations = database.conversationDao()
        val messages = database.messageDao()
        assertTrue(conversations.insert(conversation("shared-id", "user-a", syncVersion = 1)) > 0)
        assertEquals(-1L, conversations.insert(conversation("shared-id", "user-b", syncVersion = 0)))

        val foreignWrite = runCatching {
            messages.insertOwned(
                "user-b",
                MessageEntity(
                    id = "foreign-message",
                    conversationId = "shared-id",
                    role = "user",
                    content = "must not leak",
                    clientRequestId = "foreign-request"
                )
            )
        }

        assertTrue(foreignWrite.isFailure)
        assertTrue(messages.getMessagesByConversation("user-a", "shared-id").first().isEmpty())
        assertTrue(messages.getMessagesByConversation("user-b", "shared-id").first().isEmpty())
    }

    @Test
    fun conversationSyncCannotReassignAnotherAccountsCachedPrimaryKey() = runBlocking {
        val conversations = database.conversationDao()
        val messages = database.messageDao()
        conversations.insert(conversation("shared-conversation", "user-a", syncVersion = 3))
        messages.insertOwned(
            "user-a",
            MessageEntity(
                id = "user-a-message",
                conversationId = "shared-conversation",
                role = "user",
                content = "private A content",
                clientRequestId = "user-a-request"
            )
        )

        val result = runCatching {
            store.syncConversations(
                "user-b",
                listOf(remoteConversation("shared-conversation", syncVersion = 10))
            )
        }

        assertTrue(result.isFailure)
        assertEquals(
            "user-a",
            conversations.getById("user-a", "shared-conversation")?.ownerUserId
        )
        assertEquals(
            "private A content",
            messages.getMessagesByConversation("user-a", "shared-conversation")
                .first().single().content
        )
        assertTrue(conversations.getAllConversations("user-b").first().isEmpty())
        assertTrue(
            messages.getMessagesByConversation("user-b", "shared-conversation").first().isEmpty()
        )
    }

    @Test
    fun messageSyncCannotMoveAnotherAccountsMessagePrimaryKey() = runBlocking {
        val conversations = database.conversationDao()
        val messages = database.messageDao()
        conversations.insert(conversation("conversation-a", "user-a", syncVersion = 1))
        conversations.insert(conversation("conversation-b", "user-b", syncVersion = 2))
        messages.insertOwned(
            "user-a",
            MessageEntity(
                id = "shared-message-id",
                conversationId = "conversation-a",
                role = "assistant",
                content = "private A answer",
                clientRequestId = "request-a"
            )
        )

        val result = runCatching {
            store.syncMessages(
                "user-b",
                "conversation-b",
                listOf(
                    remoteMessage("shared-message-id", "conversation-b", syncVersion = 12)
                )
            )
        }

        assertTrue(result.isFailure)
        assertEquals(
            "private A answer",
            messages.getMessagesByConversation("user-a", "conversation-a")
                .first().single().content
        )
        assertTrue(
            messages.getMessagesByConversation("user-b", "conversation-b").first().isEmpty()
        )
    }

    @Test
    fun syncSkipsActiveLocalAssistantUntilDoneAdoptsServerIdentity() = runBlocking {
        database.conversationDao().insert(conversation("conversation-a", "user-a", syncVersion = 1))
        val messages = database.messageDao()
        messages.insertOwned(
            "user-a",
            MessageEntity(
                id = "local-assistant",
                conversationId = "conversation-a",
                role = "assistant",
                content = "local chunk",
                status = "streaming",
                isPartial = true,
                clientRequestId = "shared-request"
            )
        )

        store.syncMessages(
            "user-a",
            "conversation-a",
            listOf(
                remoteMessage("server-assistant", "conversation-a", syncVersion = 7)
                    .copy(
                        status = "streaming",
                        content = "",
                        clientRequestId = "shared-request"
                    )
            )
        )
        var rows = messages.getMessagesByConversation("user-a", "conversation-a").first()
        assertEquals(listOf("local-assistant"), rows.map { it.id })
        assertEquals("local chunk", rows.single().content)

        messages.finalizeMessage("user-a", "local-assistant", "complete answer", "direct")
        messages.adoptServerIdentity(
            userId = "user-a",
            localId = "local-assistant",
            serverId = "server-assistant",
            status = "completed",
            syncVersion = 8,
            serverUpdatedAt = 500
        )
        rows = messages.getMessagesByConversation("user-a", "conversation-a").first()
        assertEquals(1, rows.size)
        assertEquals("server-assistant", rows.single().id)
        assertEquals("complete answer", rows.single().content)
        assertEquals("completed", rows.single().status)
    }

    @Test
    fun firstChunkReusesServerIdentityWhenSyncWonTheRace() = runBlocking {
        database.conversationDao().insert(conversation("conversation-a", "user-a", syncVersion = 1))
        val messages = database.messageDao()
        store.syncMessages(
            "user-a",
            "conversation-a",
            listOf(
                remoteMessage("server-assistant", "conversation-a", syncVersion = 7)
                    .copy(status = "streaming", content = "")
            )
        )

        val chosenId = messages.insertOrReuseAssistantOwned(
            "user-a",
            MessageEntity(
                id = "local-candidate",
                conversationId = "conversation-a",
                role = "assistant",
                content = "first chunk",
                status = "streaming",
                isPartial = true,
                originalQuestion = "question",
                clientRequestId = "request-server-assistant"
            )
        )

        assertEquals("server-assistant", chosenId)
        val rows = messages.getMessagesByConversation("user-a", "conversation-a").first()
        assertEquals(1, rows.size)
        assertEquals("first chunk", rows.single().content)
        assertFalse(rows.any { it.id == "local-candidate" })
    }

    @Test
    fun resumableLocalStatesConvergeWithEveryRemoteStateAndDoneRemainsSafe() = runBlocking {
        val messages = database.messageDao()
        for (localStatus in listOf("partial", "failed")) {
            for (remoteStatus in listOf("streaming", "partial", "completed")) {
                val suffix = "$localStatus-$remoteStatus"
                val conversationId = "conversation-$suffix"
                val localId = "local-$suffix"
                val serverId = "server-$suffix"
                val requestId = "request-$suffix"
                database.conversationDao().insert(
                    conversation(conversationId, "user-a", syncVersion = 1)
                )
                messages.insertOwned(
                    "user-a",
                    MessageEntity(
                        id = localId,
                        conversationId = conversationId,
                        role = "assistant",
                        content = "recoverable local chunks",
                        status = localStatus,
                        isPartial = localStatus == "partial",
                        clientRequestId = requestId
                    )
                )

                store.syncMessages(
                    "user-a",
                    conversationId,
                    listOf(
                        remoteMessage(serverId, conversationId, syncVersion = 7).copy(
                            status = remoteStatus,
                            content = if (remoteStatus == "completed") "server answer" else "server partial",
                            clientRequestId = requestId
                        )
                    )
                )

                // Model a stale Continue coroutine even when a completed server
                // refresh has already replaced its local placeholder.
                messages.finalizeMessage("user-a", localId, "continued answer", "direct")
                messages.adoptServerIdentity(
                    userId = "user-a",
                    localId = localId,
                    serverId = serverId,
                    status = "completed",
                    syncVersion = 8,
                    serverUpdatedAt = 500
                )

                val rows = messages.getMessagesByConversation("user-a", conversationId).first()
                assertEquals("$suffix must converge to one row", 1, rows.size)
                assertEquals(serverId, rows.single().id)
                assertEquals("completed", rows.single().status)
                if (remoteStatus == "completed") {
                    assertEquals("server answer", rows.single().content)
                } else {
                    assertEquals("continued answer", rows.single().content)
                }
            }
        }
    }

    @Test
    fun processRestoreMarkPartialBeforeSyncStillConvergesOnDone() = runBlocking {
        val conversationId = "restore-conversation"
        val messages = database.messageDao()
        database.conversationDao().insert(conversation(conversationId, "user-a", syncVersion = 1))
        messages.insertOwned(
            "user-a",
            MessageEntity(
                id = "restored-local",
                conversationId = conversationId,
                role = "assistant",
                content = "persisted chunks",
                status = "streaming",
                isPartial = true,
                clientRequestId = "restore-request"
            )
        )
        messages.markTerminal(
            "user-a",
            "restored-local",
            "persisted chunks",
            "partial",
            true,
            "process stopped"
        )

        store.syncMessages(
            "user-a",
            conversationId,
            listOf(
                remoteMessage("restored-server", conversationId, syncVersion = 9).copy(
                    status = "streaming",
                    content = "",
                    clientRequestId = "restore-request"
                )
            )
        )
        messages.finalizeMessage("user-a", "restored-local", "finished", "direct")
        messages.adoptServerIdentity(
            "user-a", "restored-local", "restored-server", "completed", 10, 600
        )

        val rows = messages.getMessagesByConversation("user-a", conversationId).first()
        assertEquals(1, rows.size)
        assertEquals("restored-server", rows.single().id)
        assertEquals("finished", rows.single().content)
    }

    @Test
    fun completedSyncCannotBeDowngradedByLateProcessRestore() = runBlocking {
        val conversationId = "completed-before-restore"
        val messages = database.messageDao()
        database.conversationDao().insert(conversation(conversationId, "user-a", syncVersion = 1))
        messages.insertOwned(
            "user-a",
            MessageEntity(
                id = "shared-server-id",
                conversationId = conversationId,
                role = "assistant",
                content = "old persisted chunks",
                status = "streaming",
                isPartial = true,
                clientRequestId = "shared-request",
                syncVersion = 4
            )
        )

        store.syncMessages(
            "user-a",
            conversationId,
            listOf(
                remoteMessage("shared-server-id", conversationId, syncVersion = 9).copy(
                    status = "completed",
                    content = "authoritative answer",
                    clientRequestId = "shared-request"
                )
            )
        )
        // This is the delayed write launched by restorePartialStateIfAny.
        messages.markTerminal(
            "user-a",
            "shared-server-id",
            "old persisted chunks",
            "partial",
            true,
            "process stopped"
        )

        val row = messages.getMessagesByConversation("user-a", conversationId).first().single()
        assertEquals("completed", row.status)
        assertEquals("authoritative answer", row.content)
        assertEquals(9L, row.syncVersion)
    }

    @Test
    fun completedSyncCannotBeDowngradedByLateFirstChunkOrTerminalCallback() = runBlocking {
        val conversationId = "completed-before-first-chunk"
        val messages = database.messageDao()
        database.conversationDao().insert(conversation(conversationId, "user-a", syncVersion = 1))
        store.syncMessages(
            "user-a",
            conversationId,
            listOf(
                remoteMessage("completed-server", conversationId, syncVersion = 11).copy(
                    status = "completed",
                    content = "authoritative answer",
                    clientRequestId = "late-request"
                )
            )
        )

        val chosenId = messages.insertOrReuseAssistantOwned(
            "user-a",
            MessageEntity(
                id = "late-local-candidate",
                conversationId = conversationId,
                role = "assistant",
                content = "late first chunk",
                status = "streaming",
                isPartial = true,
                clientRequestId = "late-request"
            )
        )
        assertEquals("completed-server", chosenId)
        messages.updateContent("user-a", chosenId, "late second chunk", true)
        messages.markTerminal(
            "user-a", chosenId, "late chunks", "partial", true, "late eof"
        )
        messages.finalizeMessage("user-a", chosenId, "late local answer", "direct")
        messages.adoptServerIdentity(
            "user-a", chosenId, "completed-server", "partial", 8, 700
        )

        val rows = messages.getMessagesByConversation("user-a", conversationId).first()
        assertEquals(1, rows.size)
        assertEquals("completed-server", rows.single().id)
        assertEquals("completed", rows.single().status)
        assertEquals("authoritative answer", rows.single().content)
        assertEquals(11L, rows.single().syncVersion)
    }

    @Test
    fun completedSyncCannotBeDowngradedByDoneWithoutAnyLocalChunk() = runBlocking {
        val messages = database.messageDao()
        for (lateStatus in listOf("partial", "failed")) {
            val conversationId = "no-chunk-$lateStatus"
            val serverId = "server-no-chunk-$lateStatus"
            database.conversationDao().insert(
                conversation(conversationId, "user-a", syncVersion = 1)
            )
            store.syncMessages(
                "user-a",
                conversationId,
                listOf(
                    remoteMessage(serverId, conversationId, syncVersion = 15).copy(
                        status = "completed",
                        content = "authoritative $lateStatus answer",
                        clientRequestId = "no-chunk-request-$lateStatus"
                    )
                )
            )

            messages.mergeTerminalOwned(
                "user-a",
                MessageEntity(
                    id = serverId,
                    conversationId = conversationId,
                    role = "assistant",
                    content = "",
                    status = lateStatus,
                    isPartial = lateStatus == "partial",
                    clientRequestId = "no-chunk-request-$lateStatus",
                    syncVersion = 12,
                    serverUpdatedAt = 800
                )
            )

            val row = messages.getMessagesByConversation("user-a", conversationId).first().single()
            assertEquals("completed", row.status)
            assertEquals("authoritative $lateStatus answer", row.content)
            assertEquals(15L, row.syncVersion)
        }
    }

    private fun conversation(id: String, owner: String?, syncVersion: Long) = ConversationEntity(
        id = id,
        title = id,
        ownerUserId = owner,
        syncVersion = syncVersion
    )

    private fun remoteConversation(id: String, syncVersion: Long) = ConversationDto(
        id = id,
        title = "Remote",
        isPinned = false,
        version = 2,
        syncVersion = syncVersion,
        createdAt = 100,
        updatedAt = 200
    )

    private fun remoteMessage(id: String, conversationId: String, syncVersion: Long) = MessageDto(
        id = id,
        conversationId = conversationId,
        role = "assistant",
        content = "answer",
        status = "completed",
        clientRequestId = "request-$id",
        clientCreatedAt = 100,
        syncVersion = syncVersion,
        createdAt = 110,
        updatedAt = 120
    )
}
