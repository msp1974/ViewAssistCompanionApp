package com.msp1974.vacompanion.utils

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.provider.Settings


class SoundControl(private val context: Context) {
    private val audioManager: AudioManager
    private val notificationManager: NotificationManager
    private val log = Logger()

    init {
        this.audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        this.notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    // Toggle between silent, vibrate, and normal modes
    fun toggleSoundMode() {
        val currentMode = this.currentSoundMode

        when (currentMode) {
            MODE_SILENT -> setSoundMode(AudioManager.RINGER_MODE_VIBRATE)
            MODE_VIBRATE -> setSoundMode(AudioManager.RINGER_MODE_NORMAL)
            MODE_NORMAL -> setSoundMode(AudioManager.RINGER_MODE_SILENT)
        }
    }

    // Set the Sound Mode
    fun setSoundMode(mode: Int) {
        if (hasPermission) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                // For Android versions 7.0 (Nougat) to 8.1 (Oreo)
                audioManager.setRingerMode(mode)
            } else {
                // For Android versions 9 (Pie) and above
                Settings.Global.putInt(context.getContentResolver(), "zen_mode", mode)
            }
        } else {
            log.i("Set sound mode - Permissions not granted")
        }
    }

    val currentSoundMode: Int
        // Get the current sound mode
        get() {
            val ringerMode = audioManager.getRingerMode()

            when (ringerMode) {
                AudioManager.RINGER_MODE_SILENT -> return MODE_SILENT
                AudioManager.RINGER_MODE_VIBRATE -> return MODE_VIBRATE
                AudioManager.RINGER_MODE_NORMAL -> return MODE_NORMAL
                else -> return MODE_NORMAL
            }
        }

    val hasPermission: Boolean
        // Check if the app has permission to set the sound mode
        get() {
            return notificationManager.isNotificationPolicyAccessGranted
        }

    companion object {
        // Constants for different modes
        private const val MODE_SILENT = 0
        private const val MODE_VIBRATE = 1
        private const val MODE_NORMAL = 2
    }
}