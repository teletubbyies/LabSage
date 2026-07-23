package com.labsage.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SseUpdateCadenceTest {

    @Test
    fun `one thousand fast chunks use independent ui and checkpoint rates`() {
        val cadence = SseUpdateCadence()
        cadence.reset(startedAtMs = 0)

        var uiPublishes = 0
        var checkpoints = 0
        repeat(CHUNK_COUNT) { index ->
            val nowMs = (index + 1L) * CHUNK_PERIOD_MS
            val decision = cadence.onChunk(nowMs = nowMs)
            if (decision.publishUi) uiPublishes++
            if (index == 0) {
                cadence.markCheckpoint(nowMs)
            } else if (decision.checkpoint) {
                checkpoints++
            }
        }

        val legacyIntermediateWrites = CHUNK_COUNT / LEGACY_CHUNKS_PER_WRITE
        val legacyTotalWrites = 1 + legacyIntermediateWrites + 1
        val optimizedTotalWrites = 1 + checkpoints + 1
        val reduction = 1.0 - optimizedTotalWrites.toDouble() / legacyTotalWrites

        assertEquals(200, uiPublishes)
        assertEquals(19, checkpoints)
        assertEquals(202, legacyTotalWrites)
        assertEquals(21, optimizedTotalWrites)
        assertTrue("expected at least 85% fewer writes", reduction >= 0.85)
    }

    @Test
    fun `slow chunks are published immediately without unnecessary checkpoints`() {
        val cadence = SseUpdateCadence()
        cadence.reset(startedAtMs = 0)

        val decisions = (1L..5L).map { cadence.onChunk(nowMs = it * 200L) }

        assertTrue(decisions.all { it.publishUi })
        assertEquals(1, decisions.count { it.checkpoint })
    }

    @Test
    fun `first persisted row resets checkpoint age`() {
        val cadence = SseUpdateCadence()
        cadence.reset(startedAtMs = 0)

        val lateFirstChunk = cadence.onChunk(nowMs = 2_000)
        assertTrue(lateFirstChunk.checkpoint)
        cadence.markCheckpoint(nowMs = 2_000)

        assertEquals(false, cadence.onChunk(nowMs = 2_499).checkpoint)
        assertEquals(true, cadence.onChunk(nowMs = 2_500).checkpoint)
    }

    private companion object {
        const val CHUNK_COUNT = 1_000
        const val CHUNK_PERIOD_MS = 10L
        const val LEGACY_CHUNKS_PER_WRITE = 5
    }
}
