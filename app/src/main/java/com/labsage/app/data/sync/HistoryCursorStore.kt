package com.labsage.app.data.sync

import com.labsage.app.data.local.AppDatabase
import com.labsage.app.data.local.dao.HistoryCursorDao
import com.labsage.app.data.local.entity.HistoryCursorEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Read side of the history-sync cursors.
 *
 * Cursors are written exclusively by [RoomHistorySyncStore], which merges a
 * page and advances its cursor in one Room transaction, so "cursor ahead of
 * data" cannot happen by construction. This interface exposes the reads,
 * the boundness observations, and the binding invalidation used when the
 * cached pages no longer belong to the active backend.
 */
interface HistoryCursorStore {
    suspend fun invalidateCacheBinding(userId: String)
    fun observeConversationsCacheBound(userId: String, serverKey: String): Flow<Boolean>
    suspend fun isConversationsCacheBound(userId: String, serverKey: String): Boolean
    suspend fun getConversationsCursor(userId: String, serverKey: String): Long?
    fun observeMessagesCacheBound(
        userId: String,
        serverKey: String,
        conversationId: String
    ): Flow<Boolean>
    suspend fun isMessagesCacheBound(
        userId: String,
        serverKey: String,
        conversationId: String
    ): Boolean
    suspend fun getMessagesCursor(userId: String, serverKey: String, conversationId: String): Long?
}

/** Room-backed cursors: one row per scope slot, bound to the backend that produced the page. */
class RoomHistoryCursorStore(
    private val cursorDao: HistoryCursorDao
) : HistoryCursorStore {
    constructor(database: AppDatabase) : this(database.historyCursorDao())

    override suspend fun invalidateCacheBinding(userId: String) {
        cursorDao.deleteAllForUser(userId)
    }

    override fun observeConversationsCacheBound(
        userId: String,
        serverKey: String
    ): Flow<Boolean> = observeBound(HistoryCursorEntity.SCOPE_CONVERSATIONS, userId, "", serverKey)

    override suspend fun isConversationsCacheBound(userId: String, serverKey: String): Boolean =
        cursorDao.findBound(HistoryCursorEntity.SCOPE_CONVERSATIONS, userId, "", serverKey) != null

    override suspend fun getConversationsCursor(userId: String, serverKey: String): Long? =
        cursorDao.findBound(HistoryCursorEntity.SCOPE_CONVERSATIONS, userId, "", serverKey)
            ?.cursor

    override fun observeMessagesCacheBound(
        userId: String,
        serverKey: String,
        conversationId: String
    ): Flow<Boolean> =
        observeBound(HistoryCursorEntity.SCOPE_MESSAGES, userId, conversationId, serverKey)

    override suspend fun isMessagesCacheBound(
        userId: String,
        serverKey: String,
        conversationId: String
    ): Boolean =
        cursorDao.findBound(HistoryCursorEntity.SCOPE_MESSAGES, userId, conversationId, serverKey) != null

    override suspend fun getMessagesCursor(
        userId: String,
        serverKey: String,
        conversationId: String
    ): Long? = cursorDao.findBound(HistoryCursorEntity.SCOPE_MESSAGES, userId, conversationId, serverKey)
        ?.cursor

    private fun observeBound(
        scope: String,
        userId: String,
        conversationId: String,
        serverKey: String
    ): Flow<Boolean> = cursorDao.observeBound(scope, userId, conversationId, serverKey)
        .map { it != null }
}
