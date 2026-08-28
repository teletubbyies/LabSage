package com.labsage.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic JVM tests for the typewriter rate policy — no clock framework,
 * arrival and tick timelines are synthesized as virtual timestamps and merged
 * in order (tie → arrival before tick), same paradigm as [SseUpdateCadenceTest].
 */
class TypewriterCadenceTest {

    // ============================================================
    // Virtual-timeline harness
    // ============================================================

    private class TypewriterRun(
        val deltas: List<Int>,      // chars per publish
        val publishTimes: List<Long> // virtual ms of each publish
    )

    /** Merges sorted arrivals with a fixed-period tick stream; ticks drain the
     *  backlog for extraDrainMs after the last arrival so runs fully settle. */
    private fun runTypewriter(
        arrivals: List<Pair<Long, String>>,
        horizon: Int = TypewriterCadence.HORIZON,
        tickPeriodMs: Long = TypewriterCadence.TICK_MS,
        extraDrainMs: Long = 5_000L
    ): TypewriterRun {
        val typewriter = TypewriterCadence(horizon)
        val source = StringBuilder()
        val deltas = mutableListOf<Int>()
        val times = mutableListOf<Long>()
        val endTime = arrivals.maxOf { it.first } + extraDrainMs
        var arrivalIndex = 0
        var tickIndex = 0
        var rendered = 0
        while (true) {
            val arrivalTime = arrivals.getOrNull(arrivalIndex)?.first ?: Long.MAX_VALUE
            val tickTime = (tickIndex + 1L) * tickPeriodMs
            if (arrivalTime == Long.MAX_VALUE && tickTime > endTime) break
            if (arrivalTime <= tickTime) {
                source.append(arrivals[arrivalIndex].second)
                arrivalIndex++
            } else {
                val newLength = typewriter.onTick(source)
                if (newLength != null) {
                    deltas.add(newLength - rendered)
                    times.add(tickTime)
                    rendered = newLength
                }
                tickIndex++
            }
        }
        return TypewriterRun(deltas, times)
    }

    /** Control arm: the legacy 50ms window policy — publishUi dumps the whole
     *  accumulated snapshot, so a burst lands as one huge delta. */
    private fun runLegacyWindowPolicy(
        arrivals: List<Pair<Long, String>>,
        uiIntervalMs: Long = 50L
    ): List<Int> {
        val cadence = SseUpdateCadence(uiIntervalMs = uiIntervalMs, checkpointIntervalMs = 500L)
        cadence.reset(startedAtMs = 0)
        val source = StringBuilder()
        val deltas = mutableListOf<Int>()
        var published = 0
        for ((timeMs, text) in arrivals) {
            source.append(text)
            if (cadence.onChunk(nowMs = timeMs).publishUi) {
                deltas.add(source.length - published)
                published = source.length
            }
        }
        return deltas
    }

    /** Population coefficient of variation (stddev / mean). */
    private fun cv(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        if (mean == 0.0) return 0.0
        val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
        return kotlin.math.sqrt(variance) / mean
    }

    // ============================================================
    // Rate behavior
    // ============================================================

    @Test
    fun `slow drip publishes one char per tick and nothing when caught up`() {
        val source = "abcdefghijklmnopqrstuvwxyz"
        val arrivals = source.mapIndexed { i, c -> (i * 100L) to c.toString() }
        val run = runTypewriter(arrivals)

        assertEquals(source.length, run.deltas.size)
        assertTrue("every publish is exactly one char", run.deltas.all { it == 1 })
        // Concatenated deltas reproduce the source char-for-char (prefix sequence
        // equals the source prefixes), and no tick published without an arrival.
        assertEquals(source.length, run.deltas.sum())
    }

    @Test
    fun `burst of 300 drains with first step ten, non-increasing steps, bounded ticks`() {
        val typewriter = TypewriterCadence()
        val source = "x".repeat(300)
        val steps = mutableListOf<Int>()
        var rendered = 0
        while (true) {
            val newLength = typewriter.onTick(source) ?: break
            steps.add(newLength - rendered)
            rendered = newLength
        }

        assertEquals(300, rendered)
        assertEquals(10, steps.first()) // ceil(300/30)
        assertTrue("drain steps are non-increasing", steps.zipWithNext().all { (a, b) -> b <= a })
        assertEquals(300, steps.sum())
        // Closed-form bound: backlog shrinks by factor (1 - 1/30) per tick →
        // 30·ln(300) ≈ 171 ticks; 200 leaves slack for the ceil rounding.
        assertTrue("drained in ${steps.size} ticks, expected <= 200", steps.size <= 200)
    }

    @Test
    fun `steady documented load keeps chars-per-publish CV under 15 percent`() {
        val arrivals = (0 until CHUNK_COUNT).map { i ->
            (i + 1L) * CHUNK_PERIOD_MS to TEXT_32
        }
        val run = runTypewriter(arrivals)

        // Steady window only: skip the warm-up transient (equilibrium lag ≈ 1s
        // + margin) AND the post-arrival drain (the harness keeps ticking for
        // extraDrainMs after the last chunk, where steps decay toward 1).
        val lastArrivalMs = CHUNK_COUNT * CHUNK_PERIOD_MS
        val steadyDeltas = run.deltas.zip(run.publishTimes)
            .filter { (_, t) -> t > 3_000L && t <= lastArrivalMs }
            .map { (delta, _) -> delta.toDouble() }
        assertTrue("expected steady publishes, got ${steadyDeltas.size}", steadyDeltas.size >= 100)
        val steadyCv = cv(steadyDeltas)
        assertTrue("steady-state CV $steadyCv >= 0.15", steadyCv < 0.15)
    }

