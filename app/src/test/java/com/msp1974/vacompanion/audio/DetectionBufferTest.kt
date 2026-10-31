package com.msp1974.vacompanion.audio

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionBufferTest {

    /** A one-sample "frame" carrying [n], so order is easy to check. */
    private fun frame(n: Int) = shortArrayOf(n.toShort())

    private fun List<ShortArray>.values() = map { it[0].toInt() }

    private fun newMic() = MutableSharedFlow<ShortArray>(extraBufferCapacity = 256)

    @Test
    fun isListeningToTheMicrophoneTheMomentItIsCreated() = runBlocking {
        val mic = newMic()
        val buffer = DetectionBuffer(mic, this)
        // A frame arriving straight away would be lost if the subscription came later.
        assertEquals(1, mic.subscriptionCount.value)
        buffer.cancel()
    }

    @Test
    fun keepsAudioHeardBeforeAnyoneReadsItAndThenContinuesLive() = runBlocking {
        val mic = newMic()
        val buffer = DetectionBuffer(mic, this)

        // Said while the devices were still agreeing who answers.
        (1..3).forEach { mic.emit(frame(it)) }
        yield()

        val read = async { buffer.frames.take(6).toList() }
        yield()
        // And what is said once the winner has started reading.
        (4..6).forEach { mic.emit(frame(it)) }

        // In order, and nothing missed or repeated where kept audio meets live audio.
        assertEquals(listOf(1, 2, 3, 4, 5, 6), read.await().values())
        buffer.cancel()
    }

    @Test
    fun aReaderThatArrivesLateStillGetsEverythingInOrder() = runBlocking {
        val mic = newMic()
        val buffer = DetectionBuffer(mic, this)
        (1..5).forEach { mic.emit(frame(it)) }
        yield()
        assertEquals(listOf(1, 2, 3, 4, 5), buffer.frames.take(5).toList().values())
        buffer.cancel()
    }

    @Test
    fun cancellingStopsListeningAndDropsWhatWasKept() = runBlocking {
        val mic = newMic()
        val buffer = DetectionBuffer(mic, this)
        mic.emit(frame(1))
        yield()

        buffer.cancel()
        yield()

        assertTrue(buffer.isCancelled)
        assertEquals(0, mic.subscriptionCount.value)
        // Nothing more is kept, and a reader finds nothing left.
        mic.emit(frame(2))
        yield()
        assertEquals(emptyList<Int>(), buffer.frames.toList().values())
    }

    @Test
    fun aReaderInProgressEndsWhenTheBufferIsCancelled() = runBlocking {
        val mic = newMic()
        val buffer = DetectionBuffer(mic, this)
        val read = async { buffer.frames.toList() }
        mic.emit(frame(1))
        mic.emit(frame(2))
        repeat(5) { yield() } // let the reader take what has arrived
        buffer.cancel()
        // It got what it read before the cancel, and its flow ended rather than hanging.
        assertEquals(listOf(1, 2), read.await().values())
    }

    @Test
    fun whenFullTheOldestAudioIsDroppedNotTheNewest() = runBlocking {
        val mic = newMic()
        val buffer = DetectionBuffer(mic, this, capacityFrames = 3)
        (1..6).forEach {
            mic.emit(frame(it))
            yield()
        }
        assertEquals(listOf(4, 5, 6), buffer.frames.take(3).toList().values())
        buffer.cancel()
    }

    @Test
    fun isNotCancelledWhileItIsListening() = runBlocking {
        val buffer = DetectionBuffer(newMic(), this)
        assertFalse(buffer.isCancelled)
        buffer.cancel()
    }
}
