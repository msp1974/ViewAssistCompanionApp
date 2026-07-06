package com.msp1974.vacompanion.players

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.IBinder
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.C.USAGE_NOTIFICATION
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioFocusRequestCompat
import androidx.media3.common.audio.AudioManagerCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.msp1974.vacompanion.R
import timber.log.Timber

@UnstableApi
class AlarmService : Service() {

    private lateinit var audioManager: AudioManager
    private var mediaPlayer: ExoPlayer? = null
    private var focusRequest: AudioFocusRequestCompat? = null
    private var hasAudioFocus = false

    companion object {
        var sInstance: AlarmService? = null
    }

    override fun onCreate() {
        super.onCreate()
        sInstance = this
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (mediaPlayer == null) {
            mediaPlayer = createPlayer()
        }

        val uri = intent?.getStringExtra("uri") ?: ""
        play(uri.toUri())
        return START_NOT_STICKY
    }

    private fun createPlayer(): ExoPlayer {
        val player = ExoPlayer.Builder(this).build()
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_ALARM)
            .setContentType(C.AUDIO_CONTENT_TYPE_SONIFICATION)
            .build()
        player.setAudioAttributes(audioAttributes, false)
        return player
    }

    fun play(uri: Uri) {
        Timber.i("Alarm started: $uri")
        val player = mediaPlayer ?: return

        try {
            val mediaUri = if (uri.toString().isNotBlank()) {
                uri
            } else {
                "asset:///alarm/default.mp3".toUri()
            }
            player.setMediaItem(MediaItem.fromUri(mediaUri))
            player.repeatMode = Player.REPEAT_MODE_ONE
            player.prepare()
            player.volume = 1f
            requestAudioFocus()
            player.play()
        } catch (e: Exception) {
            Timber.e("Error playing music: $e")
        }
    }

    fun pause() {
        mediaPlayer?.pause()
    }

    fun resume() {
        mediaPlayer?.let { player ->
            if (!player.isPlaying) {
                if (requestAudioFocus()) {
                    player.play()
                }
            }
            player.volume = 1.0f
        }
    }

    fun stop() {
        mediaPlayer?.let { player ->
            try {
                player.stop()
                player.release()
            } catch (e: Exception) {
                Timber.e("Error stopping/releasing player: $e")
            } finally {
                mediaPlayer = null
            }
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun requestAudioFocus(): Boolean {
        @SuppressLint("UnsafeOptInUsageError")
        focusRequest = AudioFocusRequestCompat.Builder(AudioManagerCompat.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(mediaPlayer?.audioAttributes!!)
            .setAcceptsDelayedFocusGain(true)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { focusChange ->
                Timber.d("Alarm focus change: $focusChange")
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        hasAudioFocus = true
                        resume()
                    }

                    AudioManager.AUDIOFOCUS_LOSS -> {
                        hasAudioFocus = false
                        stop()
                    }

                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        hasAudioFocus = false
                        pause()
                    }

                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        mediaPlayer?.volume = 0.2f
                    }
                }
            }
            .build()

        val result = AudioManagerCompat.requestAudioFocus(audioManager, focusRequest!!)

        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        Timber.d("Alarm requestAudioFocus: $result")
        return hasAudioFocus
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun abandonAudioFocus() {
        if (hasAudioFocus) AudioManagerCompat.abandonAudioFocusRequest(audioManager, focusRequest!!)
        hasAudioFocus = false
        Timber.d("Alarm abandonAudioFocus")
    }

    override fun onDestroy() {
        stop()
        abandonAudioFocus()
        sInstance = null
        super.onDestroy()
        Timber.i("Alarm player stopped")
    }

    override fun onBind(intent: Intent?): IBinder? = null

}