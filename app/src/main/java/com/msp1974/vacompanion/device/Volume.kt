package com.msp1974.vacompanion.device


import android.app.NotificationManager
import android.content.Context
import android.content.Context.AUDIO_SERVICE
import android.content.Context.NOTIFICATION_SERVICE
import android.database.ContentObserver
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.FirebaseManager
import timber.log.Timber
import javax.inject.Inject
import kotlin.math.min

class VolumeObserver(
    private val context: Context,
    private val onVolumeChanged: (musicVolume:Int, notificationVolume:Int) -> Unit
) : ContentObserver(Handler(Looper.getMainLooper())) {

    private val audioManager = context.getSystemService(AUDIO_SERVICE) as AudioManager

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

internal class AudioVolumeManager(context: Context) {
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

class VolumeManager(val context: Context) {
    @Inject
    lateinit var deviceManager: DeviceManager

    private val config get() = deviceManager.config

    val firebase = FirebaseManager.getInstance(context)

    fun setVolume(stream: Int, volume: Int) {
        try {
            val audioManager = AudioVolumeManager(context)
            audioManager.setVolume(stream, volume)
            Timber.d("Set volume $stream to $volume")
        } catch (e: Exception) {
            Timber.d("Error setting volume: ${e.message.toString()}")
            firebase.logException(e)
        }
    }

    fun setDoNotDisturb(enable: Boolean) {
        val notificationManager =  context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val isInDND = notificationManager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        if (isInDND != enable) {
            if (notificationManager.isNotificationPolicyAccessGranted) {
                Timber.d("Setting do not disturb to $enable")
                if (enable) {
                    notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
                } else {
                    notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                }
            } else {
                Timber.w("Unable to set do not disturb, notification policy access not granted")
                config.eventBroadcaster.notifyEvent(
                    Event(
                        "showToastMessage",
                        "",
                        "Unable to set do not disturb.  Permission not granted."
                    )
                )
            }
        }
    }
}
