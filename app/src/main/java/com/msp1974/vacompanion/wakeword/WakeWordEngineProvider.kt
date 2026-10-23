package com.msp1974.vacompanion.wakeword

import kotlinx.coroutines.flow.Flow

abstract class WakeWordEngineProvider {

    sealed class AudioResult {
        data class EngineStatus(val status: String): AudioResult()
        data class Audio(val audio: ShortArray, val timestamp: Long = System.currentTimeMillis()) : AudioResult()
        data class AudioLevel(val level: Float): AudioResult()
        data class WakeDetected(val detection: WakeWordDetection) : AudioResult()
        data class StopDetected(val detection: WakeWordDetection) : AudioResult()
    }

    data class WakeWordDetection(
        val wakeWordId: String,
        val wakeWord: String,
        val detected: Boolean,
        val score: Float,
        var vadScore: Float = 1.0f,
        val timestamp: Long = System.currentTimeMillis()
    )

    open fun start(): Flow<AudioResult>? {
        return null
    }
    open fun setActiveWakeWords(value: List<String>) {}

    open fun setActiveStopWords(value: List<String>) {}

    open fun setMuted(value: Boolean) {}

    open fun isMuted(): Boolean {
        return false
    }

    open fun release() {}

}

