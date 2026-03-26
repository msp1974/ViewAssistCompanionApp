package com.msp1974.vacompanion.wakeword.openwakeword.model

/**
 * Represents real-time wake word inference scores.
 * 
 * This data class is emitted through a Flow for continuous monitoring
 * of wake word detection scores, regardless of whether they exceed the threshold.
 * 
 * @property model The wake word model being evaluated
 * @property score The current inference score (0.0 to 1.0)
 * @property timestamp The time when this score was calculated
 */
data class WakeWordScore(
    val model: WakeWordModel,
    val score: Float,
    val timestamp: Long = System.currentTimeMillis()
)