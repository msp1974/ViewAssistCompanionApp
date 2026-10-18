package com.msp1974.vacompanion.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
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
    /**
     * The id of the output playback is actually going to, or -1 if it can't be told. That is the
     * selected device when one is set and connected; on automatic it is whatever Android routes
     * media to (asked directly on Android 13+, otherwise worked out in Android's usual priority).
     */
    fun activeOutputId(context: Context, config: APPConfig): Int {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

        val selected = config.audioOutputId
        if (selected >= 0 && outputs.any { it.id == selected }) return selected

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
            audioManager.getAudioDevicesForAttributes(attributes).firstOrNull()?.let { return it.id }
        }

        val priority = listOf(
            listOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES),
            listOf(AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE),
            listOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP),
            listOf(AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC),
            listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE),
        )
        for (types in priority) {
            outputs.firstOrNull { it.type in types }?.let { return it.id }
        }
        return -1
    }

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
