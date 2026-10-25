package com.msp1974.vacompanion.ui.layouts.config

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.*
import com.msp1974.vacompanion.ui.VAViewModel
import kotlin.math.roundToInt

private const val APPEARANCE = "Appearance"
private const val BRIGHTNESS = "Brightness"
private const val SCREEN = "Screen"
private const val WAKE = "Wake the screen"

fun displayConfig(vm: VAViewModel): ConfigSection {
    val c = vm.config
    val hardware = vm.deviceInfo.hardware
    val hasCamera = hardware.hasFrontCamera
    val hasAccelerometer = hardware.hasAccelerometer
    val hasProximity = hardware.hasProximitySensor
    return ConfigSection(
        "Display", Icons.Default.Brightness6, listOf(
            ConfigItem.Toggle(BRIGHTNESS, "Automatic brightness", null, Icons.Default.BrightnessAuto,
                key = "screen_auto_brightness", get = { c.screenAutoBrightness }, set = { c.screenAutoBrightness = it }),
            ConfigItem.IntValue(BRIGHTNESS, "Brightness", "Used when automatic brightness is off",
                Icons.Default.Brightness6, key = "screen_brightness", min = 0, max = 100, unit = "%",
                get = { (c.screenBrightness * 100).roundToInt() }, set = { c.screenBrightness = it / 100f }),

            ConfigItem.Toggle(SCREEN, "Always on", "Never switch the screen off", Icons.Default.LightMode,
                key = "screen_always_on", get = { c.screenAlwaysOn }, set = { c.screenAlwaysOn = it }),
            ConfigItem.IntValue(SCREEN, "Screen timeout", "How long before the screen switches off",
                Icons.Default.Timer, key = "screen_timeout", min = 10, max = 3600, step = 10, unit = " sec",
                get = { c.screenTimeout / 1000 }, set = { c.screenTimeout = it * 1000 }),
            ConfigItem.Options(APPEARANCE, "Screen orientation", null, Icons.Default.ScreenRotation,
                key = "screen_orientation_mode",
                options = friendlyOptions("auto", "portrait", "landscape", "reverse_portrait", "reverse_landscape"),
                get = { c.screenOrientationMode }, set = { c.screenOrientationMode = it }),
            ConfigItem.Toggle(SCREEN, "Screen saver", "Show a blank screen instead of switching off",
                Icons.Default.Bedtime, key = "screen_saver", get = { c.screenSaver }, set = { c.screenSaver = it }),
            ConfigItem.Toggle(SCREEN, "Touch ends screen saver", null, Icons.Default.TouchApp,
                key = "screen_saver_disable_on_touch", get = { c.screenSaverDisableOnTouch },
                set = { c.screenSaverDisableOnTouch = it }),

            ConfigItem.Toggle(APPEARANCE, "Dark mode", null, Icons.Default.DarkMode,
                key = "dark_mode", get = { c.darkMode }, set = { c.darkMode = it }),
            ConfigItem.IntValue(APPEARANCE, "Text size", "Dashboard text size as a percentage (0 = default)",
                Icons.Default.TextFields, key = "text_size", min = 0, max = 300, step = 10, unit = "%",
                get = { c.textSize }, set = { c.textSize = it }),
            ConfigItem.IntValue(APPEARANCE, "Zoom level", "Dashboard zoom as a percentage (0 = fit to screen)",
                Icons.Default.ZoomIn, key = "zoom_level", min = 0, max = 300, step = 10, unit = "%",
                get = { c.zoomLevel }, set = { c.zoomLevel = it }),
            ConfigItem.Toggle(APPEARANCE, "Status bar", "Show the Android status bar over the dashboard",
                Icons.Default.Smartphone, key = "show_status_bar",
                get = { c.showStatusBar }, set = { c.showStatusBar = it }),

            ConfigItem.Toggle(WAKE, "On wake word", "Switch the screen on when the wake word is heard",
                Icons.Default.RecordVoiceOver, key = "screen_on_wake_word",
                get = { c.screenOnWakeWord }, set = { c.screenOnWakeWord = it }),

            ConfigItem.Toggle(WAKE, "On motion", "Switch the screen on when motion is detected",
                Icons.AutoMirrored.Filled.DirectionsRun, key = "screen_on_motion",
                get = { c.screenOnMotion }, set = { c.screenOnMotion = it })
                .enabledWhen { hasCamera },
            ConfigItem.Options(WAKE, "Camera motion detection", null, Icons.Default.Videocam,
                key = "motion_detection_mode", options = friendlyOptions("none", "motion", "face"),
                get = { c.motionDetectionMode },
                set = { c.motionDetectionMode = it; c.enableMotionDetection = it != "none" })
                .enabledWhen { hasCamera },
            ConfigItem.IntValue(WAKE, "Motion sensitivity", null, Icons.Default.Tune,
                key = "motion_detection_sensitivity", min = 0, max = 100,
                get = { c.motionDetectionSensitivity }, set = { c.motionDetectionSensitivity = it })
                .enabledWhen { hasCamera },

            ConfigItem.Toggle(WAKE, "On bump", "Switch the screen on when the device is bumped",
                Icons.Default.Vibration, key = "screen_on_bump",
                get = { c.screenOnBump }, set = { c.screenOnBump = it })
                .enabledWhen { hasAccelerometer },
            ConfigItem.FloatValue(WAKE, "Bump sensitivity", null, Icons.Default.Tune,
                key = "bump_sensitivity", min = 0.5f, max = 10f, step = 0.5f,
                get = { c.bumpSensitivity * 10 }, set = { c.bumpSensitivity = it / 10f })
                .enabledWhen { hasAccelerometer },

            ConfigItem.Toggle(WAKE, "On proximity", "Switch the screen on when something comes close",
                Icons.Default.Sensors, key = "screen_on_proximity",
                get = { c.screenOnProximity }, set = { c.screenOnProximity = it })
                .enabledWhen { hasProximity },
            ConfigItem.Info(WAKE, "Proximity threshold", null, Icons.Default.Tune,
                value = { c.rawProximitySensorThreshold.toString() })
                .enabledWhen { hasProximity },
        )
    )
}
