package com.labsage.app.ui.chat

/**
 * Typewriter rate policy for streaming UI rendering.
 *
 * The SSE collector appends every chunk to a StringBuilder immediately (network
 * delivery is never delayed — Room checkpoints keep persisting the full text).
 * This class owns the render cursor into that buffer: a ticker loop calls
 * [onTick] every [TICK_MS] and advances the cursor by `ceil(backlog / horizon)`.
 * That proportional step gives burst catch-up (a large backlog drains fast,
 * then settles exponentially) and a near-constant visual rate in steady state —
 * display lag self-balances at ≈ horizon × TICK_MS regardless of arrival rate.
 * No per-tick cap by design: capping would make lag grow without bound for
 * fast streams and enlarge the terminal jump instead (doc §5).
 *
 * Time-free by construction: the caller owns the tick clock, same paradigm as
 * [SseUpdateCadence] (which remains responsible for the Room checkpoint half).
 *
 * Cursor safety: a step never splits a UTF-16 surrogate pair. Multi-codepoint
 * graphemes (flags, ZWJ sequences) may still split across one 33ms tick —
 * accepted per doc §6.3.
 *
 * Invariants: the cursor never decreases except via [reset]; returned lengths
 * are always ≤ source length; consecutive returns are strictly increasing
 * while backlog > 0.
 */
internal class TypewriterCadence(private val horizon: Int = HORIZON) {

    init {
        require(horizon >= 1) { "horizon must be >= 1: $horizon" }
    }

    private var cursor: Int = 0

    /** How many chars of the source are currently displayed. */
    val renderedLength: Int get() = cursor

    /**
     * Repositions the cursor. Pass the currently displayed length when resuming
     * a partially shown stream (continue-generation / crash recovery) so the
     * already-shown text is never re-typed.
     */
    fun reset(renderedLength: Int = 0) {
        require(renderedLength >= 0) { "renderedLength must be >= 0: $renderedLength" }
        cursor = renderedLength
    }

    /**
     * One tick. Advances the cursor toward the end of [source] by
     * `ceil(backlog / horizon)`; if the landing index falls strictly inside the
     * buffer on a low surrogate it advances one more char, so the cursor never
     * splits a surrogate pair mid-buffer. (When the buffer's last char is a high
     * surrogate whose low half is still in flight from the network, one tick
     * shows it unpaired — inherent to prefix rendering, accepted per doc §6.3.)
     *
     * @return the new prefix length to publish (caller publishes
     *         `source.substring(0, result)`), or null when there is no backlog.
     */
    fun onTick(source: CharSequence): Int? {
        val backlog = source.length - cursor
        if (backlog <= 0) return null
        var target = cursor + (backlog + horizon - 1) / horizon
        if (target < source.length && Character.isLowSurrogate(source[target])) target += 1
        cursor = target
        return target
    }

    /**
     * One tick of the terminal accelerated drain (SSE Done received; Room already
     * holds the full text). The remaining backlog is consumed uniformly within
     * [ticksRemaining] ticks: step = `ceil(backlog / ticksRemaining)` — the finish
     * "prints faster" by ticking faster and shrinking the remaining window,
     * NEVER by dumping: each call still pops a contiguous segment, the same
     * surrogate guard as [onTick] applies.
     *
     * @param ticksRemaining ticks left in the drain window, ≥ 1.
     * @return new prefix length, or null when there is no backlog.
     */
    fun onDrainTick(source: CharSequence, ticksRemaining: Int): Int? {
        require(ticksRemaining >= 1) { "ticksRemaining must be >= 1: $ticksRemaining" }
        val backlog = source.length - cursor
        if (backlog <= 0) return null
        var target = cursor + (backlog + ticksRemaining - 1) / ticksRemaining
        if (target < source.length && Character.isLowSurrogate(source[target])) target += 1
        cursor = target
        return target
    }

    /**
     * Terminal flush: jumps the cursor to [totalLength] (never backward).
     * Used by tests/benchmarks to model an instant terminal publish; production
     * terminal paths publish the full string directly instead.
     */
    fun flushTo(totalLength: Int): Int {
        cursor = totalLength.coerceAtLeast(cursor)
        return cursor
    }

    companion object {
        /** Catch-up horizon: equilibrium display lag ≈ HORIZON × TICK_MS ≈ 1s. */
        const val HORIZON = 30

        /** Tick period for the render loop (≈30fps; 50ms is the documented fallback knob). */
        const val TICK_MS = 33L

        /** Terminal drain window after Done: remaining backlog pops uniformly within this long. */
        const val TERMINAL_DRAIN_MS = 1000L

        /** Drain-phase tick period — faster than [TICK_MS], so the finish visibly accelerates. */
        const val DRAIN_TICK_MS = 16L
    }
}
