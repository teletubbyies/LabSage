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
    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId ORDER BY timestamp ASC")
    fun getMessagesByConversation(conversationId: String): Flow<List<MessageEntity>>

    /** Insert a message and return its auto-generated ID */
    @Insert
    suspend fun insert(message: MessageEntity): Long

    /** Update an existing message (used to update streaming content in-place) */
    @Update
    suspend fun update(message: MessageEntity)

    /**
     * Efficient partial update for streaming content — avoids fetching
     * the full entity first.
     */
    @Query("UPDATE messages SET content = :content, is_partial = :isPartial WHERE id = :messageId")
    suspend fun updateContent(messageId: Long, content: String, isPartial: Boolean)

    /** Mark a message as no longer partial (stream completed successfully) */
    @Query("UPDATE messages SET is_partial = 0, route = :route, content = :content WHERE id = :messageId")
    suspend fun finalizeMessage(messageId: Long, content: String, route: String?)

    /** Delete a specific partial message (used by "regenerate") */
    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteById(messageId: Long)
}
