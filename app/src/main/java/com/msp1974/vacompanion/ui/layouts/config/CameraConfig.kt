package com.msp1974.vacompanion.ui.layouts.config

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import com.msp1974.vacompanion.ui.VAViewModel

private const val STREAM = "Video stream"
private const val SECURITY = "Security"

fun cameraConfig(vm: VAViewModel): ConfigSection {
    val c = vm.config
    return ConfigSection(
        "Camera Stream", Icons.Default.Videocam, listOf(
            ConfigItem.Toggle(STREAM, "Camera stream", "Share the camera as an RTSP stream", Icons.Default.Videocam,
                key = "rtsp_enabled", get = { c.rtspEnabled }, set = { c.rtspEnabled = it }),
            ConfigItem.IntValue(STREAM, "Port", null, Icons.Default.Numbers, key = "rtsp_port",
                min = 1024, max = 65535,
                get = { c.rtspPort }, set = { c.rtspPort = it }),
            ConfigItem.Options(STREAM, "Resolution", null, Icons.Default.HighQuality, key = "rtsp_resolution",
                options = listOf("480p", "720p", "1080p").map { ConfigOption(it, it) },
                get = { c.rtspResolution }, set = { c.rtspResolution = it }),
            ConfigItem.IntValue(STREAM, "Frame rate", null, Icons.Default.Speed, key = "rtsp_fps",
                min = 5, max = 30, unit = " fps", get = { c.rtspFps }, set = { c.rtspFps = it }),
            ConfigItem.IntValue(STREAM, "Bitrate", null, Icons.Default.NetworkCheck, key = "rtsp_bitrate",
                min = 500_000, max = 8_000_000, step = 100_000, unit = " bps",
                get = { c.rtspBitrate }, set = { c.rtspBitrate = it }),
            ConfigItem.FloatValue(STREAM, "Keyframe interval", null, Icons.Default.Timer,
                key = "rtsp_keyframe_interval", min = 0.1f, max = 10f, step = 0.1f, unit = " sec",
                get = { c.rtspKeyframeIntervalSecs }, set = { c.rtspKeyframeIntervalSecs = it }),
            ConfigItem.Options(STREAM, "Image rotation", "Use if the camera feed is the wrong way up",
                Icons.Default.ScreenRotation, key = "camera_rotation_offset",
                options = listOf(0, 90, 180, 270).map {
                    ConfigOption(it.toString(), if (it == 0) "Automatic" else "Automatic + $it°")
                },
                get = { c.cameraRotationOffset.toString() }, set = { c.cameraRotationOffset = it.toInt() },
                toHaValue = { it.toInt() }),
            ConfigItem.Toggle(STREAM, "Streaming icon", "Show an icon on screen while someone is watching",
                Icons.Default.Visibility, key = "show_rtsp_streaming_indicator",
                get = { c.showRtspStreamingIndicator }, set = { c.showRtspStreamingIndicator = it }),

            ConfigItem.Info(SECURITY, "Username", null, Icons.Default.Person,
                value = { c.rtspUsername.ifBlank { "None" } }),
            ConfigItem.Info(SECURITY, "Password", null, Icons.Default.Lock,
                value = { if (c.rtspPassword.isBlank()) "None" else "Set" }),
        )
    )
}
