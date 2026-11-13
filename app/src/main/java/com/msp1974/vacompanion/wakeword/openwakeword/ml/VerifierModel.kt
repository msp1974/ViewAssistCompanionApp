package com.msp1974.vacompanion.wakeword.openwakeword.ml

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import kotlin.math.exp

/**
 * Optional second stage check for an openWakeWord model, the same idea as openWakeWord's
 * custom verifier models (https://github.com/dscripka/openWakeWord#user-specific-models).
 *
 * openWakeWord trains these with train_custom_verifier and saves them as a pickled sklearn
 * pipeline (flatten -> StandardScaler -> LogisticRegression). The app can't read a pickle so
 * the same numbers are loaded from json instead:
 *
 * {"mean": [...], "scale": [...], "coef": [...], "intercept": 0.0, "threshold": 0.5}
 *
 * Each array is 16 x 96 = 1536 long (the flattened features the wake word model sees).
 * threshold is optional and defaults to 0.5.
 */
internal class VerifierModel private constructor(
    private val data: VerifierModelData
) {

    val threshold: Float get() = data.threshold
    private var sizeWarned = false

    /**
     * Probability (0 to 1) that the features are the voices the verifier was trained on.
     * If the features aren't the size it was trained on it lets the wake word through
     * rather than blocking it.
     */
    fun predict(audioFeatures: Array<Array<FloatArray>>): Float {
        val frames = audioFeatures[0]
        val count = frames.sumOf { it.size }
        if (count != data.coef.size) {
            if (!sizeWarned) Timber.w("Verifier expected ${data.coef.size} features but got $count, ignoring it")
            sizeWarned = true
            return 1f
        }
        var sum = data.intercept
        var i = 0
        for (frame in frames) {
            for (value in frame) {
                sum += (value - data.mean[i]) / data.scale[i] * data.coef[i]
                i++
            }
        }
        return 1f / (1f + exp(-sum))
    }

    companion object {
        const val FILE_SUFFIX = "_verifier.json"

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Loads <wakeWordId>_verifier.json from the folder if there is one, otherwise null.
         */
        fun load(dir: File, wakeWordId: String): VerifierModel? {
            val file = File(dir, "$wakeWordId$FILE_SUFFIX")
            if (!file.exists()) return null
            return try {
                val data = json.decodeFromString(VerifierModelData.serializer(), file.readText())
                require(data.coef.isNotEmpty() && data.mean.size == data.coef.size && data.scale.size == data.coef.size) {
                    "mean, scale and coef must all be the same length"
                }
                Timber.i("Loaded verifier for $wakeWordId (threshold ${data.threshold})")
                VerifierModel(data)
            } catch (e: Exception) {
                Timber.e(e, "Error loading verifier ${file.name}, wake word will run without it")
                null
            }
        }
    }
}

@Serializable
internal data class VerifierModelData(
    val mean: FloatArray,
    val scale: FloatArray,
    val coef: FloatArray,
    val intercept: Float,
    val threshold: Float = 0.5f,
)