    @Test
    fun `bursty schedule typewriter CV beats legacy 50ms window policy`() {
        // NOTE: on perfectly uniform arrival the legacy policy also yields CV ≈ 0
        // (it publishes a constant ~160 chars every 50ms), so the new-beats-old
        // claim is only meaningful on the stall-burst schedule that motivates the
        // change ("停顿 → 一大块文字瞬间出现"). See SSE_TYPEWRITER_FEASIBILITY.md §7.1.
        val arrivals = burstyArrivals(repeats = 3)

        val typewriterDeltas = runTypewriter(arrivals).deltas.map { it.toDouble() }
        val legacyDeltas = runLegacyWindowPolicy(arrivals).map { it.toDouble() }

        assertTrue(typewriterDeltas.size > legacyDeltas.size) // typewriter spreads the same text thinner
        val typewriterCv = cv(typewriterDeltas)
        val legacyCv = cv(legacyDeltas)
        assertTrue("typewriter CV $typewriterCv should beat legacy CV $legacyCv", typewriterCv < legacyCv)
    }

    // ============================================================
    // Cursor safety
    // ============================================================

    @Test
    fun `surrogate pairs are never split by a step boundary`() {
        // Emoji-dense source with CJK: "😀🎉" (surrogate pairs) + "中文" (BMP).
        val emojiSentence = ("😀🎉🚀💡".repeat(8) + "中文测试文本").repeat(4)

        // Sweep: a small horizon forces many odd landing indexes; a fresh cursor
        // must never emit a prefix ending on an unpaired high surrogate.
        for (horizon in 2..5) {
            val typewriter = TypewriterCadence(horizon)
            val prefixes = mutableListOf<String>()
            while (true) {
                val newLength = typewriter.onTick(emojiSentence) ?: break
                prefixes.add(emojiSentence.substring(0, newLength))
            }
            assertTrue("expected multiple publishes at horizon $horizon", prefixes.size >= 8)
            prefixes.forEach { prefix ->
                assertFalse(
                    "prefix must not end on an unpaired high surrogate",
                    Character.isHighSurrogate(prefix.last())
                )
            }
            // Full drain: the final prefix is the whole source, so nothing was skipped.
            assertEquals(emojiSentence, prefixes.last())
        }
    }

    @Test
    fun `terminal flush publishes everything and subsequent ticks idle`() {
        val typewriter = TypewriterCadence()
        val source = "y".repeat(250)
        // Partial render: a few ticks, far from the end.
        repeat(3) { typewriter.onTick(source) }
        val flushed = typewriter.flushTo(source.length)

        assertEquals(source.length, flushed)
        assertNull(typewriter.onTick(source))
        // A further arrival resumes ticking from the flushed cursor.
        val grown = source + "zzz"
        assertEquals(source.length + 1, typewriter.onTick(grown)) // ceil(3/30) = 1
    }

    @Test
    fun `continue reset keeps displayed prefix and appends only`() {
        val existing = "已经显示的旧内容".repeat(10)
        val delta = "新增的续传内容".repeat(10)
        val typewriter = TypewriterCadence()
        typewriter.reset(existing.length) // prior partial is fully displayed

        val firstLength = typewriter.onTick(existing + delta)!!

        // Load-bearing: with the cursor correctly pinned, the first publish must
        // extend beyond the already-shown text (a reset-to-0 regression would
        // publish ~horizon chars — far less than the existing prefix — and fail).
        assertTrue("must append, not re-type", firstLength > existing.length)
        // And the prefix up to the first publish contains the prior partial unchanged.
        assertTrue((existing + delta).substring(0, firstLength).startsWith(existing))
    }

    @Test
    fun `boundary backlogs and invalid inputs`() {
        val typewriter = TypewriterCadence()
        assertEquals(1, typewriter.onTick("a")) // backlog 1 → step 1
        typewriter.reset()
        assertEquals(1, typewriter.onTick("b".repeat(30))) // backlog == horizon → step 1
        typewriter.reset()
        assertEquals(2, typewriter.onTick("c".repeat(31))) // backlog 31 → step 2

        var thrown = false
        try {
            typewriter.reset(renderedLength = -1)
        } catch (expected: IllegalArgumentException) {
            thrown = true
        }
        assertTrue(thrown)

        // Fail-safe: a source shorter than the cursor (cleared buffer) idles.
        typewriter.reset(10)
        assertNull(typewriter.onTick("short"))
    }

    // ============================================================
    // Fixed schedules
    // ============================================================

    private companion object {
        const val CHUNK_COUNT = 1_000
        const val CHUNK_PERIOD_MS = 10L
        const val TEXT_32 = "0123456789abcdef0123456789abcdef"

        /** (stallMs, burstChunks): varied stalls and burst sizes produce
         *  whole-burst dumps under the legacy window policy. */
        val BURST_SCHEDULE = listOf(
            1_200L to 12, 400L to 5, 2_000L to 20, 100L to 3,
            700L to 8, 1_500L to 15, 300L to 6, 900L to 10
        )

        /** Walks the schedule with a running clock: stall, then 1ms-spaced burst. */
        fun burstyArrivals(repeats: Int): List<Pair<Long, String>> {
            val arrivals = mutableListOf<Pair<Long, String>>()
            var clockMs = 0L
            repeat(repeats) {
                BURST_SCHEDULE.forEach { (stallMs, burstChunks) ->
                    clockMs += stallMs
                    repeat(burstChunks) { i -> arrivals.add((clockMs + i) to TEXT_32) }
                    clockMs += burstChunks
                }
            }
            return arrivals
        }
    }
}
