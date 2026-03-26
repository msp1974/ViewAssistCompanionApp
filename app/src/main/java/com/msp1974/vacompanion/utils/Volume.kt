package com.msp1974.vacompanion.utils

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings

class VolumeObserver(
    private val context: Context,
    private val onVolumeChanged: (musicVolume:Int, notificationVolume:Int) -> Unit
) : ContentObserver(Handler(Looper.getMainLooper())) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        super.onChange(selfChange, uri)

        // We check the "music" stream specifically, but you can check others
        val musicVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val notificationVolume = audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
        onVolumeChanged(musicVolume, notificationVolume)
    }

    fun register() {
        context.contentResolver.registerContentObserver(
            Settings.System.CONTENT_URI,
            true,
            this
        )
    }

    fun unregister() {
        context.contentResolver.unregisterContentObserver(this)
    }
}