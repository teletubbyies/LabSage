package com.labsage.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for a conversation/session.
 *
 * Maps 1:1 to the backend's session Id concept.
 * Title is derived from the first user message (first 30 chars, matching the web frontend).
 */
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey
    val id: String,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),

    /** Pinned conversations sort to the top of the list. */
    @ColumnInfo(name = "is_pinned")
    val isPinned: Boolean = false
)
