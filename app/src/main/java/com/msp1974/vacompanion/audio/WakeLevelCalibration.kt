package com.msp1974.vacompanion.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The logic of the microphone calibration wizard. It collects the wake word said [READINGS_NEEDED]
 * times in a normal voice and works out the **calibration offset**: how much to add to this device's
 * wake word level so it can be compared with other devices'. Microphones differ in sensitivity by more
 * than a change in distance does, so raw levels can't be compared as they are.
 *
 * While a wizard is running ([isActive]), wake word detections add their raw level here instead of
 * waking the device, so each reading is the same measurement later used to compare devices.
 */
class WakeLevelCalibration {

    enum class Phase { IDLE, LISTENING, DONE }

    data class State(
        val phase: Phase = Phase.IDLE,
        val readings: List<Float> = emptyList(),
    )

    /** What the readings come to. */
    class Summary(
        /** Median raw level of the readings. */
        val medianDb: Float,
        /** Gap between the quietest and loudest reading. */
        val spreadDb: Float,
        /** What to add to this device's raw level. */
        val offsetDb: Float,
    ) {
        /** False if the readings varied so much that the median can't be trusted. */
        val consistent: Boolean get() = spreadDb <= MAX_CONSISTENT_SPREAD_DB
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    val isActive: Boolean get() = _state.value.phase == Phase.LISTENING

    /** Starts, or restarts, collecting. */
    fun start() {
        _state.value = State(phase = Phase.LISTENING)
    }

    /** Stops collecting and forgets the readings. */
    fun cancel() {
        _state.value = State()
    }

    /** A wake word was heard at [rawDbfs]. Ignored unless collecting; stops by itself when done. */
    fun addReading(rawDbfs: Float) {
        _state.update { s ->
            if (s.phase != Phase.LISTENING) {
                s
            } else {
                val readings = s.readings + rawDbfs
                State(
                    phase = if (readings.size >= READINGS_NEEDED) Phase.DONE else Phase.LISTENING,
                    readings = readings,
                )
            }
        }
    }

    /** What the finished wizard comes to; null until it is done. */
    fun summary(): Summary? {
        val s = _state.value
        return if (s.phase == Phase.DONE && s.readings.isNotEmpty()) summarise(s.readings) else null
    }

    companion object {
        const val READINGS_NEEDED = 5

        /**
         * What a normal-voiced wake word at the calibration distance reads after calibration, on
         * every device. Only agreeing matters, not the value, so this is just a sensible level.
         */
        const val REFERENCE_DBFS = -30f

        /** The most the offset may be, so a bad reading can't make a device wildly off. */
        const val MAX_OFFSET_DB = 40f

        /** Readings further apart than this suggest the voice or distance wasn't steady. */
        const val MAX_CONSISTENT_SPREAD_DB = 6f

        /** The offset that brings the median of [readings] to [REFERENCE_DBFS]. Needs at least one. */
        fun summarise(readings: List<Float>): Summary {
            val sorted = readings.sorted()
            val mid = sorted.size / 2
            val median = if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2f
            return Summary(
                medianDb = median,
                spreadDb = sorted.last() - sorted.first(),
                offsetDb = (REFERENCE_DBFS - median).coerceIn(-MAX_OFFSET_DB, MAX_OFFSET_DB),
            )
        }
    }
}
