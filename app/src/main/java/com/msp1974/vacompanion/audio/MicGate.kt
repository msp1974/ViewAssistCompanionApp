package com.msp1974.vacompanion.audio

import kotlin.math.log10

/**
 * Keeps the microphone silent until it hears something louder than a set level, so that very quiet
 * sound - a whisper, background murmur - never reaches the wake word engine or the speech pipeline.
 *
 * It looks at the raw microphone signal, before WebRTC's gain control would level it up. Once a frame
 * gets over the level the gate stays open for [HOLD_MS] after the last one that did, so the pauses
 * and quiet endings of a normal sentence aren't cut off.
 */
class MicGate(private val clock: () -> Long = System::currentTimeMillis) {

    @Volatile
    private var openUntilMs = 0L

    /** Whether [frame] (raw samples) should be let through with a gate set at [thresholdDbfs]. */
    fun isOpen(frame: ShortArray, thresholdDbfs: Int): Boolean {
        if (thresholdDbfs <= OFF_DBFS) return true
        val now = clock()
        if (frameDbfs(frame) >= thresholdDbfs) openUntilMs = now + HOLD_MS
        return now < openUntilMs
    }

    companion object {
        /** The lowest level that can be set; at this the gate is off and everything is let through. */
        const val OFF_DBFS = -70

        /** The loudest level that can be set (0 dBFS is full scale). */
        const val MAX_DBFS = -20

        /** How long the gate stays open after the sound last got over the level. */
        const val HOLD_MS = 1000L

        /** RMS level of [frame] in dBFS. */
        fun frameDbfs(frame: ShortArray): Float {
            if (frame.isEmpty()) return -100f
            var sum = 0.0
            for (s in frame) {
                val v = s / 32768.0
                sum += v * v
            }
            return (10 * log10(sum / frame.size + 1e-12)).toFloat()
        }
    }
}
