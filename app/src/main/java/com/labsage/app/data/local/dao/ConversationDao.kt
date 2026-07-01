package com.labsage.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.labsage.app.data.local.entity.ConversationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    /** All conversations, newest first */
    @Query("SELECT * FROM conversations ORDER BY updated_at DESC")
    fun getAllConversations(): Flow<List<ConversationEntity>>

    /**
     * Insert a NEW conversation. Uses IGNORE (not REPLACE) because REPLACE
     * triggers DELETE + INSERT in SQLite, which would cascade-delete all
     * messages via FK ON DELETE CASCADE.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(conversation: ConversationEntity): Long

    /** Update an existing conversation's title and timestamp */
    @Update
    suspend fun update(conversation: ConversationEntity)

    /** Delete a conversation (cascades to messages via FK) */
    @Query("DELETE FROM conversations WHERE id = :conversationId")
    suspend fun deleteById(conversationId: String)

    /** Get a single conversation by ID */
    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getById(id: String): ConversationEntity?

    /** Update only title and timestamp without full entity replacement */
    @Query("UPDATE conversations SET title = :title, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateMeta(id: String, title: String, updatedAt: Long)
}
