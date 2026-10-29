package com.msp1974.vacompanion.ui.layouts.config

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import android.app.Application
import android.content.Context
import androidx.compose.ui.graphics.vector.ImageVector
import android.media.AudioManager
import android.os.Build
import com.msp1974.vacompanion.ui.VAViewModel
import com.msp1974.vacompanion.audio.MicrophoneInput
import com.msp1974.vacompanion.settings.APPConfig

private const val DEVICES = "Audio Devices"
private const val MIC = "Microphone"
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
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // Sliders run over the stream's own range, as the device's volume controls do.
    fun volumeItem(label: String, icon: ImageVector, key: String, stream: Int, get: () -> Int, set: (Int) -> Unit) =
        ConfigItem.IntValue(VOLUME, label, null, icon, key = key,
            min = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) audioManager.getStreamMinVolume(stream) else 0,
            max = audioManager.getStreamMaxVolume(stream),
            get = get, set = set)

    return ConfigSection(
        "Audio", Icons.AutoMirrored.Filled.VolumeUp, listOf(
            ConfigItem.Options(DEVICES, "Microphone", null, Icons.Default.Mic, key = "audio_input",
                optionsProvider = {
                    listOf(ConfigOption("-1", "Automatic")) +
                        hardware.audioInputs.map { ConfigOption(it.id.toString(), "${it.productName} (${it.typeName})") }
                },
                get = { c.audioInputId.toString() }, set = { c.audioInputId = it.toInt() },
                toHaValue = { it.toInt() }),
            ConfigItem.Options(DEVICES, "Speaker", null, Icons.Default.Speaker, key = "audio_output",
                optionsProvider = {
                    listOf(ConfigOption("-1", "Automatic")) +
                        hardware.audioOutputs.map { ConfigOption(it.id.toString(), "${it.productName} (${it.typeName})") }
                },
                get = { c.audioOutputId.toString() }, set = { c.audioOutputId = it.toInt() },
                toHaValue = { it.toInt() }),

            ConfigItem.IntValue(MIC, "Microphone gain", "Boost or reduce the microphone level", Icons.Default.GraphicEq,
                key = "mic_gain", min = -10, max = 10, unit = " dB",
                get = { c.micGain }, set = { c.micGain = it }),
            ConfigItem.Toggle(MIC, "Muted", null, Icons.Default.MicOff,
                key = "mute", get = { c.isMuted }, set = { c.isMuted = it }),

            volumeItem("Alarm volume", Icons.Default.Alarm, "alarm_volume",
                AudioManager.STREAM_ALARM, get = { c.alarmVolume }, set = { c.alarmVolume = it }),
            volumeItem("Music volume", Icons.Default.MusicNote, "music_volume",
                AudioManager.STREAM_MUSIC, get = { c.musicVolume }, set = { c.musicVolume = it }),
            volumeItem("Voice volume", Icons.Default.Notifications, "notification_volume",
                AudioManager.STREAM_NOTIFICATION, get = { c.notificationVolume }, set = { c.notificationVolume = it }),
            ConfigItem.IntValue(VOLUME, "Ducking volume", "Music volume while the assistant is speaking",
                Icons.AutoMirrored.Filled.VolumeDown, key = "ducking_volume", min = 0, max = 50,
                get = { c.duckingVolume }, set = { c.duckingVolume = it }),
            ConfigItem.Options(VOLUME, "Ducking scope",
                "Which devices duck, and compete for the wake word, when this one is woken",
                Icons.Default.Hub, key = "duck_scope",
                options = listOf(
                    ConfigOption(APPConfig.DUCK_SCOPE_AREA, "Area"),
                    ConfigOption(APPConfig.DUCK_SCOPE_ZONE, "Custom zone"),
                ),
                get = { c.duckScope }, set = { c.duckScope = it }),
            ConfigItem.Options(VOLUME, "Custom ducking zone", "Devices set to the same zone duck together",
                Icons.Default.Hub, key = "duck_zone",
                options = APPConfig.DUCK_ZONES.map { ConfigOption(it, it) },
                get = { c.duckZone }, set = { c.duckZone = it })
                .enabledWhen { c.duckScope == APPConfig.DUCK_SCOPE_ZONE },

            ConfigItem.Options(ALARMS, "Alarm sound", null, Icons.Default.Alarm, key = "alarm_sound",
                optionsProvider = {
                    c.availableAlarms.map { ConfigOption(it.id, it.name) }
                        .ifEmpty { listOf(ConfigOption(c.alarmSound, friendly(c.alarmSound))) }
                },
                get = { c.alarmSound }, set = { c.alarmSound = it }),

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
        )
    )
}
