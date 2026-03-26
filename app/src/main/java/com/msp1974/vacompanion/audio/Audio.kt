package com.msp1974.vacompanion.audio

import android.content.Context.AUDIO_SERVICE
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.C.USAGE_NOTIFICATION
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.min

internal class AudioManager(context: Context) {
    private val audioManager = context.getSystemService(AUDIO_SERVICE) as AudioManager

    fun getStreamMaxVolume(stream: Int): Int {
        return audioManager.getStreamMaxVolume(stream)
    }

    fun setVolume(stream: Int, volume: Int) {
        audioManager.setStreamVolume(stream, min(getStreamMaxVolume(stream) ,volume).toInt(), 0)
    }

    fun getVolume(stream: Int): Float {
        return audioManager.getStreamVolume(stream).toFloat() / getStreamMaxVolume(stream).toFloat()
    }
}

internal class SoundClipPlayer(private val context: Context, private val resId: Int) {
    private lateinit var mediaPlayer: ExoPlayer

    fun play() {
        try {
            Handler(context.mainLooper).post({
                mediaPlayer = ExoPlayer.Builder(context).build()
                val mediaItem = MediaItem.fromUri("android.resource://${context.packageName}/$resId".toUri())
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(USAGE_NOTIFICATION)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build()
                mediaPlayer.setAudioAttributes(audioAttributes, false)
                mediaPlayer.setMediaItem(mediaItem)

                mediaPlayer.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        super.onPlaybackStateChanged(playbackState)
                        if (playbackState == Player.STATE_ENDED) {
                            mediaPlayer.release()
                        }
                    }
                })

                // Prepare the player.
                mediaPlayer.prepare()
                // Start the playback.
                mediaPlayer.play()
            })
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
    }
}





