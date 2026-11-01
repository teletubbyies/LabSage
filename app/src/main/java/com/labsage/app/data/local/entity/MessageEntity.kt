package com.labsage.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for a single chat message.
 *
 * Foreign-key linked to [ConversationEntity] so deleting a conversation
 * cascades to delete all its messages.
 *
 * isPartial = true means the stream was interrupted before completion.
 * The UI shows a "Continue" / "Regenerate" banner for partial messages.
 */
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["conversation_id", "timestamp"]), Index("client_request_id")]
)
data class MessageEntity(
    @PrimaryKey
    val id: String,

    @ColumnInfo(name = "conversation_id")
    val conversationId: String,

    /** "user" or "assistant" */
    val role: String,

    val content: String,

    val timestamp: Long = System.currentTimeMillis(),

    /** "direct" or "react" — set for assistant messages after completion */
    val route: String? = null,

    /** True if the stream was interrupted before completion */
    @ColumnInfo(name = "is_partial")
    val isPartial: Boolean = false,

    /** The user question that triggered this response (needed for "continue" resume) */
    @ColumnInfo(name = "original_question")
    val originalQuestion: String? = null,

    /** Error details for partial or failed messages */
    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null,

    /** pending / streaming / completed / partial / failed */
    val status: String = if (isPartial) "partial" else "completed",

    /** Stable idempotency key shared by one user/assistant turn. */
    @ColumnInfo(name = "client_request_id")
    val clientRequestId: String? = null,

    /** Device timestamp retained for legacy import and conflict diagnostics. */
    @ColumnInfo(name = "client_created_at")
    val clientCreatedAt: Long = timestamp,

    /** Monotonic server cursor, 0 while the row exists only locally. */
    @ColumnInfo(name = "sync_version")
    val syncVersion: Long = 0,

    /** Server's last update time after a sync or structured SSE done event. */
    @ColumnInfo(name = "server_updated_at")
    val serverUpdatedAt: Long? = null,

    /** Local tombstone mirrored from the server during sync. */
    @ColumnInfo(name = "is_deleted")
    val isDeleted: Boolean = false,

    /**
     * Chunk-level citations for assistant messages, encoded by
     * ReferencesCodec (null = none). Stored inline as JSON rather than in a
     * separate table: the list is small (<10), always read with its message,
     * and never queried by ref — see docs/CITATION_DESIGN.md (option B1).
     */
    @ColumnInfo(name = "references_json")
    val referencesJson: String? = null
)
