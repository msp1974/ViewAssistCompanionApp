package com.msp1974.vacompanion.players

import android.annotation.SuppressLint
import android.media.AudioManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.audio.AudioFocusRequestCompat
import androidx.media3.common.audio.AudioManagerCompat
import timber.log.Timber

@SuppressLint("UnsafeOptInUsageError")
class AudioFocusRegistration(
    val audioManager: AudioManager,
    val audioAttributes: AudioAttributes,
    val focusGain: Int
) : AutoCloseable {
    private var focusRequest: AudioFocusRequestCompat? = null

    fun request() {
        if (focusRequest == null) {
            focusRequest = AudioFocusRequestCompat.Builder(focusGain)
                .setAudioAttributes(audioAttributes)
                .setOnAudioFocusChangeListener { }
                .build()
            val rq = AudioManagerCompat.requestAudioFocus(audioManager, focusRequest!!)
            Timber.d("Audio focus request result: $rq")
        }
    }

    fun abandon() {
        if (focusRequest != null) {
            val rq = AudioManagerCompat.abandonAudioFocusRequest(audioManager, focusRequest!!)
            Timber.d("Audio focus abandon result: $rq")
            focusRequest = null
        }
    }

    override fun close() {
        abandon()
    }

    companion object {
        fun request(
            audioManager: AudioManager,
            audioAttributes: AudioAttributes,
            focusGain: Int
        ) = AudioFocusRegistration(audioManager, audioAttributes, focusGain).apply {
            request()
        }
    }
}