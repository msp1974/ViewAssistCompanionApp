package com.msp1974.vacompanion.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import com.msp1974.vacompanion.R
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.utils.Logger
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import androidx.core.net.toUri


class Alarm(val context: Context) {
    private val log = Logger()
    private val config: APPConfig = APPConfig.getInstance(context)

    private var currentVolume: Int = config.musicVolume
    var isVolumeDucked: Boolean = false
    var isSounding: Boolean = false
    var mediaPlayer: MediaPlayer? = null
    var maxVolume: Int = AudioManager(context).getStreamMaxVolume(android.media.AudioManager.STREAM_NOTIFICATION)

    fun startAlarm(url: String = "") {
        if (mediaPlayer == null) {
            mediaPlayer = MediaPlayer().apply {
                if (url != "") {
                    setDataSource(url)
                } else {
                    val mediaPath =
                        ("android.resource://" + context.packageName + "/" + R.raw.alarm_sound).toUri()
                    setDataSource(context, mediaPath)
                }
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .build()
                )
            }
            mediaPlayer?.prepare()
            mediaPlayer?.isLooping = true
            mediaPlayer?.start()
            isSounding = true
            stopOnTimeout(10)
        }
    }

    fun stopAlarm() {
        if (mediaPlayer != null && mediaPlayer!!.isPlaying) {
            mediaPlayer!!.stop()
            isSounding = false
            mediaPlayer!!.reset()
            mediaPlayer!!.release()
            mediaPlayer = null
        }
    }

    fun stopOnTimeout(timeout: Long) {
        Executors.newSingleThreadScheduledExecutor().schedule({
            stopAlarm()
        }, timeout, TimeUnit.MINUTES)
    }

    fun duckVolume() {
        if (mediaPlayer != null && !isVolumeDucked) {
            if (mediaPlayer!!.isPlaying) {
                val vol = config.duckingVolume
                if (vol < config.musicVolume) {
                    log.d("Ducking Alarm volume from $currentVolume to $vol")
                    mediaPlayer!!.setVolume((vol / maxVolume).toFloat(), (vol / maxVolume).toFloat())
                    isVolumeDucked = true
                } else {
                    log.d("Not ducking Alarm volume as it is lower than ducking volume of ${config.duckingVolume} at ${config.musicVolume}")
                }
            }
        }
    }

    fun unDuckVolume() {
        if (mediaPlayer != null && isVolumeDucked) {
            log.i("Restoring Alarm volume to ${currentVolume}")
            thread(name="alarmVolumeUnducking") {
                val steps = 3
                val diffStepVolume = (currentVolume - config.duckingVolume) / steps
                for (i in 1..steps) {
                    val vol = config.duckingVolume + (diffStepVolume * i)
                    mediaPlayer!!.setVolume((vol / maxVolume).toFloat(), (vol / maxVolume).toFloat())
                    if (i < steps) { Thread.sleep(250) }
                }
            }
            isVolumeDucked = false
        }
    }
}