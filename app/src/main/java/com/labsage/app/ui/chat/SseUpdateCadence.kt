package com.labsage.app.ui.chat

/**
 * Separates the cadence of in-memory UI snapshots from durable Room checkpoints.
 *
 * The caller supplies a monotonic timestamp so the policy is deterministic in tests
 * and independent of wall-clock changes.
 */
internal class SseUpdateCadence(
    private val uiIntervalMs: Long = UI_INTERVAL_MS,
    private val checkpointIntervalMs: Long = CHECKPOINT_INTERVAL_MS
) {
    private var lastUiPublishMs = Long.MIN_VALUE
    private var lastCheckpointMs = Long.MIN_VALUE

    init {
        require(uiIntervalMs > 0)
        require(checkpointIntervalMs >= uiIntervalMs)
    }

    fun reset(startedAtMs: Long) {
        lastUiPublishMs = startedAtMs - uiIntervalMs
        lastCheckpointMs = startedAtMs
    }

    fun onChunk(nowMs: Long): SseUpdateDecision {
        val publishUi = nowMs - lastUiPublishMs >= uiIntervalMs
        val checkpoint = nowMs - lastCheckpointMs >= checkpointIntervalMs
        if (publishUi) lastUiPublishMs = nowMs
        if (checkpoint) lastCheckpointMs = nowMs
        return SseUpdateDecision(publishUi = publishUi, checkpoint = checkpoint)
    }

    /** The first persisted assistant row establishes a fresh recovery checkpoint. */
    fun markCheckpoint(nowMs: Long) {
        lastCheckpointMs = nowMs
    }

    companion object {
        const val UI_INTERVAL_MS = 50L
        const val CHECKPOINT_INTERVAL_MS = 500L
    }
}

internal data class SseUpdateDecision(
    val publishUi: Boolean,
    val checkpoint: Boolean
)
