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
import com.labsage.app.ui.chat.TypewriterCadence
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Two-arm instrumented benchmark: legacy 50ms window policy (control) vs the
 * typewriter proportional-drain render loop, over a real Room file database.
 *
 * Both arms drive the Room checkpoint half with the SAME [SseUpdateCadence] on
 * the SAME virtual arrival timestamps, so persistence work is identical by
 * construction (asserted). The arms differ only in the UI publish half:
 * - CONTROL publishes the full accumulated snapshot on each 50ms-window expiry;
 * - TYPEWRITER publishes growing prefixes every [TypewriterCadence.TICK_MS],
 *   advancing by ceil(backlog / horizon).
 *
 * Modes: STEADY (uniform 1000 x 32c / 10ms) and BURST (stall-burst schedule).
 * On STEADY the control CV is near-zero too, so the new-beats-old ordering
 * assert runs on BURST only (see SSE_TYPEWRITER_FEASIBILITY.md §7.1/§7.2).
 *
 * Virtual timestamps make counts deterministic; reported milliseconds measure
 * local snapshot/Room/query work only — the simulated network time is not slept.
 */
@RunWith(AndroidJUnit4::class)
class TypewriterBenchmarkTest {

    @Volatile
    private var uiSnapshotSink: String = ""

    @Test
    fun benchmarkTypewriterVsWindowPolicy() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val databaseName = "typewriter-bench-${System.nanoTime()}.db"
        val database = Room.databaseBuilder(context, AppDatabase::class.java, databaseName).build()
        try {
            seedHistory(database)
            val arms = listOf(ArmConfig.CONTROL, ArmConfig.TYPEWRITER)
                .flatMap { arm -> Mode.entries.map { mode -> arm to mode } }

            repeat(WARMUP_ROUNDS) { warmup ->
                val ordered = if (warmup % 2 == 0) arms else arms.asReversed()
                ordered.forEach { (arm, mode) ->
                    measureRound(database, arm, mode, "warmup-$warmup")
                }
            }

            val samples = arms.associateWith { mutableListOf<RoundMetrics>() }
            repeat(MEASURED_ROUNDS) { round ->
                val ordered = if (round % 2 == 0) arms else arms.asReversed()
                ordered.forEach { (arm, mode) ->
                    samples.getValue(arm to mode) += measureRound(database, arm, mode, "measure-$round")
                }
            }

            Log.i(
                TAG,
                "device=${Build.MANUFACTURER} ${Build.MODEL} api=${Build.VERSION.SDK_INT} " +
                    "steadyChunks=${STEADY_CHUNKS} chunkPeriodMs=$CHUNK_PERIOD_MS " +
                    "chunkChars=${CHUNK_TEXT.length} historyRows=$HISTORY_ROWS " +
                    "warmup=$WARMUP_ROUNDS rounds=$MEASURED_ROUNDS"
            )
            Log.i(
                TAG,
                "columns=arm,mode,publishes,charsPerPublishP50,charsPerPublishP95," +
                    "charsPerPublishCV,charsPerPublishSteadyCV,drainTicks," +
                    "checkpointUpdates,persistenceOps," +
                    "uniqueSnapshots,copiedChars,snapshotMedianMs,roomMedianMs,roomP95Ms," +
                    "reconcileMedianMs,totalMedianMs,totalP95Ms"
            )

            val firstByArmMode = arms.associateWith { (arm, mode) ->
                samples.getValue(arm to mode).first().also { first ->
                    check(samples.getValue(arm to mode).all { it.sameDeterministicCountsAs(first) })
                    check(first.finalContentMatches)
                }
            }
            arms.forEach { (arm, mode) ->
                val rows = samples.getValue(arm to mode)
                val first = firstByArmMode.getValue(arm to mode)
                val steadyDeltas = first.steadyDeltas.map { it.toDouble() }
                if (arm == ArmConfig.TYPEWRITER && mode == Mode.STEADY) {
                    val steadyCv = cv(steadyDeltas)
                    check(steadyDeltas.size >= 100) { "expected steady publishes, got ${steadyDeltas.size}" }
                    check(steadyCv < 0.15) { "typewriter STEADY CV $steadyCv >= 0.15" }
                }
                Log.i(
                    TAG,
                    listOf(
                        "bench",
                        arm.name,
                        mode.name,
                        first.publishes,
                        fmt(first.deltasP50.toDouble()),
                        fmt(first.deltasP95.toDouble()),
                        fmt(cv(first.deltas.map { it.toDouble() })),
                        // Steady-window CV excludes the startup ramp and terminal
                        // drain transients — the headline smoothness metric.
                        fmt(cv(first.steadyDeltas.map { it.toDouble() })),
                        first.drainTicks,
                        first.checkpointUpdates,
                        first.persistenceOps,
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

            // Hard cross-arm asserts: persistence path zero regression, and the
            // headline smoothing claim on the burst schedule.
            Mode.entries.forEach { mode ->
                val control = firstByArmMode.getValue(ArmConfig.CONTROL to mode)
                val typewriter = firstByArmMode.getValue(ArmConfig.TYPEWRITER to mode)
                check(control.checkpointUpdates == typewriter.checkpointUpdates) {
                    "checkpointUpdates diverged: control=${control.checkpointUpdates} " +
                        "typewriter=${typewriter.checkpointUpdates}"
                }
                check(control.persistenceOps == typewriter.persistenceOps) {
                    "persistenceOps diverged: ${control.persistenceOps} vs ${typewriter.persistenceOps}"
                }
                if (mode == Mode.BURST) {
                    val controlCv = cv(control.deltas.map { it.toDouble() })
                    val typewriterCv = cv(typewriter.deltas.map { it.toDouble() })
                    check(typewriterCv < controlCv) {
                        "BURST CV ordering failed: typewriter=$typewriterCv control=$controlCv"
                    }
                }
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
                title = "Typewriter benchmark",
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

    /**
     * One simulated stream over the real Room database. Arrival events drive the
     * checkpoint cadence identically in both arms; tick events (typewriter arm
     * only) are merged into the same virtual timeline, arrival first on ties.
     */
    private suspend fun measureRound(
        database: AppDatabase,
        arm: ArmConfig,
        mode: Mode,
        runId: String
    ): RoundMetrics {
        val messageId = "stream-$runId-${arm.name}-${mode.name}"
        val arrivals = mode.arrivals()
        val lastArrivalMs = arrivals.last().first
        val cadence = SseUpdateCadence(uiIntervalMs = 50L, checkpointIntervalMs = 500L)
        cadence.reset(startedAtMs = 0)
        val typewriter = TypewriterCadence()
        typewriter.reset(0)
        val answer = StringBuilder(arrivals.sumOf { it.second.length })

        var publishes = 0
        var checkpointUpdates = 0
        var persistenceOps = 0
        var uniqueSnapshots = 0
        var copiedChars = 0L
        var snapshotNanos = 0L
        var roomNanos = 0L
        var consumedCharacters = 0L
        var drainTicks = 0
        val deltas = mutableListOf<Int>()
        val steadyDeltas = mutableListOf<Int>()
        var publishedLength = 0
        val totalStarted = SystemClock.elapsedRealtimeNanos()

        suspend fun firstChunkInsert(content: String, nowMs: Long) {
            val started = SystemClock.elapsedRealtimeNanos()
            database.messageDao().insertOwned(
                USER_ID,
                MessageEntity(
                    id = messageId,
                    conversationId = CONVERSATION_ID,
                    role = "assistant",
                    content = content,
                    isPartial = true,
                    originalQuestion = "typewriter benchmark",
                    status = "streaming",
                    clientRequestId = "request-$runId",
                    timestamp = HISTORY_ROWS.toLong() + 1
                )
            )
            roomNanos += SystemClock.elapsedRealtimeNanos() - started
            persistenceOps++
            cadence.markCheckpoint(nowMs)
        }

        suspend fun checkpointUpdate(content: String) {
            val started = SystemClock.elapsedRealtimeNanos()
            database.messageDao().updateContent(
                userId = USER_ID,
                messageId = messageId,
                content = content,
                isPartial = true
            )
            roomNanos += SystemClock.elapsedRealtimeNanos() - started
            checkpointUpdates++
            persistenceOps++
        }

        fun snapshotFull(): String {
            val started = SystemClock.elapsedRealtimeNanos()
            val value = answer.toString()
            snapshotNanos += SystemClock.elapsedRealtimeNanos() - started
            uniqueSnapshots++
            copiedChars += value.length
            return value
        }

        fun publish(content: String, atMs: Long) {
            val delta = content.length - publishedLength
            publishes++
            deltas.add(delta)
            if (atMs > 3_000L && atMs <= lastArrivalMs) steadyDeltas.add(delta)
            publishedLength = content.length
            // StateFlow publish: retain the reference so the allocation escapes.
            uiSnapshotSink = content
        }

        // ---- Merged virtual timeline -------------------------------------
        var arrivalIndex = 0
        var tickIndex = 0
        var firstChunkSeen = false
        var renderedSoFar = 0
        while (true) {
            val arrivalTime = arrivals.getOrNull(arrivalIndex)?.first ?: Long.MAX_VALUE
            val tickTime = if (arm == ArmConfig.TYPEWRITER) {
                (tickIndex + 1L) * TypewriterCadence.TICK_MS
            } else {
                Long.MAX_VALUE
            }
            if (arrivalTime == Long.MAX_VALUE) {
                // Arrivals exhausted. Control has no ticks; typewriter drains
                // the remaining backlog (bounded by MAX_DRAIN_TICKS).
                if (arm != ArmConfig.TYPEWRITER) break
                if (renderedSoFar >= answer.length) break
                check(tickTime <= lastArrivalMs + MAX_DRAIN_TICKS * TypewriterCadence.TICK_MS) {
                    "typewriter drain exceeded $MAX_DRAIN_TICKS ticks"
                }
            }

            if (arrivalTime <= tickTime) {
                // ---- Arrival: accumulate + cadence (identical in both arms) ----
                answer.append(arrivals[arrivalIndex].second)
                val nowMs = arrivalTime
                val decision = cadence.onChunk(nowMs)
                if (!firstChunkSeen) {
                    firstChunkSeen = true
                    val snapshot = snapshotFull()
                    if (arm == ArmConfig.CONTROL) publish(snapshot, nowMs)
                    firstChunkInsert(snapshot, nowMs)
                } else {
                    // One shared full snapshot per arrival when both halves need it
                    // (mirrors the production sharing modeled by the grid benchmark).
                    var sharedSnapshot: String? = null
                    fun snapshotOnce(): String =
                        sharedSnapshot ?: snapshotFull().also { sharedSnapshot = it }
                    if (decision.checkpoint) checkpointUpdate(snapshotOnce())
                    if (arm == ArmConfig.CONTROL && decision.publishUi) publish(snapshotOnce(), nowMs)
                }
                arrivalIndex++
            } else {
                // ---- Tick: typewriter UI publish only --------------------------
                val nowMs = tickTime
                val rendered = typewriter.onTick(answer)
                if (rendered != null) {
                    renderedSoFar = rendered
                    val started = SystemClock.elapsedRealtimeNanos()
                    val prefix = answer.substring(0, rendered)
                    snapshotNanos += SystemClock.elapsedRealtimeNanos() - started
                    uniqueSnapshots++
                    copiedChars += prefix.length
                    if (arrivalTime == Long.MAX_VALUE) drainTicks++
                    publish(prefix, nowMs)
                }
                tickIndex++
            }
        }
        if (arm == ArmConfig.TYPEWRITER) {
            check(renderedSoFar == answer.length) { "typewriter did not drain fully" }
        }

        // ---- Terminal: instant flush + finalize + reconcile ----------------
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

        val sortedDeltas = deltas.sorted()
        return RoundMetrics(
            publishes = publishes,
            deltasP50 = sortedDeltas.percentileIndex(50),
            deltasP95 = sortedDeltas.percentileIndex(95),
            drainTicks = drainTicks,
            checkpointUpdates = checkpointUpdates,
            persistenceOps = persistenceOps,
            uniqueSnapshots = uniqueSnapshots,
            copiedChars = copiedChars,
            snapshotMs = snapshotNanos / 1_000_000.0,
            roomMs = roomNanos / 1_000_000.0,
            reconcileMs = reconcileNanos / 1_000_000.0,
            totalMs = totalNanos / 1_000_000.0,
            finalContentMatches = reconciledContent == finalContent,
            deltas = deltas,
            steadyDeltas = steadyDeltas
        )
    }

    private fun List<Int>.percentileIndex(percentile: Int): Int {
        if (isEmpty()) return 0
        val index = ceil(percentile / 100.0 * size).toInt().coerceAtLeast(1) - 1
        return this[index]
    }

    private fun percentile(values: List<Double>, percentile: Int): Double {
        val sorted = values.sorted()
        val index = ceil(percentile / 100.0 * sorted.size).toInt().coerceAtLeast(1) - 1
        return sorted[index]
    }

    private fun cv(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        if (mean == 0.0) return 0.0
        val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
        return sqrt(variance) / mean
    }

    private fun fmt(value: Double): String = "%.3f".format(java.util.Locale.US, value)

    private enum class ArmConfig { CONTROL, TYPEWRITER }

    private enum class Mode {
        STEADY {
            override fun arrivals(): List<Pair<Long, String>> =
                (0 until STEADY_CHUNKS).map { i -> (i + 1L) * CHUNK_PERIOD_MS to CHUNK_TEXT }
        },
        BURST {
            override fun arrivals(): List<Pair<Long, String>> {
                val list = mutableListOf<Pair<Long, String>>()
                var clockMs = 0L
                repeat(BURST_REPEATS) {
                    BURST_SCHEDULE.forEach { (stallMs, burstChunks) ->
                        clockMs += stallMs
                        repeat(burstChunks) { i -> list.add((clockMs + i) to CHUNK_TEXT) }
                        clockMs += burstChunks
                    }
                }
                return list
            }
        };

        abstract fun arrivals(): List<Pair<Long, String>>
    }

    private data class RoundMetrics(
        val publishes: Int,
        val deltasP50: Int,
        val deltasP95: Int,
        val drainTicks: Int,
        val checkpointUpdates: Int,
        val persistenceOps: Int,
        val uniqueSnapshots: Int,
        val copiedChars: Long,
        val snapshotMs: Double,
        val roomMs: Double,
        val reconcileMs: Double,
        val totalMs: Double,
        val finalContentMatches: Boolean,
        val deltas: List<Int>,
        val steadyDeltas: List<Int>
    ) {
        fun sameDeterministicCountsAs(other: RoundMetrics): Boolean =
            publishes == other.publishes &&
                checkpointUpdates == other.checkpointUpdates &&
                persistenceOps == other.persistenceOps &&
                uniqueSnapshots == other.uniqueSnapshots &&
                copiedChars == other.copiedChars
    }

    private companion object {
        const val TAG = "TypewriterBench"
        const val USER_ID = "typewriter-user"
        const val SERVER_KEY = "typewriter-server"
        const val CONVERSATION_ID = "typewriter-conversation"
        const val HISTORY_ROWS = 500
        const val STEADY_CHUNKS = 1_000
        const val CHUNK_PERIOD_MS = 10L
        const val CHUNK_TEXT = "0123456789abcdef0123456789abcdef"
        const val WARMUP_ROUNDS = 5
        const val MEASURED_ROUNDS = 30
        const val BURST_REPEATS = 3
        const val MAX_DRAIN_TICKS = 2_000

        /** (stallMs, burstChunks): the stall-burst shape that motivates the change. */
        val BURST_SCHEDULE = listOf(
            1_200L to 12, 400L to 5, 2_000L to 20, 100L to 3,
            700L to 8, 1_500L to 15, 300L to 6, 900L to 10
        )
    }
}
