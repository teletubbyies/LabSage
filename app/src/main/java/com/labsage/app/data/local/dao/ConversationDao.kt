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
    @Query("SELECT * FROM conversations WHERE owner_user_id = :userId AND owner_server_key = :serverKey AND is_deleted = 0 ORDER BY is_pinned DESC, updated_at DESC, id DESC")
    fun getAllConversations(userId: String, serverKey: String = "server-a"): Flow<List<ConversationEntity>>

    /** Title-substring search, preserving pinned-first ordering. */
    @Query("SELECT * FROM conversations WHERE owner_user_id = :userId AND owner_server_key = :serverKey AND is_deleted = 0 AND title LIKE '%' || :query || '%' ORDER BY is_pinned DESC, updated_at DESC, id DESC")
    fun searchConversations(userId: String, query: String, serverKey: String = "server-a"): Flow<List<ConversationEntity>>

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
            "AND owner_user_id IS NOT NULL AND (owner_user_id != :userId " +
            "OR owner_server_key IS NOT NULL AND owner_server_key != :serverKey)"
    )
    suspend fun findForeignOwnedIds(userId: String, ids: List<String>, serverKey: String): List<String>

    @Query(
        "SELECT * FROM conversations WHERE owner_user_id = :userId " +
            "AND owner_server_key = :serverKey AND sync_version = 0 " +
            "AND is_deleted = 0 ORDER BY created_at ASC, id ASC"
    )
    suspend fun getLocalOnlyForImport(userId: String, serverKey: String): List<ConversationEntity>

    /** Delete a conversation (cascades to messages via FK) */
    @Query("DELETE FROM conversations WHERE id = :conversationId AND owner_user_id = :userId AND owner_server_key = :serverKey")
    suspend fun deleteById(userId: String, conversationId: String, serverKey: String = "server-a")

    /** Get a single conversation by ID */
    @Query("SELECT * FROM conversations WHERE id = :id AND owner_user_id = :userId AND owner_server_key = :serverKey")
    suspend fun getById(userId: String, id: String, serverKey: String = "server-a"): ConversationEntity?

    /** Update only title and timestamp without full entity replacement */
    @Query("UPDATE conversations SET title = :title, updated_at = :updatedAt WHERE id = :id AND owner_user_id = :userId AND owner_server_key = :serverKey")
    suspend fun updateMeta(userId: String, id: String, title: String, updatedAt: Long, serverKey: String = "server-a")

    /** Rename a conversation without bumping its updated_at (no reordering). */
    @Query("UPDATE conversations SET title = :title WHERE id = :id AND owner_user_id = :userId AND owner_server_key = :serverKey")
    suspend fun updateTitle(userId: String, id: String, title: String, serverKey: String = "server-a")

    /** Toggle the pinned flag (pinned conversations sort to the top). */
    @Query("UPDATE conversations SET is_pinned = :pinned WHERE id = :id AND owner_user_id = :userId AND owner_server_key = :serverKey")
    suspend fun setPinned(userId: String, id: String, pinned: Boolean, serverKey: String = "server-a")

    @Query("UPDATE conversations SET owner_user_id = :userId, owner_server_key = :serverKey WHERE owner_server_key IS NULL AND (owner_user_id IS NULL OR owner_user_id = :userId)")
    suspend fun claimLegacyRows(userId: String, serverKey: String = "server-a")

    @Query("SELECT id FROM conversations WHERE owner_user_id = :userId AND owner_server_key = :serverKey AND sync_version > 0")
    suspend fun getSyncedIds(userId: String, serverKey: String): List<String>

    @Query("SELECT EXISTS(SELECT 1 FROM conversations WHERE owner_user_id = :userId AND owner_server_key = :serverKey AND sync_version > 0)")
    suspend fun hasSyncedRows(userId: String, serverKey: String): Boolean

    @Query("DELETE FROM conversations WHERE owner_user_id = :userId AND owner_server_key = :serverKey AND sync_version > 0 AND id IN (:ids)")
    suspend fun deleteSyncedByIds(userId: String, ids: List<String>, serverKey: String)

    @Query("DELETE FROM conversations WHERE owner_user_id = :userId AND owner_server_key != :serverKey")
    suspend fun deleteOtherServerRows(userId: String, serverKey: String)

    /** Ids cached for this account under a different backend, mirroring [deleteOtherServerRows]. */
    @Query("SELECT id FROM conversations WHERE owner_user_id = :userId AND owner_server_key != :serverKey")
    suspend fun getOtherServerIds(userId: String, serverKey: String): List<String>
}
