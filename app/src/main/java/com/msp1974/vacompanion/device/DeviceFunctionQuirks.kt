package com.msp1974.vacompanion.device

import android.os.Build

enum class FunctionClasses {
    AUDIO_ENHANCEMENTS,
    CAMERA_DETECTION,
}

class Device(
    var make: String,
    var model: String,
    var quirks: List<FunctionClasses>,
)

class DeviceFunctionQuirks {

    companion object {
        private val quirks = listOf(
            Device(
                make = "lenovo",
                model = "tb-8505fs",
                quirks = listOf(FunctionClasses.AUDIO_ENHANCEMENTS)
            ),
            // Facebook Portal family. CameraX 1.6.x's validator rejects Portal's
            // virtual camera (Cams:0); pixel-diff and face detection are both
            // unreachable until a CameraX-compatible workaround lands. Aloha
            // presence (via PortalPresenceMonitor) is the supported substitute.
            // Verified on-device: cipher (Portal+).
            // Inferred from shared Aloha architecture (untested): ranger
            // (Portal Go), shrek (Portal), anteater (Portal Mini), rosie
            // (Portal TV — no front camera anyway).
            Device(make = "facebook", model = "cipher",   quirks = listOf(FunctionClasses.CAMERA_DETECTION)),
            Device(make = "facebook", model = "ranger",   quirks = listOf(FunctionClasses.CAMERA_DETECTION)),
            Device(make = "facebook", model = "shrek",    quirks = listOf(FunctionClasses.CAMERA_DETECTION)),
            Device(make = "facebook", model = "anteater", quirks = listOf(FunctionClasses.CAMERA_DETECTION)),
            Device(make = "facebook", model = "rosie",    quirks = listOf(FunctionClasses.CAMERA_DETECTION)),
        )


        fun isUnsupported(functionClass: FunctionClasses): Boolean {
            val manufacturer = Build.MANUFACTURER.orEmpty().lowercase()
            val model = Build.MODEL.orEmpty().lowercase()
            // Build.DEVICE is the manufacturer's internal codename (e.g. "cipher"
            // for Portal+); Build.MODEL is the marketing name (e.g. "Portal+").
            // Match either so quirk entries can use whichever is more stable.
            val codename = Build.DEVICE.orEmpty().lowercase()

            for (device in quirks) {
                if (functionClass in device.quirks) {
                    if (manufacturer == device.make && (model == device.model || codename == device.model)) {
                        return true
                    }
                }
            }
            return false
        }
    }
}
