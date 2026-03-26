package com.msp1974.vacompanion.wakeword.openwakeword.model

/**
 * Detection mode for handling multiple wake word models.
 * 
 * This enum determines how the WakeWordEngine processes and emits detections
 * when multiple models detect wake words simultaneously in the same audio frame.
 * 
 * ## Mode Selection Guidelines
 * 
 * ### Use SINGLE_BEST when:
 * - You have multiple similar wake words and want to avoid confusion
 * - Your app should respond to only one command at a time
 * - You want the most confident detection to take precedence
 * 
 * ### Use ALL when:
 * - Different wake words trigger different actions
 * - You want to support multi-command scenarios
 * - Wake words are sufficiently distinct to avoid false triggers
 * 
 * ## Examples
 * 
 * ### Voice Assistant (SINGLE_BEST)
 * ```kotlin
 * // Multiple ways to activate the same assistant
 * val models = listOf(
 *     WakeWordModel("Hey Assistant", "hey_assistant.onnx", 0.1f),
 *     WakeWordModel("OK Assistant", "ok_assistant.onnx", 0.1f),
 *     WakeWordModel("Computer", "computer.onnx", 0.15f)
 * )
 * 
 * val engine = WakeWordEngine(
 *     context = context,
 *     models = models,
 *     detectionMode = DetectionMode.SINGLE_BEST
 * )
 * // Only the highest confidence detection is emitted
 * ```
 * 
 * ### Smart Home Controller (ALL)
 * ```kotlin
 * // Different wake words for different actions
 * val models = listOf(
 *     WakeWordModel("Lights On", "lights_on.onnx", 0.2f),
 *     WakeWordModel("Lights Off", "lights_off.onnx", 0.2f),
 *     WakeWordModel("Temperature Up", "temp_up.onnx", 0.2f)
 * )
 * 
 * val engine = WakeWordEngine(
 *     context = context,
 *     models = models,
 *     detectionMode = DetectionMode.ALL
 * )
 * // All detected commands are emitted
 * ```
 *
 */
enum class DetectionMode {
    /**
     * Single best detection mode.
     * 
     * Only emits the detection with the highest confidence relative to its threshold.
     * When multiple models detect wake words, the engine selects the one with the
     * largest score-to-threshold difference (score - threshold).
     * 
     * If multiple detections have the same difference, the model that was registered
     * first (lower index) takes precedence.
     * 
     * Use case: Voice assistants where only one wake word should trigger at a time.
     * 
     * Example:
     * - Model A: score=0.8, threshold=0.5, difference=0.3 ✓ (selected)
     * - Model B: score=0.7, threshold=0.5, difference=0.2
     */
    SINGLE_BEST,
    
    /**
     * All detections mode.
     * 
     * Emits all detections that exceed their respective thresholds.
     * Multiple wake words can be detected and processed simultaneously.
     * 
     * Use case: Multi-command systems, gaming, or accessibility applications
     * where different wake words trigger different actions.
     * 
     * Example:
     * - Model A: score=0.8, threshold=0.5 ✓ (emitted)
     * - Model B: score=0.7, threshold=0.5 ✓ (emitted)
     * - Model C: score=0.4, threshold=0.5 ✗ (not emitted)
     */
    ALL
}