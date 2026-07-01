package com.labsage.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.labsage.app.data.local.dao.ConversationDao
import com.labsage.app.data.local.dao.MessageDao
import com.labsage.app.data.local.entity.ConversationEntity
import com.labsage.app.data.local.entity.MessageEntity

/**
 * Room database for LabSage.
 *
 * Two tables:
 *   - conversations: session metadata (title, timestamps)
 *   - messages:      individual chat messages, FK-linked to conversations
 */
@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
}
