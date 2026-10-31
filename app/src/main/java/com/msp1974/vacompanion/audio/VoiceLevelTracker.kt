package com.msp1974.vacompanion.audio

import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * How far above the room's background noise the microphone's recent sound has been, measured on
 * the raw microphone signal before any gain control. That makes it the real loudness of what was
 * said - a whisper that gain control then boosts to speech level still reads as a whisper here.
 *
 * Each frame's level is compared with a running estimate of the background floor, which drops to
 * a quieter reading at once and rises only slowly, so a few seconds of speech or whispering
 * don't pull it up but a room that really gets noisier does. Nothing here is a fixed dBFS value,
 * since raw level for the same loudness varies a lot between microphones.
 *
 * [update] is called from the microphone's read thread and [peakAboveFloorDb] from elsewhere.
 */
class VoiceLevelTracker(
    private val floorRiseMs: Float = FLOOR_RISE_MS,
    private val baselineMs: Long = BASELINE_MS,
) {
    private class Frame(val durationMs: Float, val levelDb: Float)

    private val frames = ArrayDeque<Frame>()
    private var framesMs = 0f
    private var observedMs = 0f
    private var floorDb = Float.NaN

    @Synchronized
    fun update(frame: ShortArray, sampleRate: Int = VACAAudioFormat.SAMPLE_RATE_HZ) {
        if (frame.isEmpty()) return
        var sum = 0.0
        for (s in frame) {
            val v = s / 32768.0
            sum += v * v
        }
        val levelDb = (10 * log10(sum / frame.size + 1e-12)).toFloat().coerceAtLeast(MIN_LEVEL_DB)
        val durationMs = frame.size * 1000f / sampleRate

        floorDb = when {
            floorDb.isNaN() || levelDb < floorDb -> levelDb
            else -> floorDb + (levelDb - floorDb) * (1f - exp(-durationMs / floorRiseMs))
        }

        frames.addLast(Frame(durationMs, levelDb))
        framesMs += durationMs
        observedMs += durationMs
        while (framesMs > HISTORY_MS && frames.size > 1) framesMs -= frames.removeFirst().durationMs
    }

    /**
     * The loudest the sound has been, in dB above the background floor, over the last [windowMs]
     * of audio. Null until enough audio has been heard to know what the background is, so callers
     * shouldn't act on it right after the microphone opens.
     */
    @Synchronized
    fun peakAboveFloorDb(windowMs: Long): Float? {
        if (floorDb.isNaN() || observedMs < baselineMs) return null
        var peak = Float.NEGATIVE_INFINITY
        var covered = 0f
        for (i in frames.indices.reversed()) {
            val f = frames[i]
            peak = maxOf(peak, f.levelDb)
            covered += f.durationMs
            if (covered >= windowMs) break
        }
        return peak - floorDb
    }

    @Synchronized
    fun reset() {
        frames.clear()
        framesMs = 0f
        observedMs = 0f
        floorDb = Float.NaN
    }

    companion object {
        // Long enough that a spoken command doesn't drag the floor up towards itself.
        const val FLOOR_RISE_MS = 30_000f

        // Audio needed before the floor means anything.
        const val BASELINE_MS = 2_000L

        private const val HISTORY_MS = 5_000f
        private const val MIN_LEVEL_DB = -100f
    }
}
