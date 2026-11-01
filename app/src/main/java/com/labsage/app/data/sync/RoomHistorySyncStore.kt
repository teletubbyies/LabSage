package com.labsage.app.data.sync

import androidx.room.withTransaction
import com.labsage.app.data.local.AppDatabase
import com.labsage.app.data.local.entity.ConversationEntity
import com.labsage.app.data.local.entity.HistoryCursorEntity
import com.labsage.app.data.local.entity.MessageEntity
import com.labsage.app.data.remote.ConversationDto
import com.labsage.app.data.remote.LegacyConversationDto
import com.labsage.app.data.remote.LegacyImportRequest
import com.labsage.app.data.remote.LegacyMessageDto
import com.labsage.app.data.remote.MessageDto
import com.labsage.app.domain.model.ReferencesCodec
import com.labsage.app.domain.model.toReference

class RoomHistorySyncStore(
    private val database: AppDatabase
) {
    suspend fun legacyImportSnapshot(userId: String): LegacyImportRequest =
        legacyImportSnapshot(userId, "server-a")

    suspend fun legacyImportSnapshot(userId: String, serverKey: String): LegacyImportRequest =
        database.withTransaction {
            database.conversationDao().claimLegacyRows(userId, serverKey)
            val conversations = database.conversationDao().getLocalOnlyForImport(userId, serverKey)
            val messages = database.messageDao().getLocalOnlyForImport(userId, serverKey)
            LegacyImportRequest(
                conversations = conversations.map {
                    LegacyConversationDto(
                        id = it.id,
                        title = it.title,
                        isPinned = it.isPinned,
                        createdAt = it.createdAt,
                        updatedAt = it.updatedAt
                    )
                },
                messages = messages.map {
                    val normalized = it.normalizedForLegacyImport()
                    LegacyMessageDto(
                        id = normalized.id,
                        conversationId = normalized.conversationId,
                        role = normalized.role,
                        content = normalized.content,
                        status = normalized.status,
                        route = normalized.route,
                        originalQuestion = normalized.originalQuestion,
                        errorMessage = normalized.errorMessage,
                        clientRequestId = normalized.clientRequestId,
                        clientCreatedAt = normalized.clientCreatedAt,
                        createdAt = normalized.timestamp,
                        updatedAt = normalized.serverUpdatedAt ?: normalized.timestamp
                    )
                }
            )
        }

    suspend fun claimLegacyConversations(userId: String, serverKey: String) {
        database.withTransaction {
            database.conversationDao().claimLegacyRows(userId, serverKey)
        }
    }

    suspend fun claimLegacyConversations(userId: String) =
        claimLegacyConversations(userId, "server-a")

    suspend fun syncConversations(
        userId: String,
        remote: List<ConversationDto>,
        nextCursor: Long
    ) = syncConversations(userId, "server-a", remote, nextCursor)

    suspend fun syncConversations(
        userId: String,
        serverKey: String,
        remote: List<ConversationDto>,
        nextCursor: Long,
        replaceSyncedCache: Boolean = false
    ) {
        require(nextCursor >= 0) { "History cursor must be non-negative" }
        database.withTransaction {
            val dao = database.conversationDao()
            if (replaceSyncedCache) {
                // A different backend may reuse the same user/conversation IDs.
                // Remove its server-owned cache only after the new full page arrived.
                dao.getOtherServerIds(userId, serverKey)
                    .chunked(SQLITE_SAFE_BATCH_SIZE)
                    .forEach {
                        database.historyCursorDao().deleteMessageCursors(userId, it)
                    }
                dao.deleteOtherServerRows(userId, serverKey)
            }
            upsertConversations(userId, serverKey, remote)
            val serverIds = remote.mapTo(hashSetOf()) { it.id }
            val removedIds = dao.getSyncedIds(userId, serverKey)
                .filterNot(serverIds::contains)
            removedIds
                .chunked(SQLITE_SAFE_BATCH_SIZE)
                .forEach { dao.deleteSyncedByIds(userId, it, serverKey) }
            removedIds
                .chunked(SQLITE_SAFE_BATCH_SIZE)
                .forEach { database.historyCursorDao().deleteMessageCursors(userId, it) }
            // The cursor advances in the same transaction as the page merge,
            // so a crash can never leave the cursor ahead of the cached data.
            database.historyCursorDao().upsert(
                HistoryCursorEntity.conversationsCursor(userId, serverKey, nextCursor)
            )
        }
    }

    suspend fun mergeConversations(
        userId: String,
        serverKey: String,
        remote: List<ConversationDto>,
        nextCursor: Long
    ) {
        require(nextCursor >= 0) { "History cursor must be non-negative" }
        database.withTransaction {
            upsertConversations(userId, serverKey, remote)
            database.historyCursorDao().upsert(
                HistoryCursorEntity.conversationsCursor(userId, serverKey, nextCursor)
            )
        }
    }

    /**
     * Merges one authoritative conversation row (e.g. a PATCH response) without
     * advancing the cursor: this row was seen out of band, not as a synced page,
     * so the next incremental fetch may idly re-deliver it.
     */
    suspend fun applyConversationMutation(
        userId: String,
        serverKey: String,
        conversation: ConversationDto
    ) {
        database.withTransaction {
            upsertConversations(userId, serverKey, listOf(conversation))
        }
    }

    suspend fun syncMessages(
        userId: String,
        serverKey: String,
        conversationId: String,
        remote: List<MessageDto>,
        nextCursor: Long
    ) {
        require(remote.all { it.conversationId == conversationId }) {
            "Message sync response crossed conversation boundaries"
        }
        require(nextCursor >= 0) { "History cursor must be non-negative" }
        database.withTransaction {
            upsertMessages(userId, serverKey, conversationId, remote)
            val dao = database.messageDao()
            val serverIds = remote.mapTo(hashSetOf()) { it.id }
            dao.getSyncedIds(conversationId)
                .filterNot(serverIds::contains)
                .chunked(SQLITE_SAFE_BATCH_SIZE)
                .forEach { dao.deleteSyncedByIds(conversationId, it) }
            database.historyCursorDao().upsert(
                HistoryCursorEntity.messagesCursor(userId, serverKey, conversationId, nextCursor)
            )
        }
    }

    suspend fun syncMessages(
        userId: String,
        conversationId: String,
        remote: List<MessageDto>,
        nextCursor: Long
    ) = syncMessages(userId, "server-a", conversationId, remote, nextCursor)

    suspend fun mergeMessages(
        userId: String,
        serverKey: String,
        conversationId: String,
        remote: List<MessageDto>,
        nextCursor: Long
    ) {
        require(remote.all { it.conversationId == conversationId }) {
            "Message sync response crossed conversation boundaries"
        }
        require(nextCursor >= 0) { "History cursor must be non-negative" }
        database.withTransaction {
            upsertMessages(userId, serverKey, conversationId, remote)
            database.historyCursorDao().upsert(
                HistoryCursorEntity.messagesCursor(userId, serverKey, conversationId, nextCursor)
            )
        }
    }

    suspend fun hasOwnedConversation(userId: String, serverKey: String, conversationId: String): Boolean =
        database.conversationDao().getById(userId, conversationId, serverKey) != null

    suspend fun hasSyncedConversations(userId: String, serverKey: String): Boolean =
        database.conversationDao().hasSyncedRows(userId, serverKey)

    suspend fun hasSyncedMessages(userId: String, conversationId: String): Boolean =
        database.messageDao().hasSyncedRows(userId, conversationId)

    private suspend fun upsertConversations(
        userId: String,
        serverKey: String,
        remote: List<ConversationDto>
    ) {
        val dao = database.conversationDao()
        dao.claimLegacyRows(userId, serverKey)
        val foreignIds = remote.map { it.id }
            .chunked(SQLITE_SAFE_BATCH_SIZE)
            .flatMap { dao.findForeignOwnedIds(userId, it, serverKey) }
        check(foreignIds.isEmpty()) {
            "Conversation IDs are already cached for a different account"
        }
        dao.upsertAll(remote.map { it.toEntity(userId, serverKey) })
    }

    private suspend fun upsertMessages(
        userId: String,
        serverKey: String,
        conversationId: String,
        remote: List<MessageDto>
    ) {
        val conversation = database.conversationDao().getById(userId, conversationId, serverKey)
            ?: error("Cannot sync messages before their owned conversation")
        check(!conversation.isDeleted)
        val dao = database.messageDao()
        val foreignIds = remote.map { it.id }
            .chunked(SQLITE_SAFE_BATCH_SIZE)
            .flatMap { dao.findForeignOwnedIds(userId, it) }
        check(foreignIds.isEmpty()) {
            "Message IDs are already cached for a different account"
        }
        val safeRemote = remote.mapNotNull { message ->
            val requestId = message.clientRequestId
            val localConflict = requestId?.let {
                dao.findLocalResumableIdentityConflict(
                    conversationId = conversationId,
                    role = message.role,
                    clientRequestId = it,
                    serverId = message.id
                )
            }
            when {
                localConflict == null -> message
                message.status == "completed" -> {
                    // A terminal server row replaces the local resumable placeholder.
                    dao.deleteById(userId, localConflict.id)
                    message
                }
                else -> null // Preserve local chunks until Continue/Done adopts the ID.
            }
        }
        // A backend that predates citations omits the `references` field
        // entirely (null ≠ empty list). @Upsert replaces whole rows, so a
        // null would erase the citations persisted locally by the done event.
        // Keep the local snapshot for exactly those rows.
        val idsWithoutServerReferences = safeRemote.filter { it.references == null }.map { it.id }
        val localReferencesById = idsWithoutServerReferences
            .chunked(SQLITE_SAFE_BATCH_SIZE)
            .flatMap { dao.getReferencesJsonByIds(userId, it) }
            .associate { it.id to it.referencesJson }
        dao.upsertAll(safeRemote.map { message ->
            val entity = message.toEntity()
            if (message.references == null) {
                entity.copy(referencesJson = localReferencesById[message.id])
            } else {
                entity
            }
        })
    }

    private fun ConversationDto.toEntity(userId: String, serverKey: String) = ConversationEntity(
        id = id,
        title = title,
        createdAt = createdAt,
        updatedAt = updatedAt,
        isPinned = isPinned,
        version = version,
        syncVersion = syncVersion,
        isDeleted = isDeleted,
        ownerUserId = userId,
        ownerServerKey = serverKey
    )

    private fun MessageDto.toEntity() = MessageEntity(
        id = id,
        conversationId = conversationId,
        role = role,
        content = content,
        timestamp = createdAt,
        route = route,
        isPartial = status == "partial" || status == "streaming",
        originalQuestion = originalQuestion,
        errorMessage = errorMessage,
        status = status,
        clientRequestId = clientRequestId,
        clientCreatedAt = clientCreatedAt,
        syncVersion = syncVersion,
        serverUpdatedAt = updatedAt,
        isDeleted = false,
        referencesJson = ReferencesCodec.encode(
            references?.map { it.toReference() }.orEmpty()
        )
    )

    private fun MessageEntity.normalizedForLegacyImport(): MessageEntity = when {
        role == "user" -> copy(status = "completed", isPartial = false, errorMessage = null)
        status != "pending" && status != "streaming" -> this
        content.isNotBlank() -> copy(status = "partial", isPartial = true)
        else -> copy(
            status = "failed",
            isPartial = false,
            errorMessage = errorMessage ?: "Imported interrupted generation"
        )
    }

    private companion object {
        const val SQLITE_SAFE_BATCH_SIZE = 500
    }
}
