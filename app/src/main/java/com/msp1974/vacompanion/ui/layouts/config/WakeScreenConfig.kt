package com.msp1974.vacompanion.ui.layouts.config

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.*
import com.msp1974.vacompanion.ui.VAViewModel

private const val WAKE_ON = "Wake on"
private const val SENSITIVITY = "Sensitivity"

fun wakeScreenConfig(vm: VAViewModel): ConfigSection {
    val c = vm.config
    return ConfigSection(
        "Wake the Screen", Icons.Default.WbSunny, listOf(
            ConfigItem.Toggle(WAKE_ON, "Wake word", null, Icons.Default.RecordVoiceOver,
                key = "screen_on_wake_word", get = { c.screenOnWakeWord }, set = { c.screenOnWakeWord = it }),
            ConfigItem.Toggle(WAKE_ON, "Bump", null, Icons.Default.Vibration,
                key = "screen_on_bump", get = { c.screenOnBump }, set = { c.screenOnBump = it }),
            ConfigItem.Toggle(WAKE_ON, "Proximity", null, Icons.Default.Sensors,
                key = "screen_on_proximity", get = { c.screenOnProximity }, set = { c.screenOnProximity = it }),
            ConfigItem.Toggle(WAKE_ON, "Motion", null, Icons.AutoMirrored.Filled.DirectionsRun,
                key = "screen_on_motion", get = { c.screenOnMotion }, set = { c.screenOnMotion = it }),

            ConfigItem.FloatValue(SENSITIVITY, "Bump sensitivity", null, Icons.Default.Vibration,
                key = "bump_sensitivity", min = 0.5f, max = 10f, step = 0.5f,
                get = { c.bumpSensitivity * 10 }, set = { c.bumpSensitivity = it / 10f }),
            ConfigItem.Info(SENSITIVITY, "Proximity threshold", null, Icons.Default.Sensors,
                value = { c.rawProximitySensorThreshold.toString() }),
            ConfigItem.Options(SENSITIVITY, "Camera motion detection", null, Icons.Default.Videocam,
                key = "motion_detection_mode", options = friendlyOptions("none", "motion", "face"),
                get = { c.motionDetectionMode },
                set = { c.motionDetectionMode = it; c.enableMotionDetection = it != "none" }),
            ConfigItem.IntValue(SENSITIVITY, "Motion sensitivity", null, Icons.Default.Tune,
                key = "motion_detection_sensitivity", min = 0, max = 100,
                get = { c.motionDetectionSensitivity }, set = { c.motionDetectionSensitivity = it }),
        )
    )
}
