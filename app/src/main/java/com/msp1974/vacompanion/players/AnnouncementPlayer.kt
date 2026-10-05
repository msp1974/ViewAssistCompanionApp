package com.msp1974.vacompanion.players

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioFocusRequestCompat
import androidx.media3.common.audio.AudioManagerCompat
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Plays media_player announcements (play_media with announce: true) on a player separate from
 * [MusicPlayerService], so an announcement never replaces the music source, queue or position.
 *
 * Holds transient may-duck audio focus only while an announcement is playing. The music player
 * ducks on that focus loss and restores its volume when the focus is abandoned, so music that was
 * paused before the announcement stays paused.
 *
 * HA streams TTS WAVs with placeholder (0xFFFFFFFF) RIFF/data lengths over chunked HTTP, which
 * ExoPlayer reads as ~12h of audio and never reaches STATE_ENDED. So the announcement is
 * downloaded, its WAV lengths corrected, and played from a local file with a known duration.
 */
@SuppressLint("UnsafeOptInUsageError")
class AnnouncementPlayer(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var player: ExoPlayer? = null
    private var focusRequest: AudioFocusRequestCompat? = null
    private var currentFile: File? = null

    // Incremented on every play/stop so a slow download for an older request is discarded
    private var generation = 0

    companion object {
        private const val MAX_DOWNLOAD_BYTES = 20 * 1024 * 1024
        private const val TIMEOUT_MS = 10_000
        private const val END_GRACE_MS = 1500L
    }

    // Same usage/content type as the Assist voice response player
    private val audioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_NOTIFICATION)
        .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
        .build()

    private val watchdog = Runnable {
        Timber.w("Announcement did not report ended - releasing after expected duration")
        release()
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            Timber.d("Announcement player state: $playbackState")
            when (playbackState) {
                Player.STATE_READY -> scheduleWatchdog()
                Player.STATE_ENDED -> {
                    Timber.d("Announcement finished")
                    release()
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Timber.e(error, "Announcement playback failed")
            release()
        }
    }

    fun play(url: String) {
        runOnMain {
            Timber.d("Playing announcement: $url")
            val gen = ++generation
            // A new announcement replaces any announcement still playing, but keeps audio focus
            releasePlayer()
            // Duck music now so it is already down when speech starts
            requestAudioFocus()
            scope.launch {
                val file = try {
                    download(url, gen)
                } catch (e: Exception) {
                    Timber.w("Announcement download failed, streaming instead: $e")
                    null
                }
                withContext(Dispatchers.Main) {
                    if (gen == generation) {
                        currentFile = file
                        startPlayer(file?.toUri()?.toString() ?: url)
                    } else {
                        file?.delete()
                    }
                }
            }
        }
    }

    fun stop() {
        runOnMain {
            generation++
            if (player != null || focusRequest != null) {
                Timber.d("Stopping announcement")
            }
            release()
        }
    }

    private fun startPlayer(source: String) {
        try {
            requestAudioFocus()
            player = ExoPlayer.Builder(appContext)
                .setAudioAttributes(audioAttributes, false)
                .build().apply {
                    addListener(listener)
                    setMediaItem(MediaItem.fromUri(source.toUri()))
                    prepare()
                    play()
                }
        } catch (e: Exception) {
            Timber.e("Error playing announcement: $e")
            release()
        }
    }

    private fun scheduleWatchdog() {
        val p = player ?: return
        val duration = p.duration
        if (duration == C.TIME_UNSET) return
        mainHandler.removeCallbacks(watchdog)
        mainHandler.postDelayed(watchdog, (duration - p.currentPosition).coerceAtLeast(0) + END_GRACE_MS)
    }

    /** Downloads the announcement to a cache file, or returns null if too large to cache. */
    private fun download(url: String, gen: Int): File? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${connection.responseCode}")
            }
            val out = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    if (out.size() > MAX_DOWNLOAD_BYTES) {
                        Timber.w("Announcement too large to cache, streaming instead")
                        return null
                    }
                }
            }
            val bytes = out.toByteArray()
            val fixed = fixWavLengths(bytes)
            val file = File(appContext.cacheDir, "announcement_$gen")
            file.writeBytes(bytes)
            Timber.d("Announcement downloaded: ${bytes.size} bytes, wav lengths fixed=$fixed")
            return file
        } finally {
            connection.disconnect()
        }
    }

    /** Replaces oversized RIFF/data chunk lengths with the actual lengths. Returns true if changed. */
    private fun fixWavLengths(bytes: ByteArray): Boolean {
        if (bytes.size < 12 || String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE") return false
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var changed = false
        if (buf.getInt(4).toUInt().toLong() != (bytes.size - 8).toLong()) {
            buf.putInt(4, bytes.size - 8)
            changed = true
        }
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val size = buf.getInt(pos + 4).toUInt().toLong()
            val actual = (bytes.size - pos - 8).toLong()
            if (id == "data") {
                if (size > actual) {
                    buf.putInt(pos + 4, actual.toInt())
                    changed = true
                }
                break
            }
            if (size > actual) break
            pos += 8 + size.toInt() + (size.toInt() and 1)
        }
        return changed
    }

    private fun release() {
        releasePlayer()
        abandonAudioFocus()
    }

    private fun releasePlayer() {
        mainHandler.removeCallbacks(watchdog)
        player?.let {
            try {
                it.removeListener(listener)
                it.stop()
                it.release()
            } catch (e: Exception) {
                Timber.e("Error releasing announcement player: $e")
            }
        }
        player = null
        currentFile?.delete()
        currentFile = null
    }

    private fun requestAudioFocus() {
        if (focusRequest != null) return
        focusRequest = AudioFocusRequestCompat.Builder(AudioManagerCompat.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(audioAttributes)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { focusChange ->
                Timber.d("Announcement focus change: $focusChange")
            }
            .build()
        val result = AudioManagerCompat.requestAudioFocus(audioManager, focusRequest!!)
        Timber.d("Announcement requestAudioFocus: $result")
    }

    private fun abandonAudioFocus() {
        focusRequest?.let {
            val result = AudioManagerCompat.abandonAudioFocusRequest(audioManager, it)
            Timber.d("Announcement abandonAudioFocus: $result")
        }
        focusRequest = null
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }
}
