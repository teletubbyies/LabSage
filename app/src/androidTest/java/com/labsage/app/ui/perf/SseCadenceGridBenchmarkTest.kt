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
import com.labsage.app.ui.chat.SseUpdateCadence
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.ceil

/**
 * Grid benchmark for the production SSE cadence policy.
 *
 * It models the latest production path:
 * - chunks accumulate in one StringBuilder;
 * - UI/checkpoint snapshots share one toString() when their deadlines coincide;
 * - the first assistant chunk performs a real Room INSERT;
 * - intermediate checkpoints perform real Room UPDATEs;
 * - Done performs one final UPDATE;
 * - the Room Flow is considered paused while streaming, so there is no query after
 *   each checkpoint;
 * - after Done, one equivalent full-conversation query is executed and consumed.
 *
 * Virtual chunk timestamps make cadence counts deterministic. Reported milliseconds
 * measure local snapshot/Room/query work only; the simulated ten seconds of network
 * arrival time is deliberately not slept or included.
 */
@RunWith(AndroidJUnit4::class)
class SseCadenceGridBenchmarkTest {

    @Volatile
    private var uiSnapshotSink: String = ""

    @Test
    fun benchmarkUiAndCheckpointIntervalGrid() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val databaseName = "sse-cadence-grid-${System.nanoTime()}.db"
        val database = Room.databaseBuilder(context, AppDatabase::class.java, databaseName).build()
        try {
            seedHistory(database)
            val pairs = UI_INTERVALS.flatMap { uiMs ->
                CHECKPOINT_INTERVALS.map { checkpointMs -> PairConfig(uiMs, checkpointMs) }
            }

            repeat(WARMUP_ROUNDS) { warmup ->
                val ordered = if (warmup % 2 == 0) pairs else pairs.asReversed()
                ordered.forEach { pair ->
                    measureRound(database, pair, "warmup-$warmup-${pair.uiMs}-${pair.checkpointMs}")
                }
            }

            val samples = pairs.associateWith { mutableListOf<RoundMetrics>() }
            repeat(MEASURED_ROUNDS) { round ->
                val ordered = if (round % 2 == 0) pairs else pairs.asReversed()
                ordered.forEach { pair ->
                    samples.getValue(pair) += measureRound(
                        database,
                        pair,
                        "measure-$round-${pair.uiMs}-${pair.checkpointMs}"
                    )
                }
            }

            Log.i(
                TAG,
                "device=${Build.MANUFACTURER} ${Build.MODEL} api=${Build.VERSION.SDK_INT} " +
                    "chunks=$CHUNK_COUNT chunkPeriodMs=$CHUNK_PERIOD_MS " +
                    "chunkChars=${CHUNK_TEXT.length} finalChars=${CHUNK_COUNT * CHUNK_TEXT.length} " +
                    "historyRows=$HISTORY_ROWS warmup=$WARMUP_ROUNDS rounds=$MEASURED_ROUNDS"
            )
            Log.i(
                TAG,
                "columns=uiMs,checkpointMs,uiPublishes,checkpointUpdates,persistenceOps," +
                    "reconcileQueries,uniqueSnapshots,copiedChars,snapshotMedianMs," +
                    "roomMedianMs,roomP95Ms,reconcileMedianMs,totalMedianMs,totalP95Ms"
            )

            pairs.forEach { pair ->
                val rows = samples.getValue(pair)
                val first = rows.first()
                check(rows.all { it.sameDeterministicCountsAs(first) })
                check(first.reconcileQueries == 1)
                check(first.finalContentMatches)
                Log.i(
                    TAG,
                    listOf(
                        "grid",
                        pair.uiMs,
                        pair.checkpointMs,
                        first.uiPublishes,
                        first.checkpointUpdates,
                        first.persistenceOps,
                        first.reconcileQueries,
                        first.uniqueSnapshots,
                        first.copiedChars,
                        fmt(percentile(rows.map { it.snapshotMs }, 50)),
                        fmt(percentile(rows.map { it.roomMs }, 50)),
                        fmt(percentile(rows.map { it.roomMs }, 95)),
                        fmt(percentile(rows.map { it.reconcileMs }, 50)),
                        fmt(percentile(rows.map { it.totalMs }, 50)),
                        fmt(percentile(rows.map { it.totalMs }, 95))
                    ).joinToString(",")
                )
            }
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    private suspend fun seedHistory(database: AppDatabase) {
        database.conversationDao().insert(
            ConversationEntity(
                id = CONVERSATION_ID,
                title = "SSE cadence grid",
                ownerUserId = USER_ID,
                ownerServerKey = SERVER_KEY
            )
        )
        database.messageDao().upsertAll(
            List(HISTORY_ROWS) { index ->
                MessageEntity(
                    id = "history-$index",
                    conversationId = CONVERSATION_ID,
                    role = if (index % 2 == 0) "user" else "assistant",
                    content = "history message $index ".repeat(8),
                    timestamp = index.toLong(),
                    clientRequestId = "history-request-$index"
                )
            }
        )
    }

    private suspend fun measureRound(
        database: AppDatabase,
        pair: PairConfig,
        runId: String
    ): RoundMetrics {
        val messageId = "stream-$runId"
        val cadence = SseUpdateCadence(
            uiIntervalMs = pair.uiMs,
            checkpointIntervalMs = pair.checkpointMs
        )
        cadence.reset(startedAtMs = 0)
        val answer = StringBuilder(CHUNK_COUNT * CHUNK_TEXT.length)
        var uiPublishes = 0
        var checkpointUpdates = 0
        var persistenceOps = 0
        var uniqueSnapshots = 0
        var copiedChars = 0L
        var snapshotNanos = 0L
        var roomNanos = 0L
        var consumedCharacters = 0L
        val totalStarted = SystemClock.elapsedRealtimeNanos()

        repeat(CHUNK_COUNT) { index ->
            answer.append(CHUNK_TEXT)
            val nowMs = (index + 1L) * CHUNK_PERIOD_MS
            val decision = cadence.onChunk(nowMs)
            val firstChunk = index == 0
            val needsSnapshot = firstChunk || decision.publishUi || decision.checkpoint
            val snapshot = if (needsSnapshot) {
                val started = SystemClock.elapsedRealtimeNanos()
                val value = answer.toString()
                snapshotNanos += SystemClock.elapsedRealtimeNanos() - started
                uniqueSnapshots++
                copiedChars += value.length
                value
            } else {
                null
            }

            if (decision.publishUi) {
                uiPublishes++
                // Mirrors publishing an immutable String into StateFlow: retain the
                // reference so the allocation escapes, without adding a benchmark-only
                // full-string hash traversal.
                uiSnapshotSink = checkNotNull(snapshot)
            }

            if (firstChunk) {
                val started = SystemClock.elapsedRealtimeNanos()
                database.messageDao().insertOwned(
                    USER_ID,
                    MessageEntity(
                        id = messageId,
                        conversationId = CONVERSATION_ID,
                        role = "assistant",
                        content = checkNotNull(snapshot),
                        isPartial = true,
                        originalQuestion = "grid benchmark",
                        status = "streaming",
                        clientRequestId = "request-$runId",
                        timestamp = HISTORY_ROWS.toLong() + 1
                    )
                )
                roomNanos += SystemClock.elapsedRealtimeNanos() - started
                persistenceOps++
                cadence.markCheckpoint(nowMs)
            } else if (decision.checkpoint) {
                val started = SystemClock.elapsedRealtimeNanos()
                database.messageDao().updateContent(
                    userId = USER_ID,
                    messageId = messageId,
                    content = checkNotNull(snapshot),
                    isPartial = true
                )
                roomNanos += SystemClock.elapsedRealtimeNanos() - started
                checkpointUpdates++
                persistenceOps++
            }
        }

        val terminalSnapshotStarted = SystemClock.elapsedRealtimeNanos()
        val finalContent = answer.toString()
        snapshotNanos += SystemClock.elapsedRealtimeNanos() - terminalSnapshotStarted
        uniqueSnapshots++
        copiedChars += finalContent.length
        uiSnapshotSink = finalContent

        val terminalWriteStarted = SystemClock.elapsedRealtimeNanos()
        database.messageDao().finalizeMessage(
            userId = USER_ID,
            messageId = messageId,
            content = finalContent,
            route = "direct"
        )
        roomNanos += SystemClock.elapsedRealtimeNanos() - terminalWriteStarted
        persistenceOps++

        val reconcileStarted = SystemClock.elapsedRealtimeNanos()
        var reconciledContent: String? = null
        var consumedRows = 0
        database.openHelper.readableDatabase.query(
            "SELECT messages.* FROM messages " +
                "INNER JOIN conversations ON conversations.id = messages.conversation_id " +
                "WHERE messages.conversation_id = ? " +
                "AND conversations.owner_user_id = ? " +
                "AND conversations.owner_server_key = ? AND messages.is_deleted = 0 " +
                "ORDER BY messages.timestamp ASC, messages.id ASC",
            arrayOf(CONVERSATION_ID, USER_ID, SERVER_KEY)
        ).use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow("id")
            val contentColumn = cursor.getColumnIndexOrThrow("content")
            while (cursor.moveToNext()) {
                consumedRows++
                val content = cursor.getString(contentColumn)
                consumedCharacters += content.length
                if (cursor.getString(idColumn) == messageId) {
                    reconciledContent = content
                }
            }
        }
        val reconcileNanos = SystemClock.elapsedRealtimeNanos() - reconcileStarted
        val totalNanos = SystemClock.elapsedRealtimeNanos() - totalStarted
        check(consumedRows == HISTORY_ROWS + 1)
        check(reconciledContent == finalContent)
        check(consumedCharacters > finalContent.length)
        check(uiSnapshotSink.length == finalContent.length)

        database.messageDao().deleteById(USER_ID, messageId)

        return RoundMetrics(
            uiPublishes = uiPublishes,
            checkpointUpdates = checkpointUpdates,
            persistenceOps = persistenceOps,
            reconcileQueries = 1,
            uniqueSnapshots = uniqueSnapshots,
            copiedChars = copiedChars,
            snapshotMs = snapshotNanos / 1_000_000.0,
            roomMs = roomNanos / 1_000_000.0,
            reconcileMs = reconcileNanos / 1_000_000.0,
            totalMs = totalNanos / 1_000_000.0,
            finalContentMatches = reconciledContent == finalContent
        )
    }

