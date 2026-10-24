package com.msp1974.vacompanion.ui.layouts.config

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import com.msp1974.vacompanion.ui.VAViewModel
import kotlin.math.roundToInt

private const val APPEARANCE = "Appearance"
private const val BRIGHTNESS = "Brightness"
private const val SCREEN = "Screen"

fun displayConfig(vm: VAViewModel): ConfigSection {
    val c = vm.config
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
        )
    )
}
