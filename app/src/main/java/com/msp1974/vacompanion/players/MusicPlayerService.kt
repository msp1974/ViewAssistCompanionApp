package com.msp1974.vacompanion.players

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.media.AudioManager
import android.os.IBinder
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioFocusRequestCompat
import androidx.media3.common.audio.AudioManagerCompat
import androidx.media3.exoplayer.ExoPlayer
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.device.DeviceManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
class MusicPlayerService : Service() {

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
    }

    val audioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    override fun onCreate() {
        super.onCreate()
        sInstance = this
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
    }

    val mediaPlayerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val event = Event("musicPlayerPlayingStatus", oldValue = !isPlaying, newValue = isPlaying)
            config.eventBroadcaster.notifyEvent(event)

            super.onIsPlayingChanged(isPlaying)
        }

        override fun onPlayerError(error: PlaybackException) {
            Timber.e(error, "Player error - recreating player....")
            val wasPlaying = mediaPlayer?.isPlaying ?: false
            val position = mediaPlayer?.currentPosition ?: 0L

            stop()
            createMediaPlayer()

            if (lastUrl.isNotEmpty()) {
                scope.launch {
                    delay(1000.milliseconds)
                    withContext(Dispatchers.Main) {
                        if (lastUrl.isNotEmpty()) {
                            play(lastUrl)
                            mediaPlayer?.let { player ->
                                if (position > 0) player.seekTo(position)
                                if (!wasPlaying) player.pause()
                            }
                        }
                    }
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

    private fun createMediaPlayer() {
        mediaPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, false)
            .build().apply {
                repeatMode = Player.REPEAT_MODE_OFF
            }
        mediaPlayer!!.addListener(mediaPlayerListener)
    }



    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (mediaPlayer == null) {
            createMediaPlayer()
        }

        val url = intent?.getStringExtra("url") ?: ""
        musicVolume = (intent?.getFloatExtra("volume", 1f) ?: 1f) / 100f
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
                    setPlayerVolume(musicVolume)
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
            try {
                player.removeListener(mediaPlayerListener)
                player.stop()
                player.release()
            } catch (e: Exception) {
                Timber.e("Error stopping/releasing player: $e")
            } finally {
                mediaPlayer = null
            }
        }
    }

    fun setVolume(volume: Float) {
        if (!ducked) {
            musicVolume = volume / 100f
            setPlayerVolume(musicVolume)
        }
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
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        hasAudioFocus = true
                        unDuckVolume()
                    }

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
        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        Timber.d("Music requestAudioFocus: $result")
        return hasAudioFocus
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
        if (currentOutputVolume == musicVolume) return
        if (animate) {
            animateUnDuckingVolume()
        } else {
            setPlayerVolume(musicVolume)
        }
        ducked = false
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
            delay(2000.milliseconds)
            ducked = false
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
        abandonAudioFocus()
        stop()
        sInstance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

}