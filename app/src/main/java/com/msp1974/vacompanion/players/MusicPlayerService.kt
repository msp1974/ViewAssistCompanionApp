package com.msp1974.vacompanion.players

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.IBinder
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
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider
import com.msp1974.vacompanion.audio.AudioOutputRouter
import com.msp1974.vacompanion.broadcasts.VacaBroadcastMessage
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.EventListener
import com.msp1974.vacompanion.device.DeviceManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds

@SuppressLint("UnsafeOptInUsageError")
@AndroidEntryPoint
class MusicPlayerService : Service(), EventListener {

    @Inject
    lateinit var deviceManager: DeviceManager

    private val config get() = deviceManager.config

    private lateinit var audioManager: AudioManager
    private var mediaPlayer: ExoPlayer? = null
    @SuppressLint("UnsafeOptInUsageError")
    private var focusRequest: AudioFocusRequestCompat? = null
    private var hasAudioFocus = false
    private var musicVolume: Float = 1f
    private var ducked: Boolean = false
    private var lastUrl: String = ""

    // Both reset only in onStartCommand() - i.e. only a genuinely new, externally-requested
    // play-media gets a fresh budget. Internal recovery calls to play() (from onPlayerError's
    // resumeAfterRecreate()) deliberately do not reset these, so retrying/redirect-following
    // actually converges instead of looping forever.
    private var retryCount = 0
    private var redirectCount = 0

