package com.msp1974.vacompanion.audio

import kotlin.math.log10

/**
 * How loud the microphone has been lately, measured on the raw signal before any processing.
 * That matters: WebRTC's gain control brings whatever it is given up to speech loudness, so a
 * whisper looks like normal speech after it. Only the raw level tells them apart.
 *
 * Levels are the RMS of each frame in dBFS (0 is full scale, so they are negative).
 *
 * [update] is called from the microphone's read thread and the rest from elsewhere.
 */
class MicLevelMonitor {

    private class Frame(val durationMs: Float, val levelDb: Float)

    private val frames = ArrayDeque<Frame>()
    private var framesMs = 0f

    @Synchronized
    fun update(frame: ShortArray, sampleRate: Int = VACAAudioFormat.SAMPLE_RATE_HZ) {
        if (frame.isEmpty()) return
        var sum = 0.0
        for (s in frame) {
            val v = s / 32768.0
            sum += v * v
        }
        val levelDb = (10 * log10(sum / frame.size + 1e-12)).toFloat().coerceAtLeast(FLOOR_DBFS)
        val durationMs = frame.size * 1000f / sampleRate

        frames.addLast(Frame(durationMs, levelDb))
        framesMs += durationMs
        while (framesMs > HISTORY_MS && frames.size > 1) framesMs -= frames.removeFirst().durationMs
    }

    /**
     * The loudest frame, in dBFS, over the last [windowMs] of audio. Null if nothing has been
     * heard yet, so callers can tell "unknown" from "very quiet".
     */
    @Synchronized
    fun peakDbfs(windowMs: Long): Float? {
        if (frames.isEmpty()) return null
        var peak = Float.NEGATIVE_INFINITY
        var covered = 0f
        for (i in frames.indices.reversed()) {
            val f = frames[i]
            peak = maxOf(peak, f.levelDb)
            covered += f.durationMs
            if (covered >= windowMs) break
        }
        return peak
    }

    @Synchronized
    fun reset() {
        frames.clear()
        framesMs = 0f
    }

    companion object {
        /** The `wake_word_min_level` value that switches the minimum off. */
        const val MIN_LEVEL_OFF_DBFS = -80

        /** Digital silence reads as this rather than minus infinity. */
        private const val FLOOR_DBFS = -100f

        private const val HISTORY_MS = 5_000f

        /**
         * Whether a sound that peaked at [levelDbfs] is too quiet for a minimum of [minimumDbfs].
         * Never true when the minimum is off, or when the level isn't known - it's better to let a
         * wake word through than to block every one because the level couldn't be read.
         */
        fun isBelowMinimum(minimumDbfs: Int, levelDbfs: Float?): Boolean {
            if (minimumDbfs <= MIN_LEVEL_OFF_DBFS) return false
            if (levelDbfs == null) return false
            return levelDbfs < minimumDbfs
        }
    }
}
