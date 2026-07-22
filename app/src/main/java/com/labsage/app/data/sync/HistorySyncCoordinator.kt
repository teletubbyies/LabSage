package com.labsage.app.data.sync

import com.labsage.app.data.remote.ConversationDto
import com.labsage.app.data.remote.MessageDto

interface HistoryRemoteSource {
    suspend fun getConversations(): List<ConversationDto>
    suspend fun getMessages(conversationId: String): List<MessageDto>
}

/** Coordinates cache-first history refreshes while keeping Room as the only UI source. */
class HistorySyncCoordinator(
    private val remote: HistoryRemoteSource,
    private val local: RoomHistorySyncStore
) {
    suspend fun syncConversations(userId: String) {
        // This local step intentionally happens before network I/O. A migrated
        // device cache remains visible even when the first authenticated refresh fails.
        local.claimLegacyConversations(userId)
        local.syncConversations(userId, remote.getConversations())
    }

    suspend fun syncMessages(userId: String, conversationId: String) {
        local.claimLegacyConversations(userId)
        if (!local.hasOwnedConversation(userId, conversationId)) {
            // Notification/deep-link navigation can race the list refresh after a
            // clean install. Populate the parent first so the FK-safe message sync
            // cannot silently succeed with an empty screen.
            syncConversations(userId)
        }
        check(local.hasOwnedConversation(userId, conversationId)) {
            "Conversation is unavailable for the authenticated account"
        }
        local.syncMessages(userId, conversationId, remote.getMessages(conversationId))
    }
}
