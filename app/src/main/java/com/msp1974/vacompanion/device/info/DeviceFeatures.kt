package com.msp1974.vacompanion.device.info

import android.app.NotificationManager
import android.content.Context
import android.content.Context.NOTIFICATION_SERVICE
import android.media.AudioManager
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor

data class DeviceAudioFeaturesData(
    val acousticEchoCancellation: Boolean,
    val autoGainControl: Boolean,
    val noiseSuppression: Boolean,
    val maxMusicVolume: Int,
    val maxNotificationVolume: Int
)

data class DeviceFeaturesData(
    val audio: DeviceAudioFeaturesData,
    val supportsDND: Boolean
)

class DeviceFeatures(
    private val context: Context,
    ) {

    val featuresInfo = DeviceFeaturesData(
        audio = getAudioFeatureData(),
        supportsDND = hasDND()
    )

    private fun getAudioFeatureData(): DeviceAudioFeaturesData {
        return DeviceAudioFeaturesData(
            acousticEchoCancellation = AcousticEchoCanceler.isAvailable(),
            autoGainControl = AutomaticGainControl.isAvailable(),
            noiseSuppression = NoiseSuppressor.isAvailable(),
            maxMusicVolume = getMaxStreamVolume(AudioManager.STREAM_MUSIC),
            maxNotificationVolume = getMaxStreamVolume(AudioManager.STREAM_NOTIFICATION)

        )
    }

    private fun hasDND(): Boolean {
        val notificationManager = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        return notificationManager.isNotificationPolicyAccessGranted
    }

    private fun getMaxStreamVolume(stream: Int): Int {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return audioManager.getStreamMaxVolume(stream)
    }
}
