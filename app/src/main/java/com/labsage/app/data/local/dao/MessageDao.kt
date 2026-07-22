package com.labsage.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.labsage.app.data.local.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    /** All messages for a conversation, in chronological order */
    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId AND is_deleted = 0 ORDER BY timestamp ASC, id ASC")
    fun getMessagesByConversation(conversationId: String): Flow<List<MessageEntity>>

    @Insert
    suspend fun insert(message: MessageEntity)

    /** Update an existing message (used to update streaming content in-place) */
    @Update
    suspend fun update(message: MessageEntity)

    /**
     * Efficient partial update for streaming content — avoids fetching
     * the full entity first.
     */
    @Query("UPDATE messages SET content = :content, is_partial = :isPartial WHERE id = :messageId")
    suspend fun updateContent(messageId: String, content: String, isPartial: Boolean)

    @Query("UPDATE messages SET content = :content, status = :status, is_partial = :isPartial, error_message = :errorMessage WHERE id = :messageId")
    suspend fun markTerminal(
        messageId: String,
        content: String,
        status: String,
        isPartial: Boolean,
        errorMessage: String?
    )

    @Query("UPDATE messages SET status = 'streaming', is_partial = 1, error_message = NULL WHERE id = :messageId")
    suspend fun markStreaming(messageId: String)

    /** Mark a message as no longer partial (stream completed successfully) */
    @Query("UPDATE messages SET is_partial = 0, status = 'completed', route = :route, content = :content WHERE id = :messageId")
    suspend fun finalizeMessage(messageId: String, content: String, route: String?)

    @Query(
        "UPDATE messages SET id = :serverId, status = :status, " +
            "is_partial = CASE WHEN :status = 'partial' THEN 1 ELSE 0 END, " +
            "sync_version = :syncVersion, server_updated_at = :serverUpdatedAt WHERE id = :localId"
    )
    suspend fun adoptServerIdentity(
        localId: String,
        serverId: String,
        status: String,
        syncVersion: Long,
        serverUpdatedAt: Long
    )

    /** Delete a specific partial message (used by "regenerate") */
    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteById(messageId: String)
}
