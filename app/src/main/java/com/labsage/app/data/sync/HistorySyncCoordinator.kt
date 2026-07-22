package com.labsage.app.data.sync

import com.labsage.app.data.remote.ConversationDto
import com.labsage.app.data.remote.MessageDto
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class HistoryPage<T>(
    val items: List<T>,
    val nextCursor: Long,
    val serverTime: Long
)

interface HistoryRemoteSource {
    suspend fun getConversations(
        userId: String,
        serverKey: String,
        updatedAfter: Long?
    ): HistoryPage<ConversationDto>

    suspend fun getMessages(
        userId: String,
        serverKey: String,
        conversationId: String,
        updatedAfter: Long?
    ): HistoryPage<MessageDto>
}

/** Coordinates full-then-incremental refreshes while keeping Room as the UI source. */
class HistorySyncCoordinator(
    private val remote: HistoryRemoteSource,
    private val local: RoomHistorySyncStore,
    private val cursors: HistoryCursorStore,
    private val isBindingCurrent: (userId: String, serverKey: String) -> Boolean = { _, _ -> true },
    private val commitWithStableBinding: suspend (
        userId: String,
        serverKey: String,
        block: suspend () -> Unit
    ) -> Unit = { userId, serverKey, block ->
        check(isBindingCurrent(userId, serverKey))
        block()
        check(isBindingCurrent(userId, serverKey))
    }
) {
    private val mutex = Mutex()

    suspend fun syncConversations(userId: String, serverKey: String, forceFull: Boolean = false) =
        mutex.withLock {
            syncConversationsLocked(userId, serverKey, forceFull)
        }

    suspend fun syncMessages(userId: String, serverKey: String, conversationId: String) =
        mutex.withLock {
            requireCurrentBinding(userId, serverKey)
            local.claimLegacyConversations(userId, serverKey)
            if (!cursors.isConversationsCacheBound(userId, serverKey) ||
                !local.hasOwnedConversation(userId, serverKey, conversationId)
            ) {
                // A deep link can arrive with an old cursor but an empty Room DB.
                // Rebuild the parent cache from a full page before inserting children.
                syncConversationsLocked(userId, serverKey, forceFull = true)
            }
            check(local.hasOwnedConversation(userId, serverKey, conversationId)) {
                "Conversation is unavailable for the authenticated account"
            }

            val storedCursor = cursors.getMessagesCursor(userId, serverKey, conversationId)
            val cursor = storedCursor?.takeIf {
                cursors.isMessagesCacheBound(userId, serverKey, conversationId) &&
                    local.hasSyncedMessages(userId, conversationId)
            }
            requireCurrentBinding(userId, serverKey)
            val page = remote.getMessages(userId, serverKey, conversationId, cursor)
            validatePage(page, cursor) { it.syncVersion }
            commitWithStableBinding(userId, serverKey) {
                if (cursor == null) {
                    local.syncMessages(userId, serverKey, conversationId, page.items)
                } else {
                    local.mergeMessages(userId, serverKey, conversationId, page.items)
                }
                cursors.setMessagesCursor(userId, serverKey, conversationId, page.nextCursor)
            }
        }

    private suspend fun syncConversationsLocked(
        userId: String,
        serverKey: String,
        forceFull: Boolean
    ) {
        requireCurrentBinding(userId, serverKey)
        // Claim migrated rows before I/O so offline history stays account-scoped.
        local.claimLegacyConversations(userId, serverKey)
        val cacheBound = cursors.isConversationsCacheBound(userId, serverKey)
        if (!cacheBound) {
            // Hide a different backend's cache before network I/O. Cursors are
            // retained for diagnostics/re-entry but cannot be reused while unbound.
            commitWithStableBinding(userId, serverKey) {
                cursors.invalidateCacheBinding(userId)
            }
        }
        val storedCursor = cursors.getConversationsCursor(userId, serverKey)
        val cursor = storedCursor?.takeIf {
            !forceFull && cacheBound &&
                local.hasSyncedConversations(userId, serverKey)
        }
        requireCurrentBinding(userId, serverKey)
        val page = remote.getConversations(userId, serverKey, cursor)
        validatePage(page, cursor) { it.syncVersion }
        commitWithStableBinding(userId, serverKey) {
            if (cursor == null) {
                local.syncConversations(
                    userId,
                    serverKey,
                    page.items,
                    replaceSyncedCache = !cacheBound
                )
            } else {
                local.mergeConversations(userId, serverKey, page.items)
            }
            cursors.setConversationsCursor(userId, serverKey, page.nextCursor)
        }
    }

    private fun requireCurrentBinding(userId: String, serverKey: String) {
        check(isBindingCurrent(userId, serverKey)) {
            "Authentication changed during history synchronization"
        }
    }

    private fun <T> validatePage(
        page: HistoryPage<T>,
        requestedCursor: Long?,
        syncVersion: (T) -> Long
    ) {
        require(page.nextCursor >= 0) { "History cursor must be non-negative" }
        require(page.serverTime > 0) { "History response has an invalid server time" }
        val floor = requestedCursor ?: 0L
        require(page.nextCursor >= floor) { "History cursor regressed" }
        require(page.items.all { syncVersion(it) > floor }) {
            "History page contains an item outside the requested cursor"
        }
        require(page.items.all { syncVersion(it) <= page.nextCursor }) {
            "History cursor does not cover every returned item"
        }
    }
}
