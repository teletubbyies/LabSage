package com.labsage.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * Room entity for a history-sync cursor.
 *
 * Cursors live in the same database as the cached rows they describe, so a
 * cache wipe clears data and progress together, and the sync commit can
 * merge a page and advance its cursor in one transaction. The row itself is
 * the binding: a cursor is only reusable while [serverKey] still names the
 * backend that produced the cached page.
 */
@Entity(
    tableName = "history_cursors",
    primaryKeys = ["scope", "user_id", "conversation_id"],
    indices = [Index("user_id")]
)
data class HistoryCursorEntity(
    /** [SCOPE_CONVERSATIONS] or [SCOPE_MESSAGES]. */
    val scope: String,

    @ColumnInfo(name = "user_id")
    val userId: String,

    /** Conversation the cursor belongs to; empty for the conversations scope. */
    @ColumnInfo(name = "conversation_id")
    val conversationId: String,

    /** Backend that produced the cached page this cursor summarizes. */
    @ColumnInfo(name = "server_key")
    val serverKey: String,

    /** Server-returned high-water mark; only trustworthy while the binding matches. */
    val cursor: Long
) {
    companion object {
        const val SCOPE_CONVERSATIONS = "conversations"
        const val SCOPE_MESSAGES = "messages"

        fun conversationsCursor(userId: String, serverKey: String, cursor: Long) =
            HistoryCursorEntity(
                scope = SCOPE_CONVERSATIONS,
                userId = userId,
                conversationId = "",
                serverKey = serverKey,
                cursor = cursor
            )

        fun messagesCursor(
            userId: String,
            serverKey: String,
            conversationId: String,
            cursor: Long
        ) = HistoryCursorEntity(
            scope = SCOPE_MESSAGES,
            userId = userId,
            conversationId = conversationId,
            serverKey = serverKey,
            cursor = cursor
        )
    }
}
