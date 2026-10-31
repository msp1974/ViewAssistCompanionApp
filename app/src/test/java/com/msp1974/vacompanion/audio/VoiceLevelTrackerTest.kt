package com.msp1974.vacompanion.audio

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow
import kotlin.math.sqrt

class VoiceLevelTrackerTest {

    private val sampleRate = 16000
    private val frameSamples = 512
    private val ambient = -70f

    private var seed = 7L

    private fun gaussian(): Float {
        var sum = 0f
        repeat(4) {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            sum += ((seed ushr 33).toInt() / 2147483648f) - 0.5f
        }
        return sum / sqrt(4f / 12f)
    }

    /** Feeds [seconds] of noise at [levelDb] RMS dBFS to the tracker, a frame at a time. */
    private fun VoiceLevelTracker.feed(seconds: Float, levelDb: Float) {
        val amplitude = 10f.pow(levelDb / 20f)
        var remaining = (seconds * sampleRate).toInt()
        while (remaining > 0) {
            val n = minOf(frameSamples, remaining)
            update(ShortArray(n) { (gaussian() * amplitude * 32768f).toInt().coerceIn(-32768, 32767).toShort() }, sampleRate)
            remaining -= n
        }
    }

    @Test
    fun noReadingUntilTheBackgroundIsKnown() {
        val tracker = VoiceLevelTracker()
        tracker.feed(1f, ambient)
        assertNull(tracker.peakAboveFloorDb(2000))
        tracker.feed(2f, ambient)
        assertNotNull(tracker.peakAboveFloorDb(2000))
    }

    @Test
    fun steadyBackgroundReadsNearZero() {
        val tracker = VoiceLevelTracker()
        tracker.feed(6f, ambient)
        val above = tracker.peakAboveFloorDb(2000)!!
        assertTrue("background read $above dB above itself", above in 0f..4f)
    }

    @Test
    fun speechReadsWellAboveTheBackground() {
        val tracker = VoiceLevelTracker()
        tracker.feed(5f, ambient)
        tracker.feed(1.5f, ambient + 25f)
        tracker.feed(0.5f, ambient)
        val above = tracker.peakAboveFloorDb(2000)!!
        assertTrue("speech read $above dB", above >= 20f)
    }

    @Test
    fun whisperReadsOnlyALittleAboveTheBackground() {
        val tracker = VoiceLevelTracker()
        tracker.feed(5f, ambient)
        tracker.feed(1.5f, ambient + 8f)
        tracker.feed(0.5f, ambient)
        val above = tracker.peakAboveFloorDb(2000)!!
        assertTrue("whisper read $above dB", above in 5f..12f)
    }

    @Test
    fun aSoundOlderThanTheWindowIsNotCounted() {
        val tracker = VoiceLevelTracker()
        tracker.feed(5f, ambient)
        tracker.feed(1f, ambient + 25f)
        tracker.feed(3f, ambient)
        val above = tracker.peakAboveFloorDb(2000)!!
        assertTrue("old speech still read $above dB", above <= 4f)
    }

    @Test
    fun aBriefSpokenCommandDoesNotRaiseTheFloorMuch() {
        val tracker = VoiceLevelTracker()
        tracker.feed(5f, ambient)
        tracker.feed(10f, ambient + 25f)
        tracker.feed(5f, ambient + 25f)
        // 15 s of speech in a row, then the same level again: the floor has only crept up a bit.
        val above = tracker.peakAboveFloorDb(2000)!!
        assertTrue("speech after a long phrase read $above dB", above >= 12f)
    }

    @Test
    fun aRoomThatGetsNoisierBecomesTheNewBackground() {
        val tracker = VoiceLevelTracker()
        tracker.feed(5f, ambient)
        tracker.feed(150f, ambient + 15f)
        val above = tracker.peakAboveFloorDb(2000)!!
        assertTrue("new background read $above dB", above <= 5f)
    }

    @Test
    fun resetForgetsTheBackground() {
        val tracker = VoiceLevelTracker()
        tracker.feed(6f, ambient)
        tracker.reset()
        assertNull(tracker.peakAboveFloorDb(2000))
    }
}
