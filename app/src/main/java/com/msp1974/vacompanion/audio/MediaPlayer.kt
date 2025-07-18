package com.msp1974.vacompanion.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.utils.Logger
import java.io.IOException

class VAMediaPlayer(val context: Context) {
    private val log = Logger()
    private val config: APPConfig = APPConfig.getInstance(context)

    private var currentVolume: Float = config.musicVolume
    private var mediaPlayer: MediaPlayer = MediaPlayer()

    companion object {
        @Volatile
        private var instance: VAMediaPlayer? = null

        fun getInstance(context: Context) =
            instance ?: synchronized(this) {
                instance ?: VAMediaPlayer(context).also { instance = it }
            }
    }

    fun play(url: String) {
        try {
            if (mediaPlayer.isPlaying) {
                mediaPlayer.stop()
            }
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(url)
                prepare() // might take long! (for buffering, etc)
            }
            mediaPlayer.start()
            log.i("Music started")
        } catch (e: IllegalStateException) {
            e.printStackTrace()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    fun pause() {
        mediaPlayer.pause()
        log.i("Music paused")
    }

    fun resume() {
        mediaPlayer.start()
        log.i("Music resumed")
    }

    fun stop() {
        try {
            if (mediaPlayer.isPlaying) {
                mediaPlayer.stop()
                log.i("Music stopped")
            }
        } catch (e: Exception) {
            log.e("Error stopping music: $e")
        }
    }

    fun setVolume(volume: Float) {
        mediaPlayer.setVolume(volume, volume)
        currentVolume = volume
        log.i("Music volume set to $volume")
    }

    fun duckVolume() {

        val vol = config.duckingVolume
        if (mediaPlayer.isPlaying) {
            if (vol < currentVolume) {
                log.i("Ducking music volume from $currentVolume to ${config.duckingVolume}")
                mediaPlayer.setVolume(vol, vol)
            } else {
                log.i("Not ducking mMusic volume as it is lower than ducking volume of ${config.duckingVolume} at $currentVolume")
            }
        }
    }

    fun unDuckVolume() {
        log.i("Restoring music volume to $currentVolume")
        //TODO: Restore volume more smoothly
        setVolume(currentVolume)
    }
}
