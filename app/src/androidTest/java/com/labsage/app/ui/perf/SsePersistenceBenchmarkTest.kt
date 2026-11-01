package com.labsage.app.ui.perf

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.labsage.app.data.local.AppDatabase
import com.labsage.app.data.local.entity.ConversationEntity
import com.labsage.app.data.local.entity.MessageEntity
import com.labsage.app.ui.chat.SseUpdateCadence
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.ceil

/**
 * Trace-driven A/B of Room write counts: the legacy "every 5 chunks" strategy
 * vs the production SseUpdateCadence (500 ms checkpoints), replayed over REAL
 * recorded SSE arrival traces (see scripts/record_sse_trace.py) plus one
 * synthetic high-density scenario for comparison with the grid report.
 *
 * Real traces matter because chunk arrival is far from uniform: the direct
 * trace has 216 ms stalls, the react trace arrives in near-zero-gap bursts.
 * Both arms replay the SAME trace, so the comparison isolates the cadence
 * policy from network/model behaviour.
 */
@RunWith(AndroidJUnit4::class)
class SsePersistenceBenchmarkTest {

    private data class TraceChunk(val tMs: Double, val text: String)

    private data class Scenario(
        val name: String,
        val chunks: List<TraceChunk>,
        /** Synthetic scenarios describe themselves in the report. */
        val origin: String
    )

    @Test
    fun compareLegacyAndTimedCheckpointPersistence() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val databaseName = "sse-perf-${System.nanoTime()}.db"
        val database = Room.databaseBuilder(context, AppDatabase::class.java, databaseName).build()
        try {
            seed(database)
            val scenarios = listOf(
                realTrace("direct", "sse_trace_direct.json"),
                realTrace("react", "sse_trace_react.json"),
                Scenario(
                    name = "synthetic-1000@10ms",
                    origin = "synthetic (grid-report load: 1000 chunks × 10 ms)",
                    chunks = List(1_000) { index ->
                        TraceChunk((index + 1) * 10.0, "0123456789abcdef0123456789abcdef")
                    }
                )
            )

            val results = mutableListOf<Triple<Scenario, Int, Int>>() // scenario, legacyWrites, optimizedWrites
            scenarios.forEach { scenario ->
                val (legacySnapshots, optimizedSnapshots, finalContent) = replay(scenario)
                // Warm up both arms outside the measured rounds (JIT/page cache).
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

                val writeReduction = 100.0 *
                    (1.0 - optimizedSnapshots.size.toDouble() / legacySnapshots.size)
                results += Triple(scenario, legacySnapshots.size, optimizedSnapshots.size)
                Log.i(
                    TAG,
                    "scenario=${scenario.name} origin=${scenario.origin} " +
                        "chunks=${scenario.chunks.size} " +
                        "durationMs=${scenario.chunks.last().tMs.toInt()} " +
                        "chars=${finalContent.length}"
                )
                Log.i(
                    TAG,
                    "  legacy writes=${legacySnapshots.size} medianMs=${fmt(percentile(legacyTimesMs, 50))} " +
                        "p95Ms=${fmt(percentile(legacyTimesMs, 95))}"
                )
                Log.i(
                    TAG,
                    "  optimized writes=${optimizedSnapshots.size} medianMs=${fmt(percentile(optimizedTimesMs, 50))} " +
                        "p95Ms=${fmt(percentile(optimizedTimesMs, 95))} " +
                        "writeReductionPct=${fmt(writeReduction)}"
                )
            }

            Log.i(TAG, "device=${Build.MANUFACTURER} ${Build.MODEL} api=${Build.VERSION.SDK_INT} rounds=$MEASURED_ROUNDS")
            // The timed cadence bounds writes by O(duration/500ms) instead of
            // O(chunks): it can never write MORE often than the legacy policy.
            results.forEach { (scenario, legacyWrites, optimizedWrites) ->
                check(optimizedWrites <= legacyWrites) {
                    "optimized wrote more than legacy in ${scenario.name}"
                }
            }
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    /** Replays one trace through BOTH strategies and returns their snapshot lists. */
    private fun replay(scenario: Scenario): Triple<List<String>, List<String>, String> {
        val content = StringBuilder()
        val legacySnapshots = mutableListOf<String>()
        val cadence = SseUpdateCadence()
        cadence.reset(0)
        val optimizedSnapshots = mutableListOf<String>()
        var streamingRowInserted = false
        scenario.chunks.forEachIndexed { index, chunk ->
            content.append(chunk.text)
            // Legacy: a write every LEGACY_CHUNKS_PER_WRITE chunks (first chunk
            // takes the INSERT path in both arms, not counted as UPDATE).
            if (index > 0 && (index + 1) % LEGACY_CHUNKS_PER_WRITE == 0) {
                legacySnapshots += content.toString()
            }
            val decision = cadence.onChunk(chunk.tMs.toLong())
            if (!streamingRowInserted) {
                streamingRowInserted = true
                cadence.markCheckpoint(chunk.tMs.toLong())
            } else if (decision.checkpoint) {
                optimizedSnapshots += content.toString()
            }
        }
        // Done branch appends one terminal finalize UPDATE in both arms.
        val finalContent = content.toString()
        return Triple(
            legacySnapshots + finalContent,
            optimizedSnapshots + finalContent,
            finalContent
        )
    }

    private fun realTrace(name: String, assetFile: String): Scenario {
        val json = InstrumentationRegistry.getInstrumentation()
            .context.assets.open(assetFile).bufferedReader().use { it.readText() }
        val root = JSONObject(json)
        val chunks = root.getJSONArray("chunks").let { array ->
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                TraceChunk(item.getDouble("tMs"), item.getString("text"))
            }
        }
        check(chunks.isNotEmpty()) { "trace $name has no chunks" }
        return Scenario(
            name = name,
            origin = "real (${root.getString("recordedAt")} · ${root.getString("question")})",
            chunks = chunks
        )
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
        const val LEGACY_CHUNKS_PER_WRITE = 5
        const val WARMUP_ROUNDS = 2
        const val MEASURED_ROUNDS = 30
    }
}
