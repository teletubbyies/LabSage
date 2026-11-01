package com.labsage.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.labsage.app.data.local.dao.ConversationDao
import com.labsage.app.data.local.dao.HistoryCursorDao
import com.labsage.app.data.local.dao.MessageDao
import com.labsage.app.data.local.entity.ConversationEntity
import com.labsage.app.data.local.entity.HistoryCursorEntity
import com.labsage.app.data.local.entity.MessageEntity
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Room database for LabSage.
 *
 * Three tables:
 *   - conversations:   session metadata (title, timestamps, pinned flag)
 *   - messages:        individual chat messages, FK-linked to conversations
 *   - history_cursors: sync high-water marks, committed with the pages they summarize
 */
@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        HistoryCursorEntity::class
    ],
    version = 8,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun historyCursorDao(): HistoryCursorDao

    companion object {
        /**
         * v1 → v2: add `is_pinned` column to `conversations` for the
         * conversation-organization feature (pin-to-top).
         *
         * ALTER TABLE ADD COLUMN fills existing rows with the DEFAULT (0 = not
         * pinned), so existing conversations are preserved and simply unpinned.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE conversations ADD COLUMN is_pinned INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /** v2 → v3: replace local Long message IDs with stable UUIDs and add sync metadata. */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val conversations = mutableListOf<LegacyConversation>()
                db.query("SELECT id, title, created_at, updated_at, is_pinned FROM conversations").use { cursor ->
                    while (cursor.moveToNext()) {
                        val oldId = cursor.getString(0)
                        conversations += LegacyConversation(
                            oldId = oldId,
                            newId = stableUuid("legacy-conversation", oldId),
                            title = cursor.getString(1),
                            createdAt = cursor.getLong(2),
                            updatedAt = cursor.getLong(3),
                            isPinned = cursor.getInt(4)
                        )
                    }
                }
                val conversationIds = conversations.associate { it.oldId to it.newId }
                val messages = mutableListOf<LegacyMessage>()
                val lastRequestByConversation = mutableMapOf<String, String>()
                db.query(
                    "SELECT id, conversation_id, role, content, timestamp, route, is_partial, original_question, error_message " +
                        "FROM messages ORDER BY conversation_id, timestamp, id"
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val oldId = cursor.getLong(0)
                        val oldConversationId = cursor.getString(1)
                        val role = cursor.getString(2)
                        val requestId = if (role == "user") {
                            stableUuid("legacy-request", "$oldConversationId:$oldId").also {
                                lastRequestByConversation[oldConversationId] = it
                            }
                        } else {
                            lastRequestByConversation[oldConversationId]
                                ?: stableUuid("legacy-request", "$oldConversationId:$oldId")
                        }
                        messages += LegacyMessage(
                            id = stableUuid("legacy-message", "$oldConversationId:$oldId"),
                            conversationId = checkNotNull(conversationIds[oldConversationId]),
                            role = role,
                            content = cursor.getString(3),
                            timestamp = cursor.getLong(4),
                            route = cursor.getStringOrNull(5),
                            isPartial = cursor.getInt(6),
                            originalQuestion = cursor.getStringOrNull(7),
                            errorMessage = cursor.getStringOrNull(8),
                            clientRequestId = requestId
                        )
                    }
                }

                db.execSQL("DROP TABLE messages")
                db.execSQL("DROP TABLE conversations")
                db.execSQL(
                    """CREATE TABLE conversations (
                        id TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        is_pinned INTEGER NOT NULL,
                        version INTEGER NOT NULL,
                        sync_version INTEGER NOT NULL,
                        is_deleted INTEGER NOT NULL
                    )""".trimIndent()
                )
                db.execSQL(
                    """CREATE TABLE messages (
                        id TEXT NOT NULL PRIMARY KEY,
                        conversation_id TEXT NOT NULL,
                        role TEXT NOT NULL,
                        content TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        route TEXT,
                        is_partial INTEGER NOT NULL,
                        original_question TEXT,
                        error_message TEXT,
                        status TEXT NOT NULL,
                        client_request_id TEXT,
                        client_created_at INTEGER NOT NULL,
                        sync_version INTEGER NOT NULL,
                        server_updated_at INTEGER,
                        is_deleted INTEGER NOT NULL,
                        FOREIGN KEY(conversation_id) REFERENCES conversations(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )""".trimIndent()
                )
                conversations.forEach { item ->
                    db.execSQL(
                        "INSERT INTO conversations VALUES (?, ?, ?, ?, ?, 0, 0, 0)",
                        arrayOf<Any?>(item.newId, item.title, item.createdAt, item.updatedAt, item.isPinned)
                    )
                }
                messages.forEach { item ->
                    db.execSQL(
                        "INSERT INTO messages VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, NULL, 0)",
                        arrayOf<Any?>(
                            item.id, item.conversationId, item.role, item.content, item.timestamp,
                            item.route, item.isPartial, item.originalQuestion, item.errorMessage,
                            if (item.isPartial == 1) "partial" else "completed",
                            item.clientRequestId, item.timestamp
                        )
                    )
                }
                db.execSQL("CREATE INDEX index_messages_conversation_id ON messages(conversation_id)")
                db.execSQL("CREATE INDEX index_messages_client_request_id ON messages(client_request_id)")
            }
        }

        /** v3 → v4: scope the local cache to the authenticated account. */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN owner_user_id TEXT")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_conversations_owner_user_id " +
                        "ON conversations(owner_user_id)"
                )
            }
        }

        /** v4 → v5: namespace cached history by backend as well as authenticated user. */
        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN owner_server_key TEXT")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_conversations_owner_server_key " +
                        "ON conversations(owner_server_key)"
                )
            }
        }

        /**
         * v5 → v6: move history-sync cursors from Preferences DataStore into Room.
         *
         * Old DataStore keys are SHA-256 hashes, so their values cannot be
         * recovered here. The first sync after this migration therefore runs
         * full — safe by the sync's own "prefer duplicate over loss" rule.
         */
        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS history_cursors (
                        scope TEXT NOT NULL,
                        user_id TEXT NOT NULL,
                        conversation_id TEXT NOT NULL,
                        server_key TEXT NOT NULL,
                        cursor INTEGER NOT NULL,
                        PRIMARY KEY(scope, user_id, conversation_id)
                    )""".trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_history_cursors_user_id " +
                        "ON history_cursors(user_id)"
                )
            }
        }

        /** v6 → v7: read one conversation's messages in timestamp order. */
        val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS index_messages_conversation_id")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_messages_conversation_id_timestamp " +
                        "ON messages(conversation_id, timestamp)"
                )
            }
        }

        /**
         * v7 → v8: store chunk-level citations inline on assistant messages.
         * Nullable column — existing rows simply have no references.
         */
        val MIGRATION_7_8: Migration = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN references_json TEXT")
            }
        }

        private fun stableUuid(namespace: String, value: String): String =
            runCatching { UUID.fromString(value).toString() }
                .getOrElse {
                    UUID.nameUUIDFromBytes(
                        "$namespace:$value".toByteArray(StandardCharsets.UTF_8)
                    ).toString()
                }

        private fun android.database.Cursor.getStringOrNull(index: Int): String? =
            if (isNull(index)) null else getString(index)

        private data class LegacyConversation(
            val oldId: String,
            val newId: String,
            val title: String,
            val createdAt: Long,
            val updatedAt: Long,
            val isPinned: Int
        )

        private data class LegacyMessage(
            val id: String,
            val conversationId: String,
            val role: String,
            val content: String,
            val timestamp: Long,
            val route: String?,
            val isPartial: Int,
            val originalQuestion: String?,
            val errorMessage: String?,
            val clientRequestId: String
        )
    }
}
