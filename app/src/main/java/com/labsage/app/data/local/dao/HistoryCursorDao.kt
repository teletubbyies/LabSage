package com.labsage.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.labsage.app.data.local.entity.HistoryCursorEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryCursorDao {

    /** Bound row for a cursor slot, or null when absent or owned by another backend. */
    @Query(
        "SELECT * FROM history_cursors WHERE scope = :scope AND user_id = :userId " +
            "AND conversation_id = :conversationId AND server_key = :serverKey LIMIT 1"
    )
    suspend fun findBound(
        scope: String,
        userId: String,
        conversationId: String,
        serverKey: String
    ): HistoryCursorEntity?

    /** Flow variant of [findBound] for cache-boundness observation. */
    @Query(
        "SELECT * FROM history_cursors WHERE scope = :scope AND user_id = :userId " +
            "AND conversation_id = :conversationId AND server_key = :serverKey LIMIT 1"
    )
    fun observeBound(
        scope: String,
        userId: String,
        conversationId: String,
        serverKey: String
    ): Flow<HistoryCursorEntity?>

    @Upsert
    suspend fun upsert(cursor: HistoryCursorEntity)

    /** Drops every cursor for the account after the cache binding is invalidated. */
    @Query("DELETE FROM history_cursors WHERE user_id = :userId")
    suspend fun deleteAllForUser(userId: String)

    /** Drops message cursors for conversations that disappeared from the server. */
    @Query(
        "DELETE FROM history_cursors WHERE scope = 'messages' AND user_id = :userId " +
            "AND conversation_id IN (:conversationIds)"
    )
    suspend fun deleteMessageCursors(userId: String, conversationIds: List<String>)
}