    // Player.getVolume() is only accessible on the main thread, but unDuckVolume()/
    // animateUnDuckingVolume() need the current volume for comparisons/animation math that can
    // run off it - so the current volume is cached here (kept in sync by setPlayerVolume(), the
    // only place that should ever assign mediaPlayer?.volume) instead of read from the player.
    @Volatile
    private var currentOutputVolume: Float = 1f

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)

    companion object {
        var sInstance: MusicPlayerService? = null

        // "Retry once before giving up" on a non-redirect HTTP/IO error.
        private const val MAX_RETRIES = 1

        // Redirects are expected to be followed, not counted against MAX_RETRIES - but a
        // malformed/malicious redirect chain (e.g. one that cycles between distinct URLs) must
        // still be bounded, same as any real HTTP client would cap it.
        private const val MAX_REDIRECTS = 5

        private const val REMOTE_DUCK_TIMEOUT_MS = 60_000L
    }

    val audioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    override fun onCreate() {
        super.onCreate()
        sInstance = this
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        config.eventBroadcaster.addListener(this)
    }

    override fun onEventTriggered(event: Event) {
        if (event.eventName == "audioOutputId") {
            // Config events arrive on any thread, but ExoPlayer may only be used from its own
            // (main) thread.
            Handler(Looper.getMainLooper()).post {
                mediaPlayer?.setPreferredAudioDevice(AudioOutputRouter.resolvePreferredDevice(this, config))
            }
        } else if (event.eventName == "sendspinPlaybackStarted") {
            // Sendspin began streaming: stop the music player so the two don't play together.
            Handler(Looper.getMainLooper()).post {
                if (mediaPlayer?.isPlaying == true) {
                    Timber.d("Music player: stopping, Sendspin started playing")
                    abandonAudioFocus()
                    stop()
                }
            }
        } else if (event.eventName == "remoteWakeWordDetected") {
            // Another VACA device in our area or Sendspin group heard a wake word (UDP broadcast) - duck if we're playing.
            Handler(Looper.getMainLooper()).post {
                if (isRelevantTo(event) && mediaPlayer?.isPlaying == true) {
                    duckVolume()
                    remoteDuckTimeout?.cancel()
                    // Failsafe in case the matching "pipeline completed" broadcast is lost.
                    remoteDuckTimeout = scope.launch {
                        delay(REMOTE_DUCK_TIMEOUT_MS.milliseconds)
                        withContext(Dispatchers.Main) { remoteUnduck() }
                    }
                }
            }
        } else if (event.eventName == "remotePipelineCompleted") {
            Handler(Looper.getMainLooper()).post { if (isRelevantTo(event)) remoteUnduck() }
        }
    }

    // Same rule as the Sendspin player: the sender is in our Sendspin group or our area.
    private fun isRelevantTo(event: Event): Boolean =
        (event.newValue as? VacaBroadcastMessage)
            ?.isRelevantTo(config.sendspinGroupId, config.area) == true

    private var remoteDuckTimeout: Job? = null

    private fun remoteUnduck() {
        remoteDuckTimeout?.cancel()
        remoteDuckTimeout = null
        if (ducked && VoicePlayerService.sInstance?.hasAudioFocus != true) unDuckVolume()
    }

    val mediaPlayerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val event = Event("musicPlayerPlayingStatus", oldValue = !isPlaying, newValue = isPlaying)
            config.eventBroadcaster.notifyEvent(event)

            super.onIsPlayingChanged(isPlaying)
        }

        override fun onPlayerError(error: PlaybackException) {
            val wasPlaying = mediaPlayer?.isPlaying ?: false
            val position = mediaPlayer?.currentPosition ?: 0L

            val httpError = findInvalidResponseCodeException(error)
            val redirectUrl = httpError?.takeIf { it.responseCode in 300..399 }
                ?.let { redirectLocation(it) }

            stop()
            createMediaPlayer()

            when {
                redirectUrl != null && redirectCount < MAX_REDIRECTS -> {
                    redirectCount++
                    Timber.w(
                        "Player error - HTTP ${httpError.responseCode} redirect " +
                            "($redirectCount/$MAX_REDIRECTS) to $redirectUrl"
                    )
                    resumeAfterRecreate(redirectUrl, wasPlaying, position)
                }

                retryCount < MAX_RETRIES -> {
                    retryCount++
                    Timber.e(error, "Player error - retrying ($retryCount/$MAX_RETRIES): $lastUrl")
                    resumeAfterRecreate(lastUrl, wasPlaying, position)
                }

                else -> {
                    Timber.e(
                        error,
                        "Player error - giving up (redirects=$redirectCount, retries=$retryCount): $lastUrl"
                    )
                }
            }
            super.onPlayerError(error)
        }

        override fun onPlayerErrorChanged(error: PlaybackException?) {
            if (error != null) {
                Timber.e(error, "Player error changed")
            }
            super.onPlayerErrorChanged(error)
        }
    }

    /** Walks the cause chain for the HTTP response-code exception the default HTTP data
     * source throws on a non-2xx response - PlaybackException wraps it a couple of levels
     * deep (ExoPlaybackException -> ... -> InvalidResponseCodeException). */
    private fun findInvalidResponseCodeException(error: Throwable?): HttpDataSource.InvalidResponseCodeException? {
        var cause = error
        while (cause != null) {
            if (cause is HttpDataSource.InvalidResponseCodeException) return cause
            cause = cause.cause
        }
        return null
    }

    /** Extracts the `Location` header from a redirect response - header names are
     * case-insensitive per HTTP, but the response map's keys preserve whatever casing the
     * server actually sent, so this can't just be a direct map lookup. */
    @SuppressLint("UnsafeOptInUsageError")
    private fun redirectLocation(error: HttpDataSource.InvalidResponseCodeException): String? {
        return error.headerFields.entries
            .firstOrNull { it.key.equals("location", ignoreCase = true) }
            ?.value?.firstOrNull()
    }

    /** Common tail of error recovery: wait a moment for the freshly-recreated player to settle,
     * then replay at [url] (either the same track being retried, or a redirect target),
     * restoring the pre-error playing/paused state and position. */
    private fun resumeAfterRecreate(url: String, wasPlaying: Boolean, position: Long) {
        if (url.isEmpty()) return
        scope.launch {
            delay(1000.milliseconds)
            withContext(Dispatchers.Main) {
                play(url)
                mediaPlayer?.let { player ->
                    if (position > 0) player.seekTo(position)
                    if (!wasPlaying) player.pause()
                }
            }
        }
    }

    // ExoPlayer's default AudioTrack buffer for a 44.1kHz stream is ~750ms, which with the other
    // tracks this process keeps alive can exceed AudioFlinger's small per-process buffer pool on
    // low-memory devices (createTrack error -12). 400ms is plenty for a network stream, which
    // ExoPlayer buffers separately upstream of the track.
    private fun createRenderersFactory() = object : DefaultRenderersFactory(this) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
        ): AudioSink = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioTrackBufferSizeProvider(
                DefaultAudioTrackBufferSizeProvider.Builder()
                    .setMaxPcmBufferDurationUs(400_000)
                    .build()
            )
            .build()
    }

    private fun createMediaPlayer() {
        // Never leave a previous player alive: each holds an AudioTrack's buffer memory.
        if (mediaPlayer != null) stop()
        mediaPlayer = ExoPlayer.Builder(this, createRenderersFactory())
            .setAudioAttributes(audioAttributes, false)
            .build().apply {
                repeatMode = Player.REPEAT_MODE_OFF
                setPreferredAudioDevice(AudioOutputRouter.resolvePreferredDevice(this@MusicPlayerService, config))
            }
        mediaPlayer!!.addListener(mediaPlayerListener)
    }



    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (mediaPlayer == null) {
            createMediaPlayer()
        }

        val url = intent?.getStringExtra("url") ?: ""
        musicVolume = (intent?.getFloatExtra("volume", 1f) ?: 1f) / 100f
        // A genuinely new, externally-requested play-media always gets a fresh error-recovery
        // budget - see the fields' own comment for why this is the only place that resets them.
        retryCount = 0
        redirectCount = 0
        // Only one source at a time: tell the Sendspin player to stop (no-op if it isn't playing).
        if (url.isNotEmpty()) {
            config.eventBroadcaster.notifyEvent(Event("musicPlayerStarting", "", ""))
        }
        play(url)
        return START_STICKY
    }

    fun play(url: String) {
        if (url.isNotEmpty()) {
            lastUrl = url
        }
        Timber.d("Playing music: $url with volume: $musicVolume")
        if (mediaPlayer == null) return
        if (url.isNotEmpty()) {
            try {
                val mediaItem = MediaItem.fromUri(url.toUri())
                mediaPlayer?.let { player ->
                    player.setMediaItem(mediaItem)
                    player.prepare()
                    // Start ducked not just when we already think we're ducked, but also when
                    // the voice pipeline currently holds audio focus for TTS - if we weren't
                    // already playing (so never received our own duck notification), our own
                    // requestAudioFocus() below is about to win that focus outright rather than
                    // duck us (Android only ducks an *existing* GAIN holder when a transient
                    // request lands on it, never a fresh GAIN request against an existing
                    // transient holder) - so without this check we'd start at full volume right
                    // over the top of the assistant's response.
                    val startDucked = ducked || VoicePlayerService.sInstance?.hasAudioFocus == true
                    setPlayerVolume(if (startDucked) getDuckingVolume() else musicVolume)
                    ducked = startDucked
                    player.play()
                }
                requestAudioFocus()
            } catch (e: Exception) {
                Timber.e("Error playing music: $e")
            }
        }
    }

    fun pause() {
        Timber.d("Pausing music")
        abandonAudioFocus()
        mediaPlayer?.pause()
    }

    fun resume() {
        Timber.d("Music player: Resuming music")
        mediaPlayer?.let { player ->
            if (!player.isPlaying) {
                if (requestAudioFocus()) {
                    player.play()
                }
            }
        }
    }

    fun stop() {
        Timber.d("Music player: Stopping music")
        mediaPlayer?.let { player ->
            // Each step gets its own try/catch rather than one try around all three - a player
            // that's being torn down because it just errored is already in a degraded state, so
            // removeListener()/stop() throwing here is a real possibility, not just a defensive
            // formality. With a single shared try, that would skip release() entirely while
            // mediaPlayer is still nulled out in finally - silently leaking the old player's
            // native resources (decoder, surfaces) right in the one case (error recovery) this
            // cleanup most needs to be reliable for.
            try {
                player.stop()
            } catch (e: Exception) {
                Timber.e("Error stopping player: $e")
            }
            try {
                player.removeListener(mediaPlayerListener)
            } catch (e: Exception) {
                Timber.e("Error removing media player listener: $e")
            }
            try {
                player.release()
            } catch (e: Exception) {
                Timber.e("Error releasing player: $e")
            }
        }
        mediaPlayer = null
    }

    fun setVolume(volume: Float) {
        musicVolume = volume / 100f
        if (ducked && musicVolume >= (mediaPlayer?.volume ?: 1f))
            return
        setPlayerVolume(musicVolume)
    }

    /** The only place that should assign [ExoPlayer.volume] - keeps [currentOutputVolume] in sync. */
    private fun setPlayerVolume(volume: Float) {
        mediaPlayer?.volume = volume
        currentOutputVolume = volume
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun requestAudioFocus(): Boolean {
        if (hasAudioFocus) return hasAudioFocus

        focusRequest = AudioFocusRequestCompat.Builder(AudioManagerCompat.AUDIOFOCUS_GAIN)
            .setAudioAttributes(audioAttributes)
            .setAcceptsDelayedFocusGain(true)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { focusChange ->
                Timber.d("Music player focus change: $focusChange")
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_GAIN -> onFocusGained()

                    AudioManager.AUDIOFOCUS_LOSS -> {
                        hasAudioFocus = false
                        duckVolume()
                    }

                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        hasAudioFocus = false
                        duckVolume()
                    }

                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        hasAudioFocus = false
                        duckVolume()
                    }
                }
            }
            .build()

        val result = AudioManagerCompat.requestAudioFocus(audioManager, focusRequest!!)
        // A synchronous grant here is a real focus regain too, not just the async listener's
        // AUDIOFOCUS_GAIN branch above - without routing it through onFocusGained() as well,
        // this path would set hasAudioFocus = true without ever clearing ducked, leaving it
        // stuck the same way a desynced currentOutputVolume could (see unDuckVolume()).
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED && !hasAudioFocus) {
            onFocusGained()
        } else {
            hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        Timber.d("Music requestAudioFocus: $result")
        return hasAudioFocus
    }

    // The single place a real focus regain (synchronous grant or async AUDIOFOCUS_GAIN) should
    // go through, so ducked always converges with hasAudioFocus regardless of which path
    // detected the regain.
    private fun onFocusGained() {
        hasAudioFocus = true
        // Getting focus back doesn't mean the voice pipeline is done with it - if it still
        // holds its own (transient) focus for TTS, stay ducked regardless of what just
        // happened to our own request; onVoicePlaybackEnded() below is what un-ducks us once
        // the pipeline genuinely finishes, since no further focus callback of our own will
        // fire while we keep holding GAIN continuously.
        if (VoicePlayerService.sInstance?.hasAudioFocus != true) unDuckVolume()
    }

    /**
     * Called by [com.msp1974.vacompanion.satellite.SatelliteMediaManager.VoiceManager] once the
     * voice pipeline's TTS playback has genuinely finished - see [onFocusGained]'s doc comment
     * for why this can't just be inferred from our own focus callbacks in that scenario.
     */
    fun onVoicePlaybackEnded() {
        if (ducked) unDuckVolume()
    }

    private fun getDuckingVolume(): Float {
        return min(config.duckingVolume / 50f, musicVolume)
    }

    private fun duckVolume(silence: Boolean = false) {
        val duckVolume = if (silence) 0f else getDuckingVolume()
        Timber.d("Music player: Ducking volume to $duckVolume")
        setPlayerVolume(duckVolume)
        ducked = true
    }

    private fun unDuckVolume(animate: Boolean = true) {
        // Cleared unconditionally, before the "anything to animate" check below - ducked must
        // never stay true just because the volume already happened to be at musicVolume (e.g.
        // play() started a new track at full volume while still ducked - see its own comment).
        // ducked gates setVolume() below; leaving it stuck true after a genuine focus regain
        // would silently break all future volume-set requests.
        ducked = false
        if (currentOutputVolume == musicVolume) return
        if (animate) {
            animateUnDuckingVolume()
        } else {
            setPlayerVolume(musicVolume)
        }
    }

    private fun animateUnDuckingVolume (
        durationMs: Long = 1500,
        steps: Int = 5
    ) {
        Timber.d("Music player: Un-ducking volume")
        val delay = durationMs / steps
        val currentVolume = currentOutputVolume
        val increment = (musicVolume - currentVolume) / steps
        scope.launch {
            if (increment > 0) {
                for (i in 1..steps) {
                    val vol = currentVolume + (i * increment)
                    Timber.d("Music player: setting volume to $vol")
                    withContext(Dispatchers.Main) {
                        setPlayerVolume(vol)
                    }
                    delay(delay.milliseconds)
                }
            }
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun abandonAudioFocus() {
        if (hasAudioFocus) AudioManagerCompat.abandonAudioFocusRequest(audioManager, focusRequest!!)
        hasAudioFocus = false
        Timber.d("Music abandonAudioFocus")

    }

    override fun onDestroy() {
        Timber.d("Music service destroy")
        config.eventBroadcaster.removeListener(this)
        abandonAudioFocus()
        stop()
        sInstance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

}