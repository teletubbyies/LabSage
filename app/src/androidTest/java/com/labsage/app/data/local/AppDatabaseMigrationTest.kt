package com.labsage.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.labsage.app.data.local.entity.HistoryCursorEntity
import com.labsage.app.data.local.entity.MessageEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {
    private lateinit var context: Context
    private val databaseName = "f06-migration.db"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(databaseName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun migration2To3PreservesRowsRelationsAndAddsStableSyncFields() {
        val existingUuid = UUID.randomUUID().toString()
        val expectedLegacyConversationId = UUID.nameUUIDFromBytes(
            "legacy-conversation:session_old".toByteArray(Charsets.UTF_8)
        ).toString()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(LegacyV2Callback())
                .build()
        )
        helper.writableDatabase.apply {
            execSQL(
                "INSERT INTO conversations VALUES (?, ?, ?, ?, ?)",
                arrayOf<Any?>("session_old", "Legacy", 100L, 200L, 1)
            )
            execSQL(
                "INSERT INTO conversations VALUES (?, ?, ?, ?, ?)",
                arrayOf<Any?>(existingUuid, "Already UUID", 300L, 400L, 0)
            )
            execSQL(
                "INSERT INTO messages VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(7L, "session_old", "user", "question", 110L, null, 0, null, null)
            )
            execSQL(
                "INSERT INTO messages VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(8L, "session_old", "assistant", "half", 120L, "react", 1, "question", "lost")
            )
        }
        helper.close()

        val room = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6
            )
            .build()
        val db = room.openHelper.writableDatabase // Opens, migrates, and validates latest schema.

        val conversationId = db.query(
            "SELECT id, title, is_pinned, version, sync_version, is_deleted, owner_user_id " +
                "FROM conversations WHERE title = 'Legacy'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            val id = cursor.getString(0)
            UUID.fromString(id)
            assertNotEquals("session_old", id)
            assertEquals(expectedLegacyConversationId, id)
            assertEquals("Legacy", cursor.getString(1))
            assertEquals(1, cursor.getInt(2))
            assertEquals(0L, cursor.getLong(3))
            assertEquals(0L, cursor.getLong(4))
            assertEquals(0, cursor.getInt(5))
            assertTrue(cursor.isNull(6))
            id
        }
        db.query("SELECT id FROM conversations WHERE title = 'Already UUID'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(existingUuid, cursor.getString(0))
        }

        db.query(
            "SELECT id, conversation_id, role, content, status, client_request_id, client_created_at, sync_version, is_deleted " +
                "FROM messages ORDER BY timestamp, id"
        ).use { cursor ->
            assertEquals(2, cursor.count)
            assertTrue(cursor.moveToFirst())
            UUID.fromString(cursor.getString(0))
            assertEquals(conversationId, cursor.getString(1))
            assertEquals("user", cursor.getString(2))
            assertEquals("question", cursor.getString(3))
            assertEquals("completed", cursor.getString(4))
            val requestId = cursor.getString(5)
            UUID.fromString(requestId)
            assertEquals(110L, cursor.getLong(6))
            assertEquals(0L, cursor.getLong(7))
            assertEquals(0, cursor.getInt(8))

            assertTrue(cursor.moveToNext())
            UUID.fromString(cursor.getString(0))
            assertEquals(conversationId, cursor.getString(1))
            assertEquals("assistant", cursor.getString(2))
            assertEquals("half", cursor.getString(3))
            assertEquals("partial", cursor.getString(4))
            assertEquals(requestId, cursor.getString(5))
            assertEquals(120L, cursor.getLong(6))
            assertFalse(cursor.moveToNext())
        }

        runBlocking {
            room.conversationDao().claimLegacyRows("migration-user")
            assertEquals(
                "migration-user",
                room.conversationDao().getById("migration-user", conversationId)?.ownerUserId
            )
            val migrated = room.messageDao()
                .getMessagesByConversation("migration-user", conversationId).first()
            assertEquals(listOf("question", "half"), migrated.map { it.content })
            assertTrue(migrated.all { runCatching { UUID.fromString(it.id) }.isSuccess })

            val newMessageId = UUID.randomUUID().toString()
            room.messageDao().insert(
                MessageEntity(
                    id = newMessageId,
                    conversationId = conversationId,
                    role = "user",
                    content = "new data",
                    clientRequestId = UUID.randomUUID().toString()
                )
            )
            val oldAndNew = room.messageDao()
                .getMessagesByConversation("migration-user", conversationId).first()
            assertEquals(3, oldAndNew.size)
            assertTrue(oldAndNew.any { it.id == newMessageId && it.content == "new data" })
        }

        db.execSQL("DELETE FROM conversations WHERE id = ?", arrayOf(conversationId))
        db.query("SELECT COUNT(*) FROM messages").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0L, cursor.getLong(0))
        }

        // The v5 → v6 step adds the cursor table; it must be usable right away.
        runBlocking {
            room.historyCursorDao().upsert(
                HistoryCursorEntity.conversationsCursor("migration-user", "server-a", 12)
            )
            assertEquals(
                12L,
                room.historyCursorDao().findBound(
                    HistoryCursorEntity.SCOPE_CONVERSATIONS,
                    "migration-user",
                    "",
                    "server-a"
                )?.cursor
            )
        }
        room.close()
    }

    private class LegacyV2Callback : SupportSQLiteOpenHelper.Callback(2) {
        override fun onCreate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE conversations (
                    id TEXT NOT NULL PRIMARY KEY,
                    title TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    is_pinned INTEGER NOT NULL
                )""".trimIndent()
            )
            db.execSQL(
                """CREATE TABLE messages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    conversation_id TEXT NOT NULL,
                    role TEXT NOT NULL,
                    content TEXT NOT NULL,
                    timestamp INTEGER NOT NULL,
                    route TEXT,
                    is_partial INTEGER NOT NULL,
                    original_question TEXT,
                    error_message TEXT,
                    FOREIGN KEY(conversation_id) REFERENCES conversations(id) ON UPDATE NO ACTION ON DELETE CASCADE
                )""".trimIndent()
            )
            db.execSQL("CREATE INDEX index_messages_conversation_id ON messages(conversation_id)")
        }

        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
}
