package com.msp1974.vacompanion.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class WebRtcMicProcessorTest {

    private val frame = WebRtcMicProcessor.FRAME_SAMPLES

    /** Doubles each sample of the frame it is given, and records where it was called. */
    private class Doubler {
        val offsets = mutableListOf<Int>()
        fun process(samples: ShortArray, offset: Int): Int {
            offsets.add(offset)
            for (i in offset until offset + 160) samples[i] = (samples[i] * 2).toShort()
            return 0
        }
    }

    @Test
    fun processesEveryWholeFrameAndReturnsTheSameLength() {
        val doubler = Doubler()
        val processor = WebRtcMicProcessor(doubler::process, {})
        val input = ShortArray(frame * 8) { 1 }
        val output = processor.process(input)
        assertEquals(input.size, output.size)
        assertEquals((0 until 8).map { it * frame }, doubler.offsets)
        assertEquals(2, output[0].toInt())
        assertEquals(2, output.last().toInt())
    }

    @Test
    fun doesNotChangeTheCallersArray() {
        val processor = WebRtcMicProcessor(Doubler()::process, {})
        val input = ShortArray(frame * 2) { 5 }
        processor.process(input)
        assertArrayEquals(ShortArray(frame * 2) { 5 }, input)
    }

    @Test
    fun passesThroughAPartialFrameAtTheEnd() {
        val processor = WebRtcMicProcessor(Doubler()::process, {})
        val input = ShortArray(frame + 40) { 1 }
        val output = processor.process(input)
        assertEquals(2, output[frame - 1].toInt())
        assertEquals(1, output[frame].toInt())
        assertEquals(1, output.last().toInt())
    }

    @Test
    fun anEmptyInputGivesAnEmptyOutput() {
        val processor = WebRtcMicProcessor(Doubler()::process, {})
        assertEquals(0, processor.process(ShortArray(0)).size)
    }

    @Test
    fun stopsAtTheFirstFailedFrame() {
        var calls = 0
        val processor = WebRtcMicProcessor({ _, _ -> if (++calls == 2) -1 else 0 }, {})
        processor.process(ShortArray(frame * 5))
        assertEquals(2, calls)
    }

    @Test
    fun afterCloseItPassesAudioThroughAndNeverTouchesTheNativeModuleAgain() {
        var processed = 0
        var closed = 0
        val processor = WebRtcMicProcessor({ _, _ -> processed++; 0 }, { closed++ })
        processor.close()
        processor.close()
        val input = ShortArray(frame) { 3 }
        assertArrayEquals(input, processor.process(input))
        assertEquals(0, processed)
        assertEquals(1, closed)
    }

    @Test
    fun zeroGainReturnsTheSameSamples() {
        val samples = shortArrayOf(100, -200, 300)
        assertArrayEquals(samples, WebRtcMicProcessor.applyGainDb(samples, 0f))
    }

    @Test
    fun gainScalesInDecibels() {
        val louder = WebRtcMicProcessor.applyGainDb(shortArrayOf(1000, -1000), 6.0206f) // x2
        assertEquals(2000, louder[0].toInt())
        assertEquals(-2000, louder[1].toInt())
        val quieter = WebRtcMicProcessor.applyGainDb(shortArrayOf(1000, -1000), -6.0206f) // x0.5
        assertEquals(500, quieter[0].toInt())
        assertEquals(-500, quieter[1].toInt())
    }

    @Test
    fun gainClipsInsteadOfWrappingRound() {
        val out = WebRtcMicProcessor.applyGainDb(shortArrayOf(30000, -30000), 10f)
        assertEquals(Short.MAX_VALUE.toInt(), out[0].toInt())
        assertEquals(Short.MIN_VALUE.toInt(), out[1].toInt())
    }

    @Test
    fun gainDoesNotChangeTheCallersArray() {
        val samples = shortArrayOf(1000)
        WebRtcMicProcessor.applyGainDb(samples, 6f)
        assertEquals(1000, samples[0].toInt())
    }
}
