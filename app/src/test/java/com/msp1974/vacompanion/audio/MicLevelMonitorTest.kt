package com.msp1974.vacompanion.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow
import kotlin.math.sqrt

class MicLevelMonitorTest {

    private val sampleRate = 16000
    private val frameSamples = 1280 // 80 ms, as the microphone reads

    private var seed = 11L

    private fun gaussian(): Float {
        var sum = 0f
        repeat(4) {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            sum += ((seed ushr 33).toInt() / 2147483648f) - 0.5f
        }
        return sum / sqrt(4f / 12f)
    }

    /** Feeds [seconds] of noise at [levelDbfs] RMS, a microphone frame at a time. */
    private fun MicLevelMonitor.feed(seconds: Float, levelDbfs: Float) {
        val amplitude = 10f.pow(levelDbfs / 20f)
        var remaining = (seconds * sampleRate).toInt()
        while (remaining > 0) {
            val n = minOf(frameSamples, remaining)
            update(ShortArray(n) { (gaussian() * amplitude * 32768f).toInt().coerceIn(-32768, 32767).toShort() }, sampleRate)
            remaining -= n
        }
    }

    // ---- measuring the level

    @Test
    fun unknownUntilAnyAudioHasBeenHeard() {
        assertNull(MicLevelMonitor().peakDbfs(2000))
    }

    @Test
    fun readsTheRmsLevelOfTheAudioInDbfs() {
        val monitor = MicLevelMonitor()
        monitor.feed(2f, -40f)
        val level = monitor.peakDbfs(2000)!!
        assertEquals(-40f, level, 1.5f)
    }

    @Test
    fun aFullScaleSquareWaveReadsZero() {
        val monitor = MicLevelMonitor()
        monitor.update(ShortArray(frameSamples) { if (it % 2 == 0) 32767 else -32767 }, sampleRate)
        assertEquals(0f, monitor.peakDbfs(2000)!!, 0.1f)
    }

    @Test
    fun silenceReadsAsAFloorNotMinusInfinity() {
        val monitor = MicLevelMonitor()
        monitor.update(ShortArray(frameSamples), sampleRate)
        assertEquals(-100f, monitor.peakDbfs(2000)!!, 0.01f)
    }

    @Test
    fun reportsThePeakOverTheWindowNotTheAverage() {
        val monitor = MicLevelMonitor()
        monitor.feed(1f, -60f)
        monitor.feed(0.5f, -30f) // a short loud word
        monitor.feed(0.5f, -60f)
        val level = monitor.peakDbfs(2000)!!
        assertTrue("peak read $level dBFS", level > -35f)
    }

    @Test
    fun aSoundOlderThanTheWindowIsNotCounted() {
        val monitor = MicLevelMonitor()
        monitor.feed(0.5f, -30f)
        monitor.feed(3f, -60f)
        val level = monitor.peakDbfs(2000)!!
        assertTrue("old loud sound still read $level dBFS", level < -55f)
    }

    @Test
    fun resetForgetsEverything() {
        val monitor = MicLevelMonitor()
        monitor.feed(1f, -30f)
        monitor.reset()
        assertNull(monitor.peakDbfs(2000))
    }

    @Test
    fun keepsOnlyAFewSecondsOfHistory() {
        val monitor = MicLevelMonitor()
        monitor.feed(1f, -30f)
        monitor.feed(10f, -60f)
        // The loud second is long gone even from a window as wide as the history allows.
        val level = monitor.peakDbfs(60_000)!!
        assertTrue("read $level dBFS", level < -55f)
    }
}
