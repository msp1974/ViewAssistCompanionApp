package com.msp1974.vacompanion.wakeword.openwakeword.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.res.AssetManager
import com.msp1974.vacompanion.wakeword.openwakeword.model.WakeWordModel
import java.io.IOException
import kotlin.io.path.Path

/**
 * Handles ONNX model loading and inference for wake word detection.
 */
internal class OnnxModelRunner(
    private val assetManager: AssetManager,
    private val model: WakeWordModel
) : AutoCloseable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var session: OrtSession = createSession()

    private fun createSession(): OrtSession {
        return try {
                val modelBytes = loadModel(model)
                val sessionOptions = OrtSession.SessionOptions()
                sessionOptions.setInterOpNumThreads(1)
                sessionOptions.setIntraOpNumThreads(1)
                env.createSession(modelBytes, sessionOptions)
        } catch (e: IOException) {
            throw RuntimeException("Failed to load model: $model.modelPath", e)
        }
    }

    private fun loadModel(model: WakeWordModel): ByteArray {
        if (model.builtIn) {
            assetManager.open(model.modelPath).use { inputStream ->
                return inputStream.readBytes()
            }
        } else {
            val file = Path(model.modelPath).toFile()
            return file.readBytes()
        }
    }

    /**
     * Run inference on the wake word detection model.
     *
     * @param inputArray 3D float array of shape [1, features, embeddings]
     * @return Prediction score between 0.0 and 1.0
     */
    fun predictWakeWord(inputArray: Array<Array<FloatArray>>): Float {
        var inputTensor: OnnxTensor? = null

        try {
            inputTensor = OnnxTensor.createTensor(env, inputArray)
            session.run(mapOf(session.inputNames.first() to inputTensor)).use { outputs ->
                val result = outputs[0].value as Array<FloatArray>
                return result[0][0]
            }
        } catch (e: Exception) {
            throw RuntimeException("Failed to run inference", e)
        } finally {
            inputTensor?.close()
        }
    }

    override fun close() {
        session.close()
    }
}