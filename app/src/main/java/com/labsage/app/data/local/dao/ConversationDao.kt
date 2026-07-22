package com.labsage.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.labsage.app.data.local.entity.ConversationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    /** All conversations, pinned first then newest first. */
    @Query("SELECT * FROM conversations WHERE owner_user_id = :userId AND is_deleted = 0 ORDER BY is_pinned DESC, updated_at DESC, id DESC")
    fun getAllConversations(userId: String): Flow<List<ConversationEntity>>

    /** Title-substring search, preserving pinned-first ordering. */
    @Query("SELECT * FROM conversations WHERE owner_user_id = :userId AND is_deleted = 0 AND title LIKE '%' || :query || '%' ORDER BY is_pinned DESC, updated_at DESC, id DESC")
    fun searchConversations(userId: String, query: String): Flow<List<ConversationEntity>>

    /**
     * Insert a NEW conversation. Uses IGNORE (not REPLACE) because REPLACE
     * triggers DELETE + INSERT in SQLite, which would cascade-delete all
     * messages via FK ON DELETE CASCADE.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(conversation: ConversationEntity): Long

    @Upsert
    suspend fun upsertAll(conversations: List<ConversationEntity>)

    @Query(
        "SELECT id FROM conversations WHERE id IN (:ids) " +
            "AND owner_user_id IS NOT NULL AND owner_user_id != :userId"
    )
    suspend fun findForeignOwnedIds(userId: String, ids: List<String>): List<String>

    @Query(
        "SELECT * FROM conversations WHERE owner_user_id = :userId " +
            "AND sync_version = 0 AND is_deleted = 0 ORDER BY created_at ASC, id ASC"
    )
    suspend fun getLocalOnlyForImport(userId: String): List<ConversationEntity>

    /** Delete a conversation (cascades to messages via FK) */
    @Query("DELETE FROM conversations WHERE id = :conversationId AND owner_user_id = :userId")
    suspend fun deleteById(userId: String, conversationId: String)

    /** Get a single conversation by ID */
    @Query("SELECT * FROM conversations WHERE id = :id AND owner_user_id = :userId")
    suspend fun getById(userId: String, id: String): ConversationEntity?

    /** Update only title and timestamp without full entity replacement */
    @Query("UPDATE conversations SET title = :title, updated_at = :updatedAt WHERE id = :id AND owner_user_id = :userId")
    suspend fun updateMeta(userId: String, id: String, title: String, updatedAt: Long)

    /** Rename a conversation without bumping its updated_at (no reordering). */
    @Query("UPDATE conversations SET title = :title WHERE id = :id AND owner_user_id = :userId")
    suspend fun updateTitle(userId: String, id: String, title: String)

    /** Toggle the pinned flag (pinned conversations sort to the top). */
    @Query("UPDATE conversations SET is_pinned = :pinned WHERE id = :id AND owner_user_id = :userId")
    suspend fun setPinned(userId: String, id: String, pinned: Boolean)

    @Query("UPDATE conversations SET owner_user_id = :userId WHERE owner_user_id IS NULL")
    suspend fun claimLegacyRows(userId: String)

    @Query("SELECT id FROM conversations WHERE owner_user_id = :userId AND sync_version > 0")
    suspend fun getSyncedIds(userId: String): List<String>

    @Query("DELETE FROM conversations WHERE owner_user_id = :userId AND sync_version > 0 AND id IN (:ids)")
    suspend fun deleteSyncedByIds(userId: String, ids: List<String>)
}
