package com.msp1974.vacompanion.device

import timber.log.Timber

enum class MotionDetectionMode(val key: String) {
    NONE("none"),
    MOTION("motion"),
    FACE("face");

    val usesCamera: Boolean get() = this == MOTION || this == FACE
    val isActive: Boolean get() = this != NONE

    companion object {
        fun fromKey(key: String): MotionDetectionMode {
            return entries.find { it.key == key } ?: run {
                Timber.w("Unknown motion detection mode key '$key' — falling back to NONE")
                NONE
            }
        }
    }
}
