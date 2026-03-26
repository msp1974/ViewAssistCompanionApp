package com.msp1974.vacompanion.wakeword.openwakeword.audio

import android.content.res.AssetManager
import com.msp1974.vacompanion.wakeword.openwakeword.ml.EmbeddingModel
import com.msp1974.vacompanion.wakeword.openwakeword.ml.MelSpectrogram
import java.util.*
import kotlin.random.Random

/**
 * Processes audio data for wake word detection.
 * Handles audio buffering, mel-spectrogram computation, and feature extraction.
 */
internal class AudioProcessor(
    assetManager: AssetManager,
) : AutoCloseable {

    companion object {
        private const val N_PREPARED_SAMPLES = 1280
        private const val SAMPLE_RATE = 16000
        private const val MEL_SPECTROGRAM_MAX_LEN = 10 * 97
        private const val FEATURE_BUFFER_MAX_LEN = 120
        private const val WINDOW_SIZE = 76
        private const val STEP_SIZE = 8
        private const val MEL_SPEC_FRAMES = 32
    }

    private val melSpectrogram = MelSpectrogram(assetManager)
    private val embeddingModel = EmbeddingModel(assetManager)

    private var featureBuffer: Array<FloatArray>? = null
    private val rawDataBuffer = ArrayDeque<Float>(SAMPLE_RATE * 10)
    private var rawDataRemainder = floatArrayOf()
    private var melSpectrogramBuffer: Array<FloatArray> = Array(WINDOW_SIZE) { FloatArray(MEL_SPEC_FRAMES) { 1.0f } }
    private var accumulatedSamples = 0

    init {
        // Initialize feature buffer with random data
        val randomData = FloatArray(SAMPLE_RATE * 4) { Random.nextFloat() * 2000f - 1000f }
        featureBuffer = getEmbeddings(randomData, WINDOW_SIZE, STEP_SIZE)
    }

    fun reset() {
        val randomData = FloatArray(SAMPLE_RATE * 4) { Random.nextFloat() * 2000f - 1000f }
        featureBuffer = getEmbeddings(randomData, WINDOW_SIZE, STEP_SIZE)
        melSpectrogramBuffer = Array(WINDOW_SIZE) { FloatArray(MEL_SPEC_FRAMES) { 1.0f} }
    }

    fun getAudioFeatures(audioBuffer: FloatArray): Array<Array<FloatArray>> {
        streamingFeatures(audioBuffer)
        return getFeatures(16, -1)
    }

    private fun streamingFeatures(audioBuffer: FloatArray): Int {
        var processedSamples = 0
        accumulatedSamples = 0
        var buffer = audioBuffer

        // Handle remainder from previous processing
        if (rawDataRemainder.isNotEmpty()) {
            buffer = rawDataRemainder + audioBuffer
            rawDataRemainder = floatArrayOf()
        }

        // Process in chunks of N_PREPARED_SAMPLES
        if (accumulatedSamples + buffer.size >= N_PREPARED_SAMPLES) {
            val remainder = (accumulatedSamples + buffer.size) % N_PREPARED_SAMPLES

            if (remainder != 0) {
                val evenChunks = buffer.copyOfRange(0, buffer.size - remainder)
                bufferRawData(evenChunks)
                accumulatedSamples += evenChunks.size
                rawDataRemainder = buffer.copyOfRange(buffer.size - remainder, buffer.size)
            } else {
                bufferRawData(buffer)
                accumulatedSamples += buffer.size
                rawDataRemainder = floatArrayOf()
            }
        } else {
            accumulatedSamples += buffer.size
            bufferRawData(buffer)
        }

        // Process accumulated samples
        if (accumulatedSamples >= N_PREPARED_SAMPLES && accumulatedSamples % N_PREPARED_SAMPLES == 0) {
            streamingMelSpectrogram(accumulatedSamples)

            val x = Array(1) { Array(WINDOW_SIZE) { Array(MEL_SPEC_FRAMES) { FloatArray(1) } } }

            for (i in (accumulatedSamples / N_PREPARED_SAMPLES) - 1 downTo 0) {
                val ndx = if (i == 0) melSpectrogramBuffer.size else melSpectrogramBuffer.size - 8 * i
                val start = maxOf(0, ndx - WINDOW_SIZE)

                for ((k, j) in (start until ndx).withIndex()) {
                    for (w in 0 until MEL_SPEC_FRAMES) {
                        x[0][k][w][0] = melSpectrogramBuffer[j][w]
                    }
                }

                if (x[0].size == WINDOW_SIZE) {
                    val newFeatures = embeddingModel.generateEmbeddings(x)
                    featureBuffer = featureBuffer?.let { existing ->
                        existing + newFeatures
                    } ?: newFeatures
                }
            }

            processedSamples = accumulatedSamples
            accumulatedSamples = 0
        }

        // Trim feature buffer if too large
        featureBuffer?.let { buffer ->
            if (buffer.size > FEATURE_BUFFER_MAX_LEN) {
                featureBuffer = buffer.takeLast(FEATURE_BUFFER_MAX_LEN).toTypedArray()
            }
        }

        return if (processedSamples != 0) processedSamples else accumulatedSamples
    }

    private fun bufferRawData(data: FloatArray) {
        // Remove old data if buffer is full
        while (rawDataBuffer.size + data.size > SAMPLE_RATE * 10) {
            rawDataBuffer.poll()
        }

        // Add new data
        data.forEach { rawDataBuffer.offer(it) }
    }

    private fun streamingMelSpectrogram(nSamples: Int) {
        require(rawDataBuffer.size >= 400) {
            "The number of input frames must be at least 400 samples @ 16kHz (25 ms)!"
        }

        // Get last n_samples + 480 samples
        val bufferList = rawDataBuffer.toList()
        val tempArray = bufferList.takeLast(nSamples + 480).toFloatArray()

        // Compute mel-spectrogram
        val newMelSpectrogram = melSpectrogram.computeMelSpectrogram(tempArray)

        // Combine with existing buffer
        melSpectrogramBuffer = melSpectrogramBuffer + newMelSpectrogram

        // Trim if exceeds max length
        if (melSpectrogramBuffer.size > MEL_SPECTROGRAM_MAX_LEN) {
            melSpectrogramBuffer = melSpectrogramBuffer.takeLast(MEL_SPECTROGRAM_MAX_LEN).toTypedArray()
        }
    }

    private fun getFeatures(nFeatureFrames: Int, startNdx: Int): Array<Array<FloatArray>> {
        val buffer = featureBuffer ?: return arrayOf(arrayOf(floatArrayOf()))

        val startIndex = if (startNdx != -1) {
            startNdx
        } else {
            maxOf(0, buffer.size - nFeatureFrames)
        }

        val endIndex = if (startNdx != -1) {
            minOf(startNdx + nFeatureFrames, buffer.size)
        } else {
            buffer.size
        }

        val features = buffer.sliceArray(startIndex until endIndex)
        return arrayOf(features)
    }

    private fun getEmbeddings(audioData: FloatArray, windowSize: Int, stepSize: Int): Array<FloatArray> {
        val spec = melSpectrogram.computeMelSpectrogram(audioData)
        val windows = mutableListOf<Array<FloatArray>>()

        for (i in 0..spec.size - windowSize step stepSize) {
            val window = spec.sliceArray(i until i + windowSize)
            if (window.size == windowSize) {
                windows.add(window)
            }
        }

        // Convert to 4D array with extra dimension
        val batch = Array(windows.size) { i ->
            Array(windowSize) { j ->
                Array(spec[0].size) { k ->
                    FloatArray(1) { windows[i][j][k] }
                }
            }
        }

        return embeddingModel.generateEmbeddings(batch)
    }

    override fun close() {
        melSpectrogram.close()
        embeddingModel.close()
    }
}