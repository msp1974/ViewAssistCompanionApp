package com.msp1974.vacompanion.ui.layouts.config

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import android.app.Application
import com.msp1974.vacompanion.ui.VAViewModel
import com.msp1974.vacompanion.audio.MicrophoneInput

private const val OUTPUT = "Output"
private const val VOLUME = "Volume"
private const val ALARMS = "Alarms"
private const val CAPABILITIES = "Audio Capabilities"

fun audioConfig(vm: VAViewModel): ConfigSection {
    val c = vm.config
    val hardware = vm.deviceInfo.hardware
    val context = vm.getApplication<Application>()
    val features = vm.deviceInfo.features.audio
    val audioEnhancementsSupported =
        features.autoGainControl || features.noiseSuppression || features.acousticEchoCancellation
    return ConfigSection(
        "Audio", Icons.AutoMirrored.Filled.VolumeUp, listOf(
            ConfigItem.Options(OUTPUT, "Speaker", null, Icons.Default.Speaker, key = "audio_output",
                optionsProvider = {
                    listOf(ConfigOption("-1", "Automatic")) +
                        hardware.audioOutputs.map { ConfigOption(it.id.toString(), "${it.productName} (${it.typeName})") }
                },
                get = { c.audioOutputId.toString() }, set = { c.audioOutputId = it.toInt() },
                toHaValue = { it.toInt() }),

            // These follow the Android volume controls, so they are shown rather than set here.
            ConfigItem.Info(VOLUME, "Notification volume", null, Icons.Default.Notifications,
                value = { c.notificationVolume.toString() }),
            ConfigItem.Info(VOLUME, "Music volume", null, Icons.Default.MusicNote,
                value = { c.musicVolume.toString() }),
            ConfigItem.Info(VOLUME, "Alarm volume", null, Icons.Default.Alarm,
                value = { c.alarmVolume.toString() }),
            ConfigItem.IntValue(VOLUME, "Ducking volume", "Music volume while the assistant is speaking",
                Icons.AutoMirrored.Filled.VolumeDown, key = "ducking_volume", min = 0, max = 50,
                get = { c.duckingVolume }, set = { c.duckingVolume = it }),
            ConfigItem.Info(VOLUME, "Media player gain", null, Icons.Default.GraphicEq,
                value = { c.mediaPlayerGain.toString() }),

            ConfigItem.Options(ALARMS, "Alarm sound", null, Icons.Default.Alarm, key = "alarm_sound",
                optionsProvider = {
                    c.availableAlarms.map { ConfigOption(it.id, it.name) }
                        .ifEmpty { listOf(ConfigOption(c.alarmSound, friendly(c.alarmSound))) }
                },
                get = { c.alarmSound }, set = { c.alarmSound = it }),

            ConfigItem.Info(OUTPUT, "Active Audio Output", null, Icons.AutoMirrored.Filled.VolumeUp,
                value = { vm.vacaState.value.audioOutputId; activeOutputLabel(context, c) }),

            ConfigItem.Info(CAPABILITIES, "Audio Enhancements Supported", null, Icons.Default.GraphicEq,
                value = { yesNo(audioEnhancementsSupported) },
                valueColor = { yesNoColor(audioEnhancementsSupported) }),
            ConfigItem.Info(CAPABILITIES, "Automatic Gain Control", null, Icons.Default.Equalizer,
                value = { enhancementSourceLabel(MicrophoneInput.agcSource) },
                valueColor = { enhancementSourceColor(MicrophoneInput.agcSource) }),
            ConfigItem.Info(CAPABILITIES, "Noise Suppression", null, Icons.Default.NoiseAware,
                value = { enhancementSourceLabel(MicrophoneInput.nsSource) },
                valueColor = { enhancementSourceColor(MicrophoneInput.nsSource) }),
            ConfigItem.Info(CAPABILITIES, "Echo Cancellation", null, Icons.Default.PhoneInTalk,
                value = { enhancementSourceLabel(MicrophoneInput.aecSource) },
                valueColor = { enhancementSourceColor(MicrophoneInput.aecSource) }),
            ConfigItem.Info(CAPABILITIES, "Active Microphone", null, Icons.Default.Mic,
                value = { vm.vacaState.value.sensorState.micInput?.activeInput ?: "Unknown" }),
            ConfigItem.Info(CAPABILITIES, "Available Microphones", null, Icons.Default.SettingsVoice,
                value = {
                    vm.vacaState.value.sensorState.micInput?.availableInputs?.joinToString(", ") ?: "None"
                }),
        )
    )
}
