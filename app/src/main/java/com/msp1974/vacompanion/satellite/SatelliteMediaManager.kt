package com.msp1974.vacompanion.satellite

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import com.msp1974.vacompanion.players.AlarmService
import com.msp1974.vacompanion.players.AnnouncementPlayer
import com.msp1974.vacompanion.players.SoundEffectsPlayer
import com.msp1974.vacompanion.players.MusicPlayerService
import com.msp1974.vacompanion.players.VoicePlayerService
import com.msp1974.vacompanion.settings.APPConfig
import timber.log.Timber

@SuppressLint("UnsafeOptInUsageError")
class SatelliteMediaManager(val context: Context, val config: APPConfig) {
    val soundPlayer = SoundEffectsPlayer(context)
    val voicePlayer = VoiceManager(context)
    val musicPlayer = MusicManager(context)
    val alarmPlayer = AlarmManager(context)
    val announcementPlayer = AnnouncementPlayer(context)

    suspend fun stopAll() {
        Timber.d("Stopping media manager")
        soundPlayer.stop()
        voicePlayer.stop()
        musicPlayer.stop()
        announcementPlayer.stop()
        alarmPlayer.stop()
    }

    class AlarmManager(val context: Context) {
        val alarmService = Intent(context, AlarmService::class.java)

        fun start(uri: String) {
            alarmService.putExtra("uri", uri)
            context.startService(alarmService)
        }

        fun stop() {
            context.stopService(alarmService)
        }

        fun isSounding(): Boolean {
            return AlarmService.sInstance != null
        }
    }

    class MusicManager(val context: Context) {
        val musicService = Intent(context, MusicPlayerService::class.java)

        fun play(url: String, volume: Float) {
            musicService.putExtra("url", url)
            musicService.putExtra("volume", volume)
            context.startService(musicService)
        }

        fun pause() {
            MusicPlayerService.sInstance?.pause()
        }

        fun resume() {
            MusicPlayerService.sInstance?.resume()
        }

        fun stop() {
            context.stopService(musicService)
        }

        fun setVolume(volume: Float) {
            MusicPlayerService.sInstance?.setVolume(volume)
        }
    }


    class VoiceManager(val context: Context) {
        val voiceService = Intent(context, VoicePlayerService::class.java)

        fun start() {
            if (!isRunning()) {
                context.startService(voiceService)
            }
        }

        fun play(rate: Int, width: Int, channels: Int) {
            VoicePlayerService.sInstance?.start(rate, width, channels)
        }

        fun requestAudioFocus() {
            VoicePlayerService.sInstance?.hasAudioFocus?.let {
                if (!it) {
                    VoicePlayerService.sInstance?.requestAudioFocus()
                }
            }
        }

        fun abandonAudioFocus() {
            VoicePlayerService.sInstance?.hasAudioFocus?.let {
                if (it) {
                    VoicePlayerService.sInstance?.abandonAudioFocus()
                }
            }
        }

        fun flush() {
            if (isRunning()) {
                VoicePlayerService.sInstance?.stop(false)
            }
        }

        fun forceStop() {
            if (isRunning()) {
                VoicePlayerService.sInstance?.stop(true)
            }
        }

        fun stop() {
            try {
                context.stopService(voiceService)
            } catch (e: Exception) {
                Timber.e(e)
            }
        }

        fun writeData(data: ByteArray) {
            VoicePlayerService.sInstance?.writeAudio(data)
        }

        fun isReady(): Boolean {
            return try {
                VoicePlayerService.sInstance!!.isReady
            } catch (e: Exception) {
                false
            }
        }

        fun isPlaying(): Boolean {
            return try {
                VoicePlayerService.sInstance!!.isPlaying
            } catch (e: Exception) {
                false
            }
        }

        fun isRunning(): Boolean {
            return VoicePlayerService.sInstance != null
        }

    }
}