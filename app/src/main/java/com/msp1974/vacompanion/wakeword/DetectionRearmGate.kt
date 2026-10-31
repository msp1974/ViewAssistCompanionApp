package com.msp1974.vacompanion.wakeword

import android.os.SystemClock

/**
 * REARM-FILTER: keeps one utterance of a wake word from being detected more than once.
 *
 * The engines report a score for every frame while it is above a floor, so the frames after the one
 * that crossed the threshold - the rest of the same word - can cross it too. Once a detection has
 * fired, further detections of that wake word are ignored ([Outcome.RESIDUAL]) until the score has
 * come back down: either an event with a score under [REARM_FRACTION] of the threshold, or a gap of
 * [GAP_MS] with no event at all (the engines stop reporting below their floor). A new detection after
 * that is a real one ([Outcome.FRESH]).
 *
 * A score that never comes down would otherwise lock the wake word out for good, so the gate opens
 * again after [MAX_HOLD_MS] regardless.
 *
 * TO REMOVE: delete this file and `DetectionRearmGateTest.kt`, and the lines tagged REARM-FILTER in
 * `SatelliteWakeWorkHandler` (the gate and the `outcome == FRESH` test).
 */
class DetectionRearmGate(
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val gapMs: Long = GAP_MS,
    private val maxHoldMs: Long = MAX_HOLD_MS,
) {
    enum class Outcome {
        /** Not a detection: the score is under the threshold. */
        NOT_A_DETECTION,

        /** A new detection to act on. */
        FRESH,

        /** Over the threshold, but the tail of a detection that has already fired. */
        RESIDUAL,
    }

    private class State {
        var closed = false
        var closedAt = 0L
        var lastEventAt = 0L
        var suppressed = 0
    }

    private val states = HashMap<String, State>()

    /**
     * Takes one score event for [wakeWordId]: its [score], whether the engine says it [detected] the
     * wake word, and the [threshold] in force. Returns what to do with it.
     */
    @Synchronized
    fun onScore(wakeWordId: String, score: Float, detected: Boolean, threshold: Float): Outcome {
        val now = clock()
        val state = states.getOrPut(wakeWordId) { State() }

        if (state.closed) {
            val cameBackDown = score < threshold * REARM_FRACTION
            val pause = now - state.lastEventAt >= gapMs
            val stuck = now - state.closedAt >= maxHoldMs
            if (cameBackDown || pause || stuck) state.closed = false
        }
        state.lastEventAt = now

        if (!detected) return Outcome.NOT_A_DETECTION
        if (state.closed) {
            state.suppressed++
            return Outcome.RESIDUAL
        }
        state.closed = true
        state.closedAt = now
        return Outcome.FRESH
    }

    /** How many residual detections were ignored since [wakeWordId]'s last fresh one; resets it. */
    @Synchronized
    fun takeSuppressed(wakeWordId: String): Int {
        val state = states[wakeWordId] ?: return 0
        return state.suppressed.also { state.suppressed = 0 }
    }

    /** How many residual detections have been ignored since [wakeWordId]'s last fresh one. */
    @Synchronized
    fun suppressed(wakeWordId: String): Int = states[wakeWordId]?.suppressed ?: 0

    companion object {
        /** A score under this fraction of the threshold means the word has ended. */
        const val REARM_FRACTION = 0.5f

        /** No score event for this long means the score has fallen below the engine's floor. */
        const val GAP_MS = 300L

        /** Failsafe: the longest the gate stays shut on a score that never comes down. */
        const val MAX_HOLD_MS = 10_000L
    }
}
