package com.labsage.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.labsage.app.data.local.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    /** All messages for a conversation, in chronological order */
    @Query(
        "SELECT messages.* FROM messages " +
            "INNER JOIN conversations ON conversations.id = messages.conversation_id " +
            "WHERE messages.conversation_id = :conversationId " +
            "AND conversations.owner_user_id = :userId AND messages.is_deleted = 0 " +
            "ORDER BY messages.timestamp ASC, messages.id ASC"
    )
    fun getMessagesByConversation(userId: String, conversationId: String): Flow<List<MessageEntity>>

    @Insert
    suspend fun insert(message: MessageEntity)

    @Query(
        "SELECT EXISTS(SELECT 1 FROM conversations " +
            "WHERE id = :conversationId AND owner_user_id = :userId AND is_deleted = 0)"
    )
    suspend fun isConversationOwned(userId: String, conversationId: String): Boolean

    @Transaction
    suspend fun insertOwned(userId: String, message: MessageEntity) {
        check(isConversationOwned(userId, message.conversationId)) {
            "Cannot write a message outside the authenticated account"
        }
        insert(message)
    }

    @Upsert
    suspend fun upsertAll(messages: List<MessageEntity>)

    @Query(
        "SELECT messages.id FROM messages INNER JOIN conversations " +
            "ON conversations.id = messages.conversation_id " +
            "WHERE messages.id IN (:ids) AND conversations.owner_user_id != :userId"
    )
    suspend fun findForeignOwnedIds(userId: String, ids: List<String>): List<String>

    @Query(
        "SELECT messages.* FROM messages INNER JOIN conversations " +
            "ON conversations.id = messages.conversation_id " +
            "WHERE conversations.owner_user_id = :userId AND messages.sync_version = 0 " +
            "AND messages.is_deleted = 0 ORDER BY messages.timestamp ASC, messages.id ASC"
    )
    suspend fun getLocalOnlyForImport(userId: String): List<MessageEntity>

    @Transaction
    suspend fun upsertOwned(userId: String, message: MessageEntity) {
        check(isConversationOwned(userId, message.conversationId)) {
            "Cannot write a message outside the authenticated account"
        }
        upsertAll(listOf(message))
    }

    @Query(
        "SELECT messages.* FROM messages INNER JOIN conversations " +
            "ON conversations.id = messages.conversation_id " +
            "WHERE messages.id = :messageId AND conversations.owner_user_id = :userId LIMIT 1"
    )
    suspend fun getOwnedMessage(userId: String, messageId: String): MessageEntity?

    /** Merge a Done-without-chunks row without regressing newer server history. */
    @Transaction
    suspend fun mergeTerminalOwned(userId: String, message: MessageEntity): String {
        check(isConversationOwned(userId, message.conversationId)) {
            "Cannot write a message outside the authenticated account"
        }
        val existing = getOwnedMessage(userId, message.id)
        if (existing != null) {
            if (existing.syncVersion > message.syncVersion ||
                (existing.status == "completed" && existing.syncVersion > 0 &&
                    message.status != "completed")
            ) {
                return existing.id
            }
            val merged = message.copy(
                content = message.content.ifEmpty { existing.content },
                route = message.route ?: existing.route,
                originalQuestion = message.originalQuestion ?: existing.originalQuestion,
                errorMessage = message.errorMessage ?: existing.errorMessage
            )
            upsertAll(listOf(merged))
            return merged.id
        }
        upsertAll(listOf(message))
        return message.id
    }

    @Query(
        "SELECT messages.id FROM messages " +
            "INNER JOIN conversations ON conversations.id = messages.conversation_id " +
            "WHERE conversations.owner_user_id = :userId " +
            "AND messages.conversation_id = :conversationId " +
            "AND messages.role = 'assistant' AND messages.client_request_id = :clientRequestId " +
            "ORDER BY messages.sync_version DESC LIMIT 1"
    )
    suspend fun findAssistantIdByRequest(
        userId: String,
        conversationId: String,
        clientRequestId: String
    ): String?

    @Query(
        "UPDATE messages SET content = :content, status = 'streaming', is_partial = 1, " +
            "original_question = :originalQuestion, error_message = NULL WHERE id = :messageId " +
            "AND NOT (status = 'completed' AND sync_version > 0)"
    )
    suspend fun prepareAssistantPlaceholder(
        messageId: String,
        content: String,
        originalQuestion: String?
    )

    @Transaction
    suspend fun insertOrReuseAssistantOwned(userId: String, message: MessageEntity): String {
        check(message.role == "assistant" && !message.clientRequestId.isNullOrBlank())
        check(isConversationOwned(userId, message.conversationId)) {
            "Cannot write a message outside the authenticated account"
        }
        val existingId = findAssistantIdByRequest(
            userId,
            message.conversationId,
            checkNotNull(message.clientRequestId)
        )
        if (existingId != null) {
            prepareAssistantPlaceholder(existingId, message.content, message.originalQuestion)
            return existingId
        }
        insert(message)
        return message.id
    }

    /**
     * Efficient partial update for streaming content — avoids fetching
     * the full entity first.
     */
    @Query(
        "UPDATE messages SET content = :content, is_partial = :isPartial " +
            "WHERE id = :messageId AND conversation_id IN " +
            "(SELECT id FROM conversations WHERE owner_user_id = :userId) " +
            "AND NOT (status = 'completed' AND sync_version > 0)"
    )
    suspend fun updateContent(
        userId: String,
        messageId: String,
        content: String,
        isPartial: Boolean
    )

    @Query(
        "UPDATE messages SET content = :content, status = :status, " +
            "is_partial = :isPartial, error_message = :errorMessage " +
            "WHERE id = :messageId AND conversation_id IN " +
            "(SELECT id FROM conversations WHERE owner_user_id = :userId) " +
            "AND NOT (status = 'completed' AND sync_version > 0)"
    )
    suspend fun markTerminal(
        userId: String,
        messageId: String,
        content: String,
        status: String,
        isPartial: Boolean,
        errorMessage: String?
    )

    @Query(
        "UPDATE messages SET status = 'streaming', is_partial = 1, error_message = NULL " +
            "WHERE id = :messageId AND conversation_id IN " +
            "(SELECT id FROM conversations WHERE owner_user_id = :userId) " +
            "AND NOT (status = 'completed' AND sync_version > 0)"
    )
    suspend fun markStreaming(userId: String, messageId: String)

    /** Mark a message as no longer partial (stream completed successfully) */
    @Query(
        "UPDATE messages SET is_partial = 0, status = 'completed', route = :route, content = :content " +
            "WHERE id = :messageId AND conversation_id IN " +
            "(SELECT id FROM conversations WHERE owner_user_id = :userId) " +
            "AND NOT (status = 'completed' AND sync_version > 0)"
    )
    suspend fun finalizeMessage(userId: String, messageId: String, content: String, route: String?)

    @Query(
        "UPDATE messages SET id = :serverId, status = :status, " +
            "is_partial = CASE WHEN :status = 'partial' THEN 1 ELSE 0 END, " +
            "sync_version = :syncVersion, server_updated_at = :serverUpdatedAt " +
            "WHERE id = :localId AND conversation_id IN " +
            "(SELECT id FROM conversations WHERE owner_user_id = :userId)"
    )
    suspend fun adoptServerIdentityRow(
        userId: String,
        localId: String,
        serverId: String,
        status: String,
        syncVersion: Long,
        serverUpdatedAt: Long
    )

    @Query(
        "SELECT EXISTS(SELECT 1 FROM messages INNER JOIN conversations " +
            "ON conversations.id = messages.conversation_id " +
            "WHERE messages.id = :messageId AND conversations.owner_user_id = :userId)"
    )
    suspend fun isMessageOwned(userId: String, messageId: String): Boolean

    @Query(
        "UPDATE messages SET status = :status, " +
            "is_partial = CASE WHEN :status = 'partial' THEN 1 ELSE 0 END, " +
            "sync_version = :syncVersion, server_updated_at = :serverUpdatedAt " +
            "WHERE id = :serverId AND conversation_id IN " +
            "(SELECT id FROM conversations WHERE owner_user_id = :userId) " +
            "AND sync_version <= :syncVersion " +
            "AND NOT (status = 'completed' AND sync_version > 0 AND :status != 'completed')"
    )
    suspend fun updateServerIdentityMetadata(
        userId: String,
        serverId: String,
        status: String,
        syncVersion: Long,
        serverUpdatedAt: Long
    )

    /**
     * Converges a local placeholder onto the server ID without a primary-key race.
     * A server row may already exist when history refresh wins immediately before Done.
     */
    @Transaction
    suspend fun adoptServerIdentity(
        userId: String,
        localId: String,
        serverId: String,
        status: String,
        syncVersion: Long,
        serverUpdatedAt: Long
    ) {
        val localExists = isMessageOwned(userId, localId)
        val existingServer = getOwnedMessage(userId, serverId)
        if (localId == serverId || !localExists) {
            updateServerIdentityMetadata(
                userId, serverId, status, syncVersion, serverUpdatedAt
            )
            return
        }
        if (existingServer != null &&
            (existingServer.syncVersion > syncVersion ||
                (existingServer.status == "completed" && existingServer.syncVersion > 0))
        ) {
            deleteById(userId, localId)
            updateServerIdentityMetadata(
                userId, serverId, status, syncVersion, serverUpdatedAt
            )
            return
        }
        if (existingServer != null) {
            deleteById(userId, serverId)
        }
        adoptServerIdentityRow(
            userId, localId, serverId, status, syncVersion, serverUpdatedAt
        )
    }

    /** Delete a specific partial message (used by "regenerate") */
    @Query(
        "DELETE FROM messages WHERE id = :messageId AND conversation_id IN " +
            "(SELECT id FROM conversations WHERE owner_user_id = :userId)"
    )
    suspend fun deleteById(userId: String, messageId: String)

    @Query("SELECT id FROM messages WHERE conversation_id = :conversationId AND sync_version > 0")
    suspend fun getSyncedIds(conversationId: String): List<String>

    @Query("DELETE FROM messages WHERE conversation_id = :conversationId AND sync_version > 0 AND id IN (:ids)")
    suspend fun deleteSyncedByIds(conversationId: String, ids: List<String>)

    @Query(
        "SELECT * FROM messages WHERE conversation_id = :conversationId " +
            "AND role = :role AND client_request_id = :clientRequestId " +
            "AND id != :serverId AND sync_version = 0 " +
            "AND status IN ('streaming', 'partial', 'failed') LIMIT 1"
    )
    suspend fun findLocalResumableIdentityConflict(
        conversationId: String,
        role: String,
        clientRequestId: String,
        serverId: String
    ): MessageEntity?
}
