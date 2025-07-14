package com.msp1974.vacompanion.audio

import android.content.Context.AUDIO_SERVICE
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer

internal interface AudioInCallback {
    fun onAudio(audioBuffer: ShortArray)
    fun onError(err: String)
}

internal class AudioManager(context: Context) {
    private val audioManager = context.getSystemService(AUDIO_SERVICE) as AudioManager

    private fun streamMaxVolume(stream: Int): Int {
        return audioManager.getStreamMaxVolume(stream)
    }

    fun setVolume(stream: Int, volume: Float) {
        audioManager.setStreamVolume(stream, (streamMaxVolume(stream)*volume).toInt(), 0)
    }

    fun getVolume(stream: Int): Float {
        return audioManager.getStreamVolume(stream).toFloat() / streamMaxVolume(stream).toFloat()
    }
}

internal class WakeWordSoundPlayer(private val context: Context, private val resId: Int) {
    private lateinit var wwSound: MediaPlayer
    fun play() {
        // Create MediaPlayer instance with a resource audio resource
        //TODO: This plays on music stream but needs to play on notification stream!!!
        wwSound = MediaPlayer.create(context, resId)
        wwSound.setAudioAttributes(
            AudioAttributes.Builder()
                .setLegacyStreamType(AudioManager.STREAM_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .build()
        )
        wwSound.start()
    }
}





