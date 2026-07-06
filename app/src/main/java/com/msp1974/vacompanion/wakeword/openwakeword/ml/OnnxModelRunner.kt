package com.msp1974.vacompanion.wakeword.openwakeword.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.res.AssetManager
import com.msp1974.vacompanion.wakeword.models.WakeWord
import com.msp1974.vacompanion.wakeword.models.WakeWordWithId
import com.msp1974.vacompanion.wakeword.openwakeword.model.WakeWordModel
import io.ktor.util.moveToByteArray
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.io.path.Path

/**
 * Handles ONNX model loading and inference for wake word detection.
 */
internal class OnnxModelRunner(
    private val wakeWord: WakeWordWithId
) : ModelRunner {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var session: OrtSession = createSession()

    private fun createSession(): OrtSession {
        return try {
            val modelBytes = runBlocking(Dispatchers.IO) { loadModel(wakeWord) }
            val sessionOptions = OrtSession.SessionOptions()
            sessionOptions.setInterOpNumThreads(1)
            sessionOptions.setIntraOpNumThreads(1)
            sessionOptions.addConfigEntry("session_options.enable_cpu_mem_arena", "1")
            env.createSession(modelBytes, sessionOptions)
        } catch (e: FileNotFoundException) {
            throw RuntimeException("Unable to load ${wakeWord.id}. ${wakeWord.wakeWord.model} not found")
        } catch (e: IOException) {
            throw RuntimeException("Failed to load model: ${wakeWord.id}", e)
        }
    }

    override suspend fun loadModel(wakeWord: WakeWordWithId): ByteArray {
        val modelBuffer = wakeWord.load()
        return modelBuffer.moveToByteArray()
    }

    /**
     * Run inference on the wake word detection model.
     *
     * @param inputArray 3D float array of shape [1, features, embeddings]
     * @return Prediction score between 0.0 and 1.0
     */
    override fun predictWakeWord(inputArray: Array<Array<FloatArray>>): Float {
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