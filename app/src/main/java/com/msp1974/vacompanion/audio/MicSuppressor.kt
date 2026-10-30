package com.msp1974.vacompanion.audio

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Turns the microphone right down for a while, so that a sound the device is playing itself - the
 * wake sound, when it is a spoken one such as "yes sir" - is not picked up and sent on as part of
 * what the user said. Normal gain returns on its own once the time is up.
 *
 * [applyTo] is the last step of the microphone's processing, after WebRTC and the `mic_gain`
 * setting. It works on whole frames, so the change lands on a frame boundary.
 */
class MicSuppressor(private val clock: () -> Long = System::currentTimeMillis) {

    // Wall-clock deadline until which the microphone stays turned down.
    @Volatile
    private var suppressUntilMs = 0L

    val isSuppressed: Boolean
        get() = clock() < suppressUntilMs

    /**
     * Turns the microphone down for [durationMs] from now. Calling it again simply replaces the
     * deadline, so a shorter, more precise duration can follow once it is known.
     */
    fun suppressFor(durationMs: Long) {
        suppressUntilMs = clock() + durationMs
    }

    /** [frame] at [micGainDb], or at [SUPPRESSED_GAIN_DB] instead while suppressed. */
    fun applyTo(frame: ShortArray, micGainDb: Float): ShortArray =
        WebRtcMicProcessor.applyGainDb(frame, if (isSuppressed) SUPPRESSED_GAIN_DB else micGainDb)

    /**
     * Suppresses the microphone from now until the playback described by [playing] has finished.
     *
     * Suppression starts at once, before the sound is audible, for [maxMs] as a safety net in case
     * the end is never seen (a player error, say). When playback ends it is cut back to [trailingMs],
     * which covers the sound still on its way from the speaker to the microphone and the
     * microphone's own buffering.
     *
     * [playing] must be true while the sound plays. A false reading before it has first been true
     * is taken to be left over from an earlier sound, not the end of this one.
     */
    suspend fun suppressWhilePlaying(
        playing: Flow<Boolean>,
        maxMs: Long = MAX_SUPPRESSION_MS,
        trailingMs: Long = TRAILING_MARGIN_MS,
    ) {
        suppressFor(maxMs)
        withTimeoutOrNull(maxMs) {
            playing.first { it }
            playing.first { !it }
            suppressFor(trailingMs)
        }
    }

    companion object {
        /** Gain while suppressed - low enough that speech in the sound is lost in the noise. */
        const val SUPPRESSED_GAIN_DB = -60f

        /** Longest a short notification sound could plausibly take to finish. */
        const val MAX_SUPPRESSION_MS = 4000L

        /** Kept on after playback ends, for the sound's travel from the speaker and the mic buffer. */
        const val TRAILING_MARGIN_MS = 200L
    }
}
