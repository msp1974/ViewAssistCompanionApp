package com.msp1974.vacompanion.audio

import android.content.Context
import android.os.Handler
import com.msp1974.vacompanion.settings.APPConfig
import timber.log.Timber
import kotlin.concurrent.thread
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer


class VAMediaPlayer(val context: Context) {
    private val config: APPConfig = APPConfig.getInstance(context)
    private var currentVolume: Int = config.musicVolume
    private var mediaPlayer: ExoPlayer? = null
    var isVolumeDucked: Boolean = false
    var playRequested: Boolean = false
    val maxVolume = AudioManager(context).getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)

    companion object {
        @Volatile
        private var instance: VAMediaPlayer? = null

        fun getInstance(context: Context) =
            instance ?: synchronized(this) {
                instance ?: VAMediaPlayer(context).also { instance = it }
            }
    }

    fun play(url: String) {
        Handler(context.mainLooper).post({
            try {
                if (playRequested) {
                    mediaPlayer!!.stop()
                }
            } catch (e: IllegalStateException) {
                // Here is media player is stopped
            }

            playRequested = true

            try {
                mediaPlayer = ExoPlayer.Builder(context).build()
                val mediaItem = MediaItem.fromUri(url.toUri())
                mediaPlayer!!.setMediaItem(mediaItem)
                // Prepare the player.
                mediaPlayer!!.prepare()
                // Start the playback.
                mediaPlayer!!.play()
                Timber.i("Music started")
            } catch (ex: Exception) {
                Timber.e("Error playing music: $ex")
                ex.printStackTrace()
            }
        })
    }

    fun pause() {
        Handler(context.mainLooper).post({
            try {
                mediaPlayer!!.pause()
                Timber.i("Music paused")
            } catch (ex: Exception) {
                Timber.e("Error pausing music: $ex")
            }
        })
    }

    fun resume() {
        Handler(context.mainLooper).post({
            try {
                mediaPlayer!!.play()
                Timber.i("Music resumed")
            } catch (ex: Exception) {
                Timber.e("Error resuming music: $ex")
            }
        })
    }

    fun stop() {
        Handler(context.mainLooper).post({
            try {
                playRequested = false
                mediaPlayer!!.stop()
                mediaPlayer!!.release()
                Timber.i("Music stopped")

            } catch (e: Exception) {
                Timber.e("Error stopping music: $e")
            }
        })
    }

    fun setVolume(volume: Int) {
        Handler(context.mainLooper).post({
            if (!isVolumeDucked && mediaPlayer != null) {
                val audioMgr = AudioManager(context)
                mediaPlayer!!.volume = volume / audioMgr.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).toFloat()
            }
            currentVolume = volume
            Timber.i("Music volume set to $volume")
        })
    }

    fun duckVolume() {
        Handler(context.mainLooper).post({
            if (!isVolumeDucked && mediaPlayer != null) {
                if (mediaPlayer!!.isPlaying) {
                    val vol = config.duckingVolume
                    if (vol < config.musicVolume) {
                        Timber.d("Ducking music volume from $currentVolume to $vol")
                        mediaPlayer!!.volume = (vol / maxVolume).toFloat()
                        isVolumeDucked = true
                    } else {
                        Timber.d("Not ducking music volume as it is lower than ducking volume of ${config.duckingVolume} at ${config.musicVolume}")
                    }
                }
            }
        })
    }

    fun unDuckVolume() {
        if (isVolumeDucked) {
            Timber.i("Restoring music volume to ${currentVolume}")
            thread(name = "volumeUnducking") {
                val steps = 3
                val diffStepVolume = (currentVolume - config.duckingVolume) / steps
                for (i in 1..steps) {
                    val vol = config.duckingVolume + (diffStepVolume * i)
                    Handler(context.mainLooper).post({
                        mediaPlayer!!.volume = (vol / maxVolume).toFloat()
                    })
                    if (i < steps) {
                        Thread.sleep(250)
                    }
                }
            }
            isVolumeDucked = false
        }
    }
}
