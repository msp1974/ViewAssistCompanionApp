package com.msp1974.vacompanion.device

import android.os.Build

/**
 * A device-specific behavioral marker. Implementations are sealed data objects
 * (or data classes if they carry parameters) declared at the top of this file.
 *
 * @property wireName the string surfaced to HA in the `unsupported_functions`
 *   capabilities payload so the integration's UI can hide affected features.
 *   `null` means this quirk is an internal routing hint only and never
 *   crosses the wire.
 */
sealed interface Quirk {
    val wireName: String?
}

// User-facing quirks — surfaced to HA so its UI can hide affected features.

/** Built-in audio enhancements (AGC/AEC/NS) are broken or counterproductive on this device. */
data object AudioEnhancementsBroken : Quirk {
    override val wireName = "AUDIO_ENHANCEMENTS"
}

// Internal routing hints — Camera.kt et al consult these to pick code paths.

/**
 * The available camera reports non-standard `CameraCharacteristics` that
 * CameraX 1.6+ rejects (e.g. Portal+ reports LENS_FACING=BACK on its only
 * camera while declaring the front-camera device feature). Route through
 * `DirectCameraSource`, which opens a hardcoded camera id via Camera2.
 */
data object CameraDirectAPI : Quirk {
    override val wireName: String? = null
}

object DeviceFunctionQuirks {

    private class Entry(val make: String, val model: String, val quirks: Set<Quirk>)

    private val entries = listOf(
        Entry("lenovo", "tb-8505fs", setOf(AudioEnhancementsBroken)),

        // Facebook Portal family. The virtual camera reports LENS_FACING=BACK
        // while the device declares android.hardware.camera.front, which
        // CameraX 1.6's CameraValidator rejects. DirectCameraSource opens the
        // virtual camera (id=0) via raw Camera2, skipping the validator.
        // Verified on-device: cipher (Portal+).
        // Inferred from shared Aloha architecture (untested): ranger
        // (Portal Go), shrek (Portal), anteater (Portal Mini)
        Entry("facebook", "cipher",   setOf(CameraDirectAPI)),
        Entry("facebook", "ranger",   setOf(CameraDirectAPI)),
        Entry("facebook", "shrek",    setOf(CameraDirectAPI)),
        Entry("facebook", "anteater", setOf(CameraDirectAPI)),
    )

    /**
     * All quirks declared for the current device. Empty for unknown devices —
     * callers can do `X in DeviceFunctionQuirks.quirks` without nullable checks.
     */
    val quirks: Set<Quirk> by lazy {
        val manufacturer = Build.MANUFACTURER.orEmpty().lowercase()
        val model = Build.MODEL.orEmpty().lowercase()
        // Build.DEVICE is the manufacturer's internal codename (e.g. "cipher"
        // for Portal+); Build.MODEL is the marketing name. Match either.
        val codename = Build.DEVICE.orEmpty().lowercase()
        entries.firstOrNull {
            it.make == manufacturer && (it.model == model || it.model == codename)
        }?.quirks ?: emptySet()
    }

    /**
     * Wire-format names of the user-facing subset of [quirks]. Surfaced to HA
     * in the `unsupported_functions` capabilities payload.
     */
    fun userFacingWireNames(): List<String> = quirks.mapNotNull { it.wireName }
}
