package com.msp1974.vacompanion.wakeword.openwakeword

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.AssetManager
import androidx.annotation.RequiresPermission
import com.google.protobuf.ByteString
import com.msp1974.vacompanion.audio.AudioDSP
import com.msp1974.vacompanion.audio.MicrophoneInput
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.wakeword.WakeWordEngineProvider
import com.msp1974.vacompanion.wakeword.openwakeword.audio.AudioProcessor
import com.msp1974.vacompanion.wakeword.openwakeword.ml.OnnxModelRunner
import com.msp1974.vacompanion.wakeword.openwakeword.model.WakeWordModel
import com.msp1974.vacompanion.wakeword.openwakeword.model.WakeWordScore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import kotlin.math.abs


/**
 * Main entry point for wake word detection using ONNX Runtime.
 *
 * This class manages multiple wake word models and emits detection events through a Kotlin Flow.
 * It provides real-time audio processing with configurable detection modes and cooldown periods.
 */
class OpenWakeWordEngine(
    private val context: Context,
    private val models: List<WakeWordModel>,
    private val detectionCooldownMs: Long = 2000L,
    muted: Boolean = false
): WakeWordEngineProvider() {

    private val config = APPConfig.getInstance(context)
    private val assetManager: AssetManager = context.assets
    private val modelProcessors = mutableMapOf<WakeWordModel, ModelProcessor>()
    private val detectionCooldowns = mutableMapOf<String, Long>()

    var isEnabled = true

    private var _audioProcessor: AudioProcessor = AudioProcessor(assetManager)
    private val slidingWindowSize = 3
    private val probabilities = ArrayDeque<Float>(slidingWindowSize)

    /**
     * Flow of wake word detection events.
     *
     * This Flow emits [WakeWordDetection] objects whenever a wake word is detected.
     * The Flow is hot and shared, meaning multiple collectors will receive the same events.
     *
     * ## Example: Basic Collection
     * ```kotlin
     * engine.detections.collect { detection ->
     *     showToast("${detection.model.name} detected!")
     * }
     * ```
     *
     * ## Example: Filtering High-Confidence Detections
     * ```kotlin
     * engine.detections
     *     .filter { it.score > 0.8f }
     *     .collect { detection ->
     *         // Only process high-confidence detections
     *     }
     * ```
     *
     * ## Example: Debouncing Rapid Detections
     * ```kotlin
     * engine.detections
     *     .debounce(500) // Additional debounce on top of cooldown
     *     .collect { detection ->
     *         // Process debounced detections
     *     }
     * ```
     */

    /**
     * Flow of real-time wake word scores.
     *
     * This Flow emits [WakeWordScore] objects continuously for all models,
     * regardless of whether they exceed the detection threshold.
     * Useful for real-time monitoring and visualization.
     */

    init {
        require(models.isNotEmpty()) { "At least one wake word model must be provided" }
        initializeModels()
    }

    private fun initializeModels() {
        models.forEach { model ->
            val processor = ModelProcessor(assetManager, model)
            modelProcessors[model] = processor
        }
    }

    fun addModel(model: WakeWordModel) {
        /**
        Add model to detections
         */
        Timber.w("Adding model ${model.name} to engine")
        modelProcessors.forEach {(wakeWordModel, processor) ->
            if (wakeWordModel.name == model.name) {
                throw IllegalArgumentException("Model with name ${model.name} already exists")
            }
        }
        modelProcessors[model] = ModelProcessor(assetManager, model)
    }

    fun removeModel(modelName: String) {
        /**
        Remove model from detections
         */
        Timber.w("Removing model $modelName from engine")
        modelProcessors.forEach {(wakeWordModel, processor) ->
            if (wakeWordModel.name == modelName) {
                processor.close()
                modelProcessors.remove(wakeWordModel)
                return
            }
        }
        throw IllegalArgumentException("Model with name $modelName not found")
    }

    private val _muted = MutableStateFlow(muted)
    val muted = _muted.asStateFlow()
    override fun setMuted(value: Boolean) {
        _muted.value = value
    }

    override fun isMuted(): Boolean {
        return _muted.value
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun start() = muted.flatMapLatest {
        if (it) emptyFlow()
        else flow {
            val microphoneInput = MicrophoneInput(frameSize = 1280)
            try {
                microphoneInput.start()
                emit(AudioResult.EngineStatus("Started"))
                while (true) {
                    val audio = microphoneInput.readFloat()

                    if (audio.isNotEmpty()) {

                        if (config.diagnosticsEnabled) {
                            emit(AudioResult.AudioLevel(AudioDSP().audioLevel(audio)))
                        }

                        if (isStreaming) {
                            val a = AudioDSP().floatArrayToByteBuffer(audio)
                            emit(AudioResult.Audio(ByteString.copyFrom(a)))
                        }

                        val detections = processAudio(audio)
                        for (detection in detections) {
                            if (detection.detected) {
                                emit(AudioResult.WakeDetected(detection))
                            }
                        }
                    }
                    yield()
                }
            } finally {
                microphoneInput.close()
                emit(AudioResult.EngineStatus("Stopped"))
            }
        }
    }

    @SuppressLint("DefaultLocale")
    fun processAudio(audioBuffer: FloatArray): List<WakeWordDetection> {
        val detections = mutableListOf<WakeWordDetection>()

        if (isEnabled) {
            val audioFeatures = _audioProcessor.getAudioFeatures(audioBuffer)
            modelProcessors.map { (model, processor) ->
                try {
                    val score = processor.process(audioFeatures)
                    if (score > model.threshold) {
                        Timber.d(
                            "DETECTION! ${model.name} - Score: ${
                                String.format("%.5f", score)
                            } > Threshold: ${String.format("%.5f", model.threshold)}"
                        )
                        detections.add(
                            WakeWordDetection(
                                model.name,
                                model.name,
                                isWakeWordDetected(model, score),
                                score
                            )
                        )
                    }
                } catch (e: Exception) {
                    Timber.e("Error processing model ${model.name} ->$e")
                    e.printStackTrace()
                }
            }
        }
        return detections
    }

    private fun isWakeWordDetected(model: WakeWordModel, probability: Float): Boolean {
        if (probabilities.size == slidingWindowSize)
            probabilities.removeFirst()
        probabilities.add(probability)

        return probabilities.size == slidingWindowSize && probabilities.average() > model.threshold
    }

    fun enable() {
        isEnabled = true
    }

    fun disable() {
        isEnabled = false
    }

    fun reset() {
        _audioProcessor.reset()
    }

    /**
     * Stops wake word detection.
     *
     * This method stops audio recording and cancels all ongoing detection processing.
     * The engine can be restarted by calling [start] again.
     *
     * ## Example
     * ```kotlin
     * override fun onPause() {
     *     super.onPause()
     *     engine.stop() // Stop detection when app goes to background
     * }
     * ```
     *
     * @see start
     */
    fun stop() {

    }

    /**
     * Releases all resources used by the engine.
     *
     * This method should be called when the engine is no longer needed to free up memory
     * and system resources. After calling this method, the engine cannot be reused.
     *
     * ## Important
     * Always call this method in your Activity/Fragment's onDestroy() to prevent memory leaks.
     *
     * ## Example
     * ```kotlin
     * override fun onDestroy() {
     *     super.onDestroy()
     *     wakeWordEngine?.release()
     * }
     * ```
     *
     * This method will:
     * - Stop any ongoing detection
     * - Release ONNX Runtime sessions
     * - Free audio processing resources
     * - Clear internal caches
     */
    override fun release() {
        stop()
        modelProcessors.values.forEach { it.close() }
        modelProcessors.clear()
    }

    /**
     * Internal class to process audio for a specific model.
     */
    private inner class ModelProcessor(
        assetManager: AssetManager,
        model: WakeWordModel
    ) : AutoCloseable {

        private val modelRunner = OnnxModelRunner(assetManager, model)

        fun process(audioFeatures: Array<Array<FloatArray>>): Float {
            val score = modelRunner.predictWakeWord(audioFeatures)
            return score
        }

        override fun close() {
            modelRunner.close()
        }
    }
}