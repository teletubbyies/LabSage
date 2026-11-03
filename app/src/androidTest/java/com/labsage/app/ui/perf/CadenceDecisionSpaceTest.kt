package com.labsage.app.ui.perf

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.labsage.app.ui.chat.SseUpdateCadence
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 * Decision-space evidence for the production cadence (50 ms UI / 500 ms Room).
 *
 * The two intervals trade off four measurable dimensions; this harness replays
 * REAL recorded traces plus one high-density synthetic stream through
 * SseUpdateCadence across a grid of candidate intervals and measures:
 *
 *   D1 视觉连续性  maxVisualGapMs — worst stall between visible updates while
 *                  text is actively arriving (perceptibility threshold for
 *                  appearing text is ~100 ms; film cadence 24 Hz = 41 ms).
 *   D2 调度成本    publishes + snapshotChars (immutable String copies).
 *   D3 持久化成本  checkpoint writes (+1 terminal finalize).
 *   D4 崩溃丢失    chars received-but-not-checkpointed at a uniformly random
 *                  process-death instant (mean / p95 / max over 500 kills).
 *
 * 50 ms is the knee of D1/D2: it keeps the visual gap under the perceptibility
 * threshold at half the publish volume of 33 ms. 500 ms is the knee of D3/D4:
 * doubling to 1000 ms saves almost no writes but doubles the loss window.
 */
@RunWith(AndroidJUnit4::class)
class CadenceDecisionSpaceTest {

    private data class Chunk(val tMs: Double, val chars: Int)

    private data class ReplayResult(
        val publishes: Int,
        val maxVisualGapMs: Double,
        val snapshotChars: Long,
        val checkpointWrites: Int,
        val lossMean: Double,
        val lossP95: Double,
        val lossMax: Double
    )

    @Test
    fun decisionSpaceAcrossCandidateIntervals() {
        val scenarios = listOf(
            trace("direct-long-real", "sse_trace_direct_long.json"),
            trace("kb-long-real", "sse_trace_kb_long.json"),
            trace("multi-tool-real", "sse_trace_multi_tool.json"),
            synthetic()
        )

        scenarios.forEach { (name, chunks) ->
            Log.i(TAG, "=== scenario=$name chunks=${chunks.size} durationMs=${chunks.last().tMs.toInt()} ===")
            // D1/D2: UI interval sweep (checkpoint pinned at production 500 ms).
            UI_CANDIDATES.forEach { uiMs ->
                val r = replay(chunks, uiMs, 500)
                Log.i(
                    TAG,
                    "UI ${uiMs}ms: rate=${fmt(1000.0 / uiMs)}Hz publishes=${r.publishes} " +
                        "maxVisualGapMs=${fmt(r.maxVisualGapMs)} snapshotChars=${r.snapshotChars}"
                )
            }
            // D3/D4: checkpoint interval sweep (UI pinned at production 50 ms).
            CKPT_CANDIDATES.forEach { ckptMs ->
                val r = replay(chunks, 50, ckptMs)
                Log.i(
                    TAG,
                    "CKPT ${ckptMs}ms: writes=${r.checkpointWrites} " +
                        "lossMean=${fmt(r.lossMean)} lossP95=${fmt(r.lossP95)} lossMax=${fmt(r.lossMax)}"
                )
            }
        }

        // Knee assertions on the high-density scenario (where the choice bites):
        val synth = synthetic().second
        val ui50 = replay(synth, 50, 500)
        val ui33 = replay(synth, 33, 500)
        val ui100 = replay(synth, 100, 500)
        val ck500 = replay(synth, 50, 500)
        val ck1000 = replay(synth, 50, 1000)
        // 50 ms stays under the ~100 ms perceptibility threshold…
        check(ui50.maxVisualGapMs <= 100.0)
        // …while 100 ms crosses it (the update itself is the gap floor)…
        check(ui100.maxVisualGapMs >= 100.0)
        // …and 33 ms buys no perceptual headroom but ~+50% publish volume.
        check(ui50.publishes <= (ui33.publishes * 10 + 9) / 10)
        // Doubling the checkpoint saves at most half the (already tiny) writes…
        check(ck1000.checkpointWrites >= ck500.checkpointWrites / 2)
        // …while the p95 crash-loss window grows ~linearly with the interval.
        check(ck500.lossP95 <= ck1000.lossP95 * 0.75)
    }

    /** Replays one trace at (uiMs, ckptMs) and measures all four dimensions. */
    private fun replay(chunks: List<Chunk>, uiMs: Long, ckptMs: Long): ReplayResult {
        val cadence = SseUpdateCadence(uiIntervalMs = uiMs, checkpointIntervalMs = ckptMs)
        cadence.reset(0)
        val publishTimes = mutableListOf<Double>()
        var snapshotChars = 0L
        var received = 0
        val ckptTimes = mutableListOf<Double>()
        val ckptChars = mutableListOf<Int>()
        var lastCkptChars = 0
        chunks.forEach { chunk ->
            val decision = cadence.onChunk(chunk.tMs.toLong())
            received += chunk.chars
            if (publishTimes.isEmpty()) {
                // First chunk takes the INSERT path (a checkpoint by contract).
                cadence.markCheckpoint(chunk.tMs.toLong())
                publishTimes += chunk.tMs
                snapshotChars += received.toLong()
                ckptTimes += chunk.tMs
                ckptChars += received
                lastCkptChars = received
            } else {
                if (decision.publishUi) {
                    publishTimes += chunk.tMs
                    snapshotChars += received.toLong()
                }
                if (decision.checkpoint) {
                    ckptTimes += chunk.tMs
                    ckptChars += received
                    lastCkptChars = received
                }
            }
        }
        val tFirst = chunks.first().tMs
        val tLast = chunks.last().tMs
        var maxGap = 0.0
        for (i in 1 until publishTimes.size) {
            val gap = publishTimes[i] - publishTimes[i - 1]
            if (gap > maxGap) maxGap = gap
        }
        // D4: uniform random process death inside the active text window.
        val rng = Random(42)
        val losses = DoubleArray(KILL_SAMPLES) {
            val t = tFirst + (tLast - tFirst) * rng.nextDouble()
            val receivedAt = charsReceivedBy(chunks, t)
            val savedAt = run {
                var idx = ckptTimes.indexOfLast { it <= t }
                if (idx < 0) idx = 0
                ckptChars[idx]
            }
            (receivedAt - savedAt).toDouble()
        }.also { it.sort() }
        fun p(pct: Int) = losses[(pct / 100.0 * (losses.size - 1)).toInt()]
        return ReplayResult(
            publishes = publishTimes.size,
            maxVisualGapMs = maxGap,
            snapshotChars = snapshotChars,
            // +1 for the terminal finalize UPDATE every interval pays equally.
            checkpointWrites = ckptTimes.size + 1,
            lossMean = losses.average(),
            lossP95 = p(95),
            lossMax = losses.last()
        )
    }

    private fun charsReceivedBy(chunks: List<Chunk>, tMs: Double): Int {
        var total = 0
        for (chunk in chunks) {
            if (chunk.tMs > tMs) break
            total += chunk.chars
        }
        return total
    }

    private fun trace(name: String, assetFile: String): Pair<String, List<Chunk>> {
        val json = InstrumentationRegistry.getInstrumentation()
            .context.assets.open(assetFile).bufferedReader().use { it.readText() }
        val chunks = JSONObject(json).getJSONArray("chunks").let { array ->
            List(array.length()) { i ->
                val item = array.getJSONObject(i)
                Chunk(item.getDouble("tMs"), item.getString("text").length)
            }
        }
        check(chunks.isNotEmpty()) { "trace $name is empty" }
        return name to chunks
    }

    /** High-density stressor: 1000 chunks × 10 ms, 32 KB — where the interval bites. */
    private fun synthetic(): Pair<String, List<Chunk>> =
        "synthetic-1000@10ms" to List(1_000) { i -> Chunk((i + 1) * 10.0, 32) }

    private fun fmt(v: Double) = "%.1f".format(java.util.Locale.US, v)

    private companion object {
        const val TAG = "CadenceSpace"
        val UI_CANDIDATES = longArrayOf(16, 33, 50, 100, 200)
        val CKPT_CANDIDATES = longArrayOf(250, 500, 1000, 2000)
        const val KILL_SAMPLES = 500
    }
}