    private fun percentile(values: List<Double>, percentile: Int): Double {
        val sorted = values.sorted()
        val index = ceil(percentile / 100.0 * sorted.size).toInt().coerceAtLeast(1) - 1
        return sorted[index]
    }

    private fun fmt(value: Double): String = "%.3f".format(java.util.Locale.US, value)

    private data class PairConfig(val uiMs: Long, val checkpointMs: Long)

    private data class RoundMetrics(
        val uiPublishes: Int,
        val checkpointUpdates: Int,
        val persistenceOps: Int,
        val reconcileQueries: Int,
        val uniqueSnapshots: Int,
        val copiedChars: Long,
        val snapshotMs: Double,
        val roomMs: Double,
        val reconcileMs: Double,
        val totalMs: Double,
        val finalContentMatches: Boolean
    ) {
        fun sameDeterministicCountsAs(other: RoundMetrics): Boolean =
            uiPublishes == other.uiPublishes &&
                checkpointUpdates == other.checkpointUpdates &&
                persistenceOps == other.persistenceOps &&
                reconcileQueries == other.reconcileQueries &&
                uniqueSnapshots == other.uniqueSnapshots &&
                copiedChars == other.copiedChars
    }

    private companion object {
        const val TAG = "SseCadenceGrid"
        const val USER_ID = "grid-user"
        const val SERVER_KEY = "grid-server"
        const val CONVERSATION_ID = "grid-conversation"
        const val HISTORY_ROWS = 500
        const val CHUNK_COUNT = 1_000
        const val CHUNK_PERIOD_MS = 10L
        const val CHUNK_TEXT = "0123456789abcdef0123456789abcdef"
        const val WARMUP_ROUNDS = 5
        const val MEASURED_ROUNDS = 30
        val UI_INTERVALS = listOf(33L, 50L, 100L)
        val CHECKPOINT_INTERVALS = listOf(250L, 500L, 1_000L)
    }
}
