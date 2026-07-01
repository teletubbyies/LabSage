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
    indices = [Index("conversation_id")]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

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
    val errorMessage: String? = null
)
