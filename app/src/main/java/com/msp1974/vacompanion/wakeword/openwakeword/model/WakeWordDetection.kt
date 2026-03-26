package com.msp1974.vacompanion.wakeword.openwakeword.model

/**
 * Represents a wake word detection event.
 *
 * This data class is emitted through the WakeWordEngine.detections Flow whenever
 * a wake word is successfully detected. It contains all the information about the
 * detection event.
 *
 * ## Usage Examples
 *
 * ### Basic Detection Handling
 * ```kotlin
 * engine.detections.collect { detection ->
 *     Log.d("WakeWord", "Detected: ${detection.model.name}")
 *     Log.d("WakeWord", "Score: ${detection.score}")
 *     Log.d("WakeWord", "Time: ${Date(detection.timestamp)}")
 * }
 * ```
 *
 * ### Confidence-Based Actions
 * ```kotlin
 * engine.detections.collect { detection ->
 *     when {
 *         detection.score > 0.9f -> {
 *             // Very high confidence - immediate action
 *             startVoiceRecognition()
 *         }
 *         detection.score > 0.7f -> {
 *             // Good confidence - normal action
 *             showListeningIndicator()
 *         }
 *         else -> {
 *             // Low confidence - maybe show subtle feedback
 *             flashMicIcon()
 *         }
 *     }
 * }
 * ```
 *
 * ### Multi-Model Handling
 * ```kotlin
 * engine.detections.collect { detection ->
 *     when (detection.model.name) {
 *         "Hey Assistant" -> startAssistantMode()
 *         "Emergency" -> triggerEmergencyProtocol()
 *         "Computer" -> openCommandInterface()
 *         else -> handleGenericWakeWord()
 *     }
 * }
 * ```
 *
 * ## Score Interpretation
 *
 * The score represents the model's confidence in the detection:
 * - **0.9-1.0**: Very high confidence, almost certain detection
 * - **0.7-0.9**: High confidence, reliable detection
 * - **0.5-0.7**: Medium confidence, likely valid
 * - **Below 0.5**: Low confidence (only emitted if threshold is set low)
 *
 * Note: Only detections with scores above the model's threshold are emitted.
 *
 * @property model The [WakeWordModel] that triggered this detection. Contains the name, model path, and threshold.
 * @property score The confidence score of the detection, ranging from 0.0 to 1.0. Higher values indicate higher confidence.
 * @property timestamp The system timestamp (milliseconds since epoch) when the detection occurred. Defaults to current time.
 *
 * @constructor Creates a wake word detection event
 *
 * @see WakeWordModel
 */
data class WakeWordDetection(
    val model: WakeWordModel,
    val score: Float,
    val timestamp: Long = System.currentTimeMillis()
)