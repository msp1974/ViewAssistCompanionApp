package com.msp1974.vacompanion.ui.layouts.config

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import com.msp1974.vacompanion.audio.MicGate
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.ui.VAViewModel

private const val VOICE = "Voice assistant"

// Features that are still being tried out, so that each can be switched off if it causes trouble.
fun experimentalConfig(vm: VAViewModel): ConfigSection {
    val c = vm.config
    return ConfigSection(
        "Experimental", Icons.Default.Science, listOf(
            ConfigItem.Toggle(VOICE, "Suppress wake sound",
                "Turn the microphone right down while the wake sound plays. Use this when the sound is a spoken one, such as 'how can i help?', so it isn't heard as part of your command",
                Icons.Default.MicOff, key = "suppress_wake_sound",
                get = { c.suppressWakeSound }, set = { c.suppressWakeSound = it }),
            ConfigItem.IntValue(VOICE, "Minimum microphone level",
                "Keep the microphone silent until it hears something louder than this, so whispers and quiet background sound are ignored. Measured on the raw microphone signal in dB below full scale, so closer to 0 is louder. The lowest value turns it off",
                Icons.Default.VoiceOverOff, key = "mic_min_level",
                min = MicGate.OFF_DBFS, max = MicGate.MAX_DBFS, unit = " dB",
                get = { c.micMinLevel }, set = { c.micMinLevel = it }),
            ConfigItem.Action(VOICE, "Microphone calibration",
                "Say the wake word in your normal voice so this device knows how loud you are. Several devices can then be compared when they all hear you. Do it on each device, from the same distance and in the same voice",
                Icons.Default.Tune,
                value = { if (c.wakeLevelCalibrated) "%+.1f dB".format(c.wakeLevelOffsetDb) else "Not calibrated" },
                onClick = { vm.openCalibrationWizard() }),
            ConfigItem.Toggle(VOICE, "Wake word arbitration",
                "When several devices hear the same wake word, they agree which one answers. Off, this device answers every wake word it hears, whatever the others do",
                Icons.Default.HowToVote, key = "wake_word_arbitration",
                get = { c.wakeWordArbitrationEnabled }, set = { c.wakeWordArbitrationEnabled = it }),
            ConfigItem.Options(VOICE, "Ducking scope",
                "Which devices lower their music when this one answers, and whose answers lower this one's: those in the same Home Assistant area, those set to the same custom zone, or none. Disabled, this device neither ducks the others nor is ducked by them",
                Icons.Default.Hub, key = "duck_scope",
                options = listOf(
                    ConfigOption(APPConfig.DUCK_SCOPE_AREA, "Area"),
                    ConfigOption(APPConfig.DUCK_SCOPE_ZONE, "Custom zone"),
                    ConfigOption(APPConfig.DUCK_SCOPE_DISABLED, "Disabled"),
                ),
                get = { c.duckScope }, set = { c.duckScope = it }),
            ConfigItem.Options(VOICE, "Custom ducking zone", "Devices set to the same zone duck together",
                Icons.Default.Hub, key = "duck_zone",
                options = APPConfig.DUCK_ZONES.map { ConfigOption(it, it) },
                get = { c.duckZone }, set = { c.duckZone = it })
                .enabledWhen { c.duckScope == APPConfig.DUCK_SCOPE_ZONE },
        )
    )
}
