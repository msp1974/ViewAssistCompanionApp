package com.msp1974.vacompanion.sendspin

import android.os.SystemClock

/**
 * What the now-playing overlay shows: the current Sendspin track, where playback is in it, and
 * which transport commands the server accepts. Published by [SendspinPlayer] as the
 * `sendspinNowPlaying` config event.
 */
data class SendspinNowPlaying(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val artworkUrl: String? = null,
    val durationMs: Long = 0,
    /** Track position when this state arrived; see [positionAtMs]. */
    val positionMs: Long = 0,
    /** [SystemClock.elapsedRealtime] when [positionMs] was current. */
    val positionAtMs: Long = 0,
    /** Playback speed in thousandths (1000 = normal), or -1 when the server didn't say. */
    val speed: Int = -1,
    /** Sendspin audio is being streamed or the group is playing, and a track is known. */
    val active: Boolean = false,
    /** Audio is actually playing, as opposed to paused. */
    val playing: Boolean = false,
    /** Playback commands the server accepts, e.g. "play", "pause", "next", "shuffle". */
    val commands: Set<String> = emptySet(),
    val shuffle: Boolean = false,
    /** "off", "one" or "all". */
    val repeat: String = "off",
    /** Colours the server derived from the track, if it provided any. */
    val colors: SendspinColors = SendspinColors(),
) {
    val hasContent: Boolean get() = title.isNotBlank() || artist.isNotBlank()

    /** The track position now, advancing from the last report while playing. */
    fun positionNow(now: Long = SystemClock.elapsedRealtime()): Long {
        if (!playing) return positionMs.coerceAtLeast(0)
        val rate = if (speed > 0) speed / 1000.0 else 1.0
        val position = positionMs + ((now - positionAtMs) * rate).toLong()
        return if (durationMs > 0) position.coerceIn(0, durationMs) else position.coerceAtLeast(0)
    }
}

/**
 * The palette a Sendspin server derives from the current track, as opaque ARGB ints. 0 means the
 * server didn't provide that colour. Each background comes with a foreground that has enough
 * contrast on it, for a dark and a light theme.
 */
data class SendspinColors(
    val backgroundDark: Int = 0,
    val backgroundLight: Int = 0,
    val onDark: Int = 0,
    val onLight: Int = 0,
)
