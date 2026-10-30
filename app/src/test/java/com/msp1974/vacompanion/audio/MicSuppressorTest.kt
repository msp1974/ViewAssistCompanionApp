package com.msp1974.vacompanion.audio

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicSuppressorTest {

    private class FakeClock(var now: Long = 0L) {
        fun read() = now
    }

    /** A loud steady signal, so a drop in level is easy to see. */
    private fun frame(level: Short = 10000) = ShortArray(1280) { level }

    private fun peak(frame: ShortArray) = frame.maxOf { kotlin.math.abs(it.toInt()) }

    // ---- the gain applied to the microphone

    @Test
    fun notSuppressedUntilAskedTo() {
        val suppressor = MicSuppressor(FakeClock()::read)
        assertFalse(suppressor.isSuppressed)
        assertEquals(10000, peak(suppressor.applyTo(frame(), micGainDb = 0f)))
    }

    @Test
    fun suppressionDropsTheGainToAVeryLowLevel() {
        val clock = FakeClock()
        val suppressor = MicSuppressor(clock::read)
        suppressor.suppressFor(1000)
        assertTrue(suppressor.isSuppressed)
        // -60 dB takes 10000 down to 10: speech in it would be lost in the noise.
        assertTrue(peak(suppressor.applyTo(frame(), micGainDb = 0f)) <= 10)
    }

    @Test
    fun suppressionOverridesAnyMicGainSetting() {
        val suppressor = MicSuppressor(FakeClock()::read)
        suppressor.suppressFor(1000)
        assertTrue(peak(suppressor.applyTo(frame(), micGainDb = 10f)) <= 32)
    }

    @Test
    fun gainRevertsToNormalWhenTheTimeIsUp() {
        val clock = FakeClock()
        val suppressor = MicSuppressor(clock::read)
        suppressor.suppressFor(1000)

        clock.now = 999
        assertTrue(suppressor.isSuppressed)
        clock.now = 1000
        assertFalse(suppressor.isSuppressed)

        assertEquals(10000, peak(suppressor.applyTo(frame(), micGainDb = 0f)))
        // The mic gain setting is back in force, not just unity.
        assertEquals(20000, peak(suppressor.applyTo(frame(), micGainDb = 6.0206f)))
    }

    @Test
    fun aLaterCallReplacesTheDeadlineSoItCanBeShortened() {
        val clock = FakeClock()
        val suppressor = MicSuppressor(clock::read)
        suppressor.suppressFor(4000)
        clock.now = 500
        suppressor.suppressFor(200)
        clock.now = 699
        assertTrue(suppressor.isSuppressed)
        clock.now = 700
        assertFalse(suppressor.isSuppressed)
    }

    // ---- following a sound while it plays

    @Test
    fun suppressionStartsStraightAwayBeforeThePlaybackBegins() = runBlocking {
        val clock = FakeClock()
        val suppressor = MicSuppressor(clock::read)
        val playing = MutableStateFlow(false)
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            suppressor.suppressWhilePlaying(playing, maxMs = 4000, trailingMs = 200)
        }
        // The sound is not audible yet, but the microphone is already down.
        assertTrue(suppressor.isSuppressed)
        job.cancel()
    }

    @Test
    fun staysSuppressedForTheWholeSoundThenOnlyAShortMarginAfter() = runBlocking {
        val clock = FakeClock()
        val suppressor = MicSuppressor(clock::read)
        val playing = MutableStateFlow(false)
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            suppressor.suppressWhilePlaying(playing, maxMs = 4000, trailingMs = 200)
        }

        playing.value = true
        yield()
        clock.now = 1500 // well into a long sound
        assertTrue(suppressor.isSuppressed)

        clock.now = 2000 // the sound ends
        playing.value = false
        job.join()

        clock.now = 2199
        assertTrue("still within the trailing margin", suppressor.isSuppressed)
        clock.now = 2200
        assertFalse("back to normal once the margin is over", suppressor.isSuppressed)
    }

    @Test
    fun anEndedReadingLeftOverFromAnEarlierSoundIsNotTakenForTheEnd() = runBlocking {
        val clock = FakeClock()
        val suppressor = MicSuppressor(clock::read)
        val playing = MutableStateFlow(false) // the player still says "ended" from last time
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            suppressor.suppressWhilePlaying(playing, maxMs = 4000, trailingMs = 200)
        }
        yield()
        clock.now = 300
        assertTrue("must not have shortened to the margin yet", suppressor.isSuppressed)
        assertTrue(job.isActive)

        playing.value = true
        yield()
        playing.value = false
        job.join()
        assertTrue(suppressor.isSuppressed) // 200 ms margin from now
        clock.now = 500
        assertFalse(suppressor.isSuppressed)
    }

    @Test
    fun aSoundThatNeverStartsDoesNotHoldTheMicrophoneDownForever() = runBlocking {
        val clock = FakeClock()
        val suppressor = MicSuppressor(clock::read)
        val playing = MutableStateFlow(false)
        // Returns on its own once the safety-net time is up, rather than waiting for ever.
        withTimeout(2000) { suppressor.suppressWhilePlaying(playing, maxMs = 50, trailingMs = 200) }
        clock.now = 50
        assertFalse(suppressor.isSuppressed)
    }
}
