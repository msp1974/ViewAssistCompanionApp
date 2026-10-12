package com.msp1974.vacompanion.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.msp1974.vacompanion.settings.APPConfig
import timber.log.Timber

/**
 * Resolves [APPConfig.audioOutputId] (-1 = automatic, otherwise an [AudioDeviceInfo.getId])
 * into the actual device to route playback to. Used by every player - MusicPlayerService,
 * AlarmService, SoundEffectsPlayer, VoicePlayerService - via
 * `ExoPlayer.setPreferredAudioDevice()`/`AudioTrack.setPreferredDevice()`, so output routing
 * stays centralized and consistent rather than each player resolving the id separately.
 */
object AudioOutputRouter {

    /** Null means "automatic" - either the setting is -1, or the configured device isn't
     *  currently connected, in which case this logs and falls back to automatic rather than
     *  silently routing nowhere. */
    fun resolvePreferredDevice(context: Context, config: APPConfig): AudioDeviceInfo? {
        val id = config.audioOutputId
        if (id < 0) return null

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val device = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == id }
        if (device == null) {
            Timber.w("AudioOutputRouter: configured output id=$id not currently connected - falling back to automatic")
        }
        return device
    }
}
