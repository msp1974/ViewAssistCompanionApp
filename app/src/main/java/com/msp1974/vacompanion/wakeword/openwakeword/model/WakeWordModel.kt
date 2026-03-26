package com.msp1974.vacompanion.wakeword.openwakeword.model

/**
 * Configuration for a wake word model.
 * 
 * This data class defines the parameters for a single wake word that the engine will detect.
 * Each model represents a unique wake word or phrase that can trigger detection events.
 * 
 * ## Model Files
 * 
 * Model files should be placed in the `assets` directory of your Android app:
 * ```
 * app/src/main/assets/
 * ├── hello_world.onnx
 * ├── hey_assistant.onnx
 * └── wake_word_models/
 *     └── custom_model.onnx
 * ```
 *
 * @property name Human-readable name for the wake word. Used in detection events and logging.
 * @property modelPath Path to the ONNX model file relative to the assets directory.
 * @property builtIn Whether the model is a built-in wake word or a custom model.
 * @property threshold Detection threshold between 0.0 and 1.0. Detections with scores above this value will trigger events. Default is 0.5f.
 * 
 * @constructor Creates a wake word model configuration
 */
data class WakeWordModel(
    val name: String,
    val modelPath: String,
    val builtIn: Boolean = true,
    val threshold: Float = 0.1f
)