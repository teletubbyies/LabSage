package com.labsage.app.ui.perf

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.labsage.app.data.local.AppDatabase
import com.labsage.app.data.local.entity.ConversationEntity
import com.labsage.app.data.local.entity.MessageEntity
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.ceil

/**
 * Measures the real SQLite/Room work caused by the legacy per-5-chunk strategy
 * against the production 500 ms checkpoint cadence.
 *
 * This is intentionally a comparative test, not a universal device benchmark:
 * both strategies run alternately against the same in-memory Room database,
 * dataset, answer snapshots and process.
 */
@RunWith(AndroidJUnit4::class)
class SsePersistenceBenchmarkTest {

    @Test
    fun compareLegacyAndTimedCheckpointPersistence() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val databaseName = "sse-perf-${System.nanoTime()}.db"
        val database = Room.databaseBuilder(context, AppDatabase::class.java, databaseName).build()
        try {
            seed(database)
            val finalContent = buildString(CHUNK_COUNT * CHUNK_TEXT.length) {
                repeat(CHUNK_COUNT) { append(CHUNK_TEXT) }
            }
            val legacySnapshots = buildSnapshots(
                finalContent = finalContent,
                checkpointIndices = (LEGACY_CHUNKS_PER_WRITE..CHUNK_COUNT)
                    .step(LEGACY_CHUNKS_PER_WRITE)
                    .toList()
            ) + finalContent // terminal finalization is an additional UPDATE
            val optimizedSnapshots = buildSnapshots(
                finalContent = finalContent,
                checkpointIndices = (51 until CHUNK_COUNT step 50).toList()
            ) + finalContent

            repeat(WARMUP_ROUNDS) {
                measureRound(database, legacySnapshots)
                measureRound(database, optimizedSnapshots)
            }

            val legacyTimesMs = mutableListOf<Double>()
            val optimizedTimesMs = mutableListOf<Double>()
            repeat(MEASURED_ROUNDS) { round ->
                if (round % 2 == 0) {
                    legacyTimesMs += measureRound(database, legacySnapshots)
                    optimizedTimesMs += measureRound(database, optimizedSnapshots)
                } else {
                    optimizedTimesMs += measureRound(database, optimizedSnapshots)
                    legacyTimesMs += measureRound(database, legacySnapshots)
                }
            }

            val stored = database.messageDao().getOwnedMessage(USER_ID, STREAM_MESSAGE_ID)
            check(stored?.content == finalContent) { "final Room content differs from SSE content" }

            val legacyMedian = percentile(legacyTimesMs, 50)
            val optimizedMedian = percentile(optimizedTimesMs, 50)
            val legacyP95 = percentile(legacyTimesMs, 95)
            val optimizedP95 = percentile(optimizedTimesMs, 95)
            val writeReduction = 100.0 *
                (1.0 - optimizedSnapshots.size.toDouble() / legacySnapshots.size)
            val medianReduction = 100.0 * (1.0 - optimizedMedian / legacyMedian)
            val p95Reduction = 100.0 * (1.0 - optimizedP95 / legacyP95)

            Log.i(TAG, "device=${Build.MANUFACTURER} ${Build.MODEL} api=${Build.VERSION.SDK_INT}")
            Log.i(
                TAG,
                "chunks=$CHUNK_COUNT chunkChars=${CHUNK_TEXT.length} finalChars=${finalContent.length} " +
                    "historyRows=$HISTORY_ROWS warmup=$WARMUP_ROUNDS rounds=$MEASURED_ROUNDS"
            )
            Log.i(
                TAG,
                "legacy writes=${legacySnapshots.size} medianMs=${fmt(legacyMedian)} " +
                    "p95Ms=${fmt(legacyP95)}"
            )
            Log.i(
                TAG,
                "optimized writes=${optimizedSnapshots.size} medianMs=${fmt(optimizedMedian)} " +
                    "p95Ms=${fmt(optimizedP95)}"
            )
            Log.i(
                TAG,
                "writeReductionPct=${fmt(writeReduction)} medianReductionPct=${fmt(medianReduction)} " +
                    "p95ReductionPct=${fmt(p95Reduction)} finalContentMatch=true"
            )

            check(optimizedSnapshots.size <= 20)
            check(writeReduction >= 85.0)
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    private suspend fun seed(database: AppDatabase) {
        database.conversationDao().insert(
            ConversationEntity(
                id = CONVERSATION_ID,
                title = "SSE benchmark",
                ownerUserId = USER_ID,
                ownerServerKey = SERVER_KEY
            )
        )
        val history = List(HISTORY_ROWS) { index ->
            MessageEntity(
                id = "history-$index",
                conversationId = CONVERSATION_ID,
                role = if (index % 2 == 0) "user" else "assistant",
                content = "history message $index ".repeat(8),
                timestamp = index.toLong(),
                clientRequestId = "history-request-$index"
            )
        }
        database.messageDao().upsertAll(
            history + MessageEntity(
                id = STREAM_MESSAGE_ID,
                conversationId = CONVERSATION_ID,
                role = "assistant",
                content = "",
                isPartial = true,
                status = "streaming",
                clientRequestId = "stream-request"
            )
        )
    }

    private fun buildSnapshots(
        finalContent: String,
        checkpointIndices: List<Int>
    ): List<String> = checkpointIndices.map { chunkIndex ->
        finalContent.substring(0, chunkIndex * CHUNK_TEXT.length)
    }

    private suspend fun measureRound(
        database: AppDatabase,
        snapshots: List<String>
    ): Double {
        val started = SystemClock.elapsedRealtimeNanos()
        snapshots.forEach { content ->
            database.messageDao().updateContent(
                userId = USER_ID,
                messageId = STREAM_MESSAGE_ID,
                content = content,
                isPartial = true
            )
            // Force the same full-conversation snapshot work that a Room invalidation
            // drives in production, rather than measuring UPDATE statements alone.
            database.openHelper.readableDatabase.query(
                "SELECT messages.* FROM messages " +
                    "INNER JOIN conversations ON conversations.id = messages.conversation_id " +
                    "WHERE messages.conversation_id = ? " +
                    "AND conversations.owner_user_id = ? " +
                    "AND conversations.owner_server_key = ? AND messages.is_deleted = 0 " +
                    "ORDER BY messages.timestamp ASC, messages.id ASC",
                arrayOf(CONVERSATION_ID, USER_ID, SERVER_KEY)
            ).use { cursor ->
                var consumedCharacters = 0
                val contentColumn = cursor.getColumnIndexOrThrow("content")
                while (cursor.moveToNext()) {
                    consumedCharacters += cursor.getString(contentColumn).length
                }
                check(consumedCharacters > 0)
            }
        }
        return (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
    }

    private fun percentile(values: List<Double>, percentile: Int): Double {
        val sorted = values.sorted()
        val index = ceil(percentile / 100.0 * sorted.size).toInt().coerceAtLeast(1) - 1
        return sorted[index]
    }

    private fun fmt(value: Double): String = "%.2f".format(java.util.Locale.US, value)

    private companion object {
        const val TAG = "SsePerf"
        const val USER_ID = "benchmark-user"
        const val SERVER_KEY = "benchmark-server"
        const val CONVERSATION_ID = "benchmark-conversation"
        const val STREAM_MESSAGE_ID = "stream-message"
        const val HISTORY_ROWS = 500
        const val CHUNK_COUNT = 1_000
        const val CHUNK_TEXT = "0123456789abcdef0123456789abcdef"
        const val LEGACY_CHUNKS_PER_WRITE = 5
        const val WARMUP_ROUNDS = 5
        const val MEASURED_ROUNDS = 30
    }
}
