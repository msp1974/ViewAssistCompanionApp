package com.msp1974.vacompanion.device

import android.os.Build

enum class FunctionClasses {
    AUDIO_ENHANCEMENTS
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
            )
        )


        fun isUnsupported(functionClass: FunctionClasses): Boolean {
            val manufacturer = Build.MANUFACTURER.orEmpty().lowercase()
            val model = Build.MODEL.orEmpty().lowercase()

            for (device in quirks) {
                if (functionClass in device.quirks) {
                    if (manufacturer == device.make && model == device.model) {
                        return true
                    }
                }
            }
            return false
        }
    }
}
