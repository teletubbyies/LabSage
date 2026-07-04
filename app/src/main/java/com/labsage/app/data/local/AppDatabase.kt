package com.labsage.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.labsage.app.data.local.dao.ConversationDao
import com.labsage.app.data.local.dao.MessageDao
import com.labsage.app.data.local.entity.ConversationEntity
import com.labsage.app.data.local.entity.MessageEntity

/**
 * Room database for LabSage.
 *
 * Two tables:
 *   - conversations: session metadata (title, timestamps, pinned flag)
 *   - messages:      individual chat messages, FK-linked to conversations
 */
@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao

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
    }
}
