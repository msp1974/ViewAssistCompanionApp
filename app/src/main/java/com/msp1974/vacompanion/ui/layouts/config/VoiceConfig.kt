package com.msp1974.vacompanion.ui.layouts.config

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import com.msp1974.vacompanion.audio.MicLevelMonitor
import com.msp1974.vacompanion.ui.VAViewModel

private const val WAKE = "Wake word"

fun voiceConfig(vm: VAViewModel): ConfigSection {
    val c = vm.config
    return ConfigSection(
        "Voice Assistant", Icons.Default.Mic, listOf(
            ConfigItem.Options(WAKE, "Detection engine", null, Icons.Default.Memory,
                key = "wake_word_engine",
                options = friendlyOptions("microwakeword", "openwakeword", "openwakeword_rt"),
                get = { c.wakeWordEngine }, set = { c.wakeWordEngine = it }),
            ConfigItem.Options(WAKE, "Wake word", "Choices depend on the detection engine", Icons.Default.RecordVoiceOver,
                key = "wake_word",
                optionsProvider = {
                    val engine = c.wakeWordEngine.uppercase()
                    c.availableWakeWords?.get(engine).orEmpty().map { ConfigOption(it.id, friendly(it.id)) }
                        .ifEmpty { listOf(ConfigOption(c.wakeWord, friendly(c.wakeWord))) }
                },
                get = { c.wakeWord }, set = { c.wakeWord = it }),
            ConfigItem.FloatValue(WAKE, "Detection threshold", "How sure the device must be before it responds",
                Icons.Default.Tune, key = "wake_word_threshold", min = 1f, max = 10f, step = 0.5f,
                get = { c.wakeWordThreshold * 10 }, set = { c.wakeWordThreshold = it / 10f }),
            ConfigItem.IntValue(WAKE, "Minimum wake word level",
                "Ignore a wake word said more quietly than this, such as a whisper. Measured at the microphone before any gain is applied, in dB below full scale, so closer to 0 is louder. The lowest value turns it off",
                Icons.Default.VoiceOverOff, key = "wake_word_min_level",
                min = MicLevelMonitor.MIN_LEVEL_OFF_DBFS, max = -20, unit = " dB",
                get = { c.wakeWordMinLevel }, set = { c.wakeWordMinLevel = it }),
            ConfigItem.Options(WAKE, "Wake sound", "Sound played when the wake word is heard", Icons.Default.MusicNote,
                key = "wake_word_sound",
                optionsProvider = {
                    listOf(ConfigOption("none", "None")) +
                        c.availableWakeSounds.map { ConfigOption(it.id, it.name) }
                },
                get = { c.wakeWordSound }, set = { c.wakeWordSound = it }),
            ConfigItem.Toggle(WAKE, "Suppress wake sound",
                "Turn the microphone right down while the wake sound plays. Use this when the sound is a spoken one, such as 'how can i help?', so it isn't heard as part of your command",
                Icons.Default.MicOff, key = "suppress_wake_sound",
                get = { c.suppressWakeSound }, set = { c.suppressWakeSound = it }),
            ConfigItem.Toggle(WAKE, "Continue conversation",
                "Keep listening after a reply always.  When off the assistant will keep listening for a response to a question only", Icons.Default.Forum,
                key = "continue_conversation", get = { c.continueConversation }, set = { c.continueConversation = it }),
        )
    )
}
