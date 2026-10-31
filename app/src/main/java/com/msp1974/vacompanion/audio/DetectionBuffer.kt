package com.msp1974.vacompanion.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Keeps the microphone audio from the moment a wake word is heard, while the devices that heard
 * it agree which one answers. Whatever was said in the meantime - the start of the command - is
 * then not lost: the winner reads [frames] to get the kept audio first and the live audio after
 * it, in order, with nothing missed or repeated at the join. The others [cancel].
 *
 * It listens as soon as it is created, so create it the instant the wake word is detected. If
 * nobody reads it, the oldest audio is dropped once [capacityFrames] is full.
 */
class DetectionBuffer(
    audio: Flow<ShortArray>,
    scope: CoroutineScope,
    capacityFrames: Int = DEFAULT_CAPACITY_FRAMES,
) {
    private val channel = Channel<ShortArray>(capacityFrames, BufferOverflow.DROP_OLDEST)

    // Started undispatched so the microphone is already being listened to when the constructor
    // returns; a frame arriving in between would be lost to everything that listens later.
    private val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            audio.collect { channel.trySend(it) }
        } finally {
            channel.close()
        }
    }

    /** The kept audio, then live audio, until [cancel]. Meant for a single reader. */
    val frames: Flow<ShortArray> = channel.receiveAsFlow()

    val isCancelled: Boolean
        get() = !job.isActive

    /** Stops listening and drops whatever has not been read. The reader's flow then ends. */
    fun cancel() {
        job.cancel()
        // Closing ends a reader's flow, but it could still read what was kept first; empty the
        // channel so a device that lost can't pass on audio it was never meant to send.
        channel.close()
        while (channel.tryReceive().isSuccess) { /* dropped */ }
    }

    companion object {
        /** About 16 s of 80 ms frames: far longer than the decision takes. */
        const val DEFAULT_CAPACITY_FRAMES = 200
    }
}
