package com.msp1974.vacompanion.device.camera

import android.os.Build

/**
 * Per-model corrections for cameras that don't behave the way Android says they should. Every
 * device's camera is driven through raw Camera2 by [Camera2DirectSource] (CameraX's own camera
 * enumeration rejects some, such as the Facebook Portal family and the Echo Show 5, even though
 * they work fine via Camera2).
 *
 * How the picture is turned and shaped is worked out in [CameraOrientation], by one rule for every
 * camera but the Portal's: the frame holds the picture as the device is naturally held, so it is
 * turned by minus the display rotation, and is stretched (squeezed) into the sensor's landscape
 * buffer when the device's natural orientation is portrait. Worked out on the Lenovo Smart Display
 * and Samsung Galaxy Tab A9+, whose cameras report a sensor orientation of 270 that is no use here,
 * and that fits the Echo Shows too.
 */
object DeviceCameraQuirks {

    private fun device(): Pair<String, String> =
        Build.MANUFACTURER.orEmpty().lowercase() to Build.DEVICE.orEmpty().lowercase()

    // Facebook Portal family. `device` matches Build.DEVICE - the manufacturer's internal codename -
    // not Build.MODEL, since several Portal hardware revisions share a marketing name ("Portal+")
    // but have different codenames. Their camera HAL updates the sensor orientation itself as the
    // device is turned, so unlike other cameras it already allows for the display rotation.
    //
    // Verified on-device for "aloha" (1st-gen Portal+, Android 9 / API 27, Snapdragon 835): the
    // camera list holds a single front camera, id "0".
    private val sensorTracksDisplayDevices = listOf("aloha", "ohana", "ripley", "omni", "atlas", "cipher")

    /** The camera's reported sensor orientation already allows for how the device is turned, so
     *  the display rotation must not be added to it. */
    fun sensorTracksDisplay(): Boolean {
        val (make, device) = device()
        return make == "facebook" && device in sensorTracksDisplayDevices
    }
}
