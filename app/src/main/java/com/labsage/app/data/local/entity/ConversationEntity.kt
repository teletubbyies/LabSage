package com.labsage.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for a conversation/session.
 *
 * Maps 1:1 to the backend's session Id concept.
 * Title is derived from the first user message (first 30 chars, matching the web frontend).
 */
@Entity(
    tableName = "conversations",
    indices = [Index("owner_user_id"), Index("owner_server_key")]
)
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
    val isPinned: Boolean = false,

    /** Optimistic-lock version assigned by the server (0 = legacy/local only). */
    val version: Long = 0,

    /** Monotonic server sync cursor (0 = not synchronized yet). */
    @ColumnInfo(name = "sync_version")
    val syncVersion: Long = 0,

    /** Local tombstone mirrored from the server during sync. */
    @ColumnInfo(name = "is_deleted")
    val isDeleted: Boolean = false,

    /** Authenticated account that owns this cached conversation; null only for unclaimed legacy rows. */
    @ColumnInfo(name = "owner_user_id")
    val ownerUserId: String? = null,

    /** Backend namespace for this cache row; null only until the v5 claim step. */
    @ColumnInfo(name = "owner_server_key")
    val ownerServerKey: String? = null
)
