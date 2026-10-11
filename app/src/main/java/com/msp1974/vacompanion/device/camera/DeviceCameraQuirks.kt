package com.msp1974.vacompanion.device.camera

import android.os.Build

/**
 * Devices where CameraX's own camera enumeration/validation rejects the camera even though it
 * works fine via raw Camera2 - confirmed by an on-device probe for the Facebook Portal family:
 * CameraX fails with `CXCP: Failed to query camera ID list` / `CameraValidator: Camera
 * LENS_FACING_FRONT verification failed` / `CameraUnavailableException: Available cameras: 0`,
 * while `android.hardware.camera2.CameraManager.openCamera()` plus a capture session opens and
 * streams frames without issue from the app's own foreground process.
 *
 * [CameraController] routes matching devices through [Camera2DirectSource] instead of CameraX.
 */
object DeviceCameraQuirks {

    private data class Entry(val make: String, val device: String, val cameraId: String)

    // `device` matches Build.DEVICE - the manufacturer's internal codename - not Build.MODEL,
    // since several Portal hardware revisions share a marketing name ("Portal+") but have
    // different codenames and may not share this defect.
    //
    // Verified on-device for "aloha" (1st-gen Portal+, Android 9 / API 27, Snapdragon 835) via a
    // direct Camera2 open+capture probe: getCameraIdList() returns a single camera, id "0",
    // correctly reporting LENS_FACING_FRONT.
    private val entries = listOf(
        Entry(make = "facebook", device = "aloha", cameraId = "0"),
        Entry(make = "facebook", device = "ohana", cameraId = "0"),
        Entry(make = "facebook", device = "ripley", cameraId = "0"),
        Entry(make = "facebook", device = "omni", cameraId = "0"),
        Entry(make = "facebook", device = "atlas", cameraId = "0"),
        Entry(make = "facebook", device = "cipher", cameraId = "0"),
    )

    /** The camera id to open directly via Camera2 on this device, or null for every other
     *  device (which should keep using the normal CameraX path). */
    fun directCameraId(): String? {
        val manufacturer = Build.MANUFACTURER.orEmpty().lowercase()
        val device = Build.DEVICE.orEmpty().lowercase()
        return entries.firstOrNull { it.make == manufacturer && it.device == device }?.cameraId
    }
}
