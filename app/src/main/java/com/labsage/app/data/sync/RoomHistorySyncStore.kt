package com.labsage.app.data.sync

import androidx.room.withTransaction
import com.labsage.app.data.local.AppDatabase
import com.labsage.app.data.local.entity.ConversationEntity
import com.labsage.app.data.local.entity.MessageEntity
import com.labsage.app.data.remote.ConversationDto
import com.labsage.app.data.remote.LegacyConversationDto
import com.labsage.app.data.remote.LegacyImportRequest
import com.labsage.app.data.remote.LegacyMessageDto
import com.labsage.app.data.remote.MessageDto

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

    suspend fun syncConversations(userId: String, remote: List<ConversationDto>) =
        syncConversations(userId, "server-a", remote)

    suspend fun syncConversations(
        userId: String,
        serverKey: String,
        remote: List<ConversationDto>,
        replaceSyncedCache: Boolean = false
    ) {
        database.withTransaction {
            if (replaceSyncedCache) {
                // A different backend may reuse the same user/conversation IDs.
                // Remove its server-owned cache only after the new full page arrived.
                database.conversationDao().deleteOtherServerRows(userId, serverKey)
            }
            upsertConversations(userId, serverKey, remote)
            val dao = database.conversationDao()
            val serverIds = remote.mapTo(hashSetOf()) { it.id }
            dao.getSyncedIds(userId, serverKey)
                .filterNot(serverIds::contains)
                .chunked(SQLITE_SAFE_BATCH_SIZE)
                .forEach { dao.deleteSyncedByIds(userId, it, serverKey) }
        }
    }

    suspend fun mergeConversations(
        userId: String,
        serverKey: String,
        remote: List<ConversationDto>
    ) {
        database.withTransaction {
            upsertConversations(userId, serverKey, remote)
        }
    }

    suspend fun syncMessages(
        userId: String,
        serverKey: String,
        conversationId: String,
        remote: List<MessageDto>
    ) {
        require(remote.all { it.conversationId == conversationId }) {
            "Message sync response crossed conversation boundaries"
        }
        database.withTransaction {
            upsertMessages(userId, serverKey, conversationId, remote)
            val dao = database.messageDao()
            val serverIds = remote.mapTo(hashSetOf()) { it.id }
            dao.getSyncedIds(conversationId)
                .filterNot(serverIds::contains)
                .chunked(SQLITE_SAFE_BATCH_SIZE)
                .forEach { dao.deleteSyncedByIds(conversationId, it) }
        }
    }

    suspend fun syncMessages(
        userId: String,
        conversationId: String,
        remote: List<MessageDto>
    ) = syncMessages(userId, "server-a", conversationId, remote)

    suspend fun mergeMessages(
        userId: String,
        serverKey: String,
        conversationId: String,
        remote: List<MessageDto>
    ) {
        require(remote.all { it.conversationId == conversationId }) {
            "Message sync response crossed conversation boundaries"
        }
        database.withTransaction {
            upsertMessages(userId, serverKey, conversationId, remote)
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
        dao.upsertAll(safeRemote.map { it.toEntity() })
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
        isDeleted = false
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
