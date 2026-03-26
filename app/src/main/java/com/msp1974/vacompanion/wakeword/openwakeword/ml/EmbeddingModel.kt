package com.msp1974.vacompanion.wakeword.openwakeword.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.res.AssetManager

/**
 * Handles embedding generation from mel-spectrograms using ONNX model.
 */
internal class EmbeddingModel(
    private val assetManager: AssetManager
) : AutoCloseable {

    companion object {
        private const val EMBEDDING_MODEL = "embedding_model.onnx"
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    var session: OrtSession? = null
    private val modelBytes: ByteArray = assetManager.open(EMBEDDING_MODEL).use { inputStream ->
        inputStream.readBytes()
    }


    /**
     * Generate embeddings from mel-spectrogram windows.
     *
     * @param input 4D array of shape [batch, height, width, channels]
     * @return 2D array of embeddings
     */
    fun generateEmbeddings(input: Array<Array<Array<FloatArray>>>): Array<FloatArray> {
        var inputTensor: OnnxTensor? = null
        try {
            if (session == null) {
                val sessionOptions = OrtSession.SessionOptions()
                sessionOptions.setInterOpNumThreads(1)
                sessionOptions.setIntraOpNumThreads(1)
                session = env.createSession(modelBytes, sessionOptions)
            }

            inputTensor = OnnxTensor.createTensor(env, input)

            session!!.run(mapOf("input_1" to inputTensor)).use { results ->
                val rawOutput = results[0].value as Array<Array<Array<FloatArray>>>

                // Reshape from (41, 1, 1, 96) to (41, 96)
                return Array(rawOutput.size) { i ->
                    rawOutput[i][0][0].copyOf()
                }
            }
        } catch (e: Exception) {
            throw RuntimeException("Failed to generate embeddings", e)
        } finally {
            inputTensor?.close()
            //session?.close()
        }
    }

    override fun close() {
        // env is managed globally by OrtEnvironment
        session?.close()
    }
}