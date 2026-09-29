package com.msp1974.vacompanion.wakeword.openwakeword

import android.content.Context
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.msp1974.vacompanion.settings.APPConfig
import timber.log.Timber
import java.io.File
import java.security.MessageDigest
import kotlin.math.sqrt

data class SpeakerVerificationResult(
    val accepted: Boolean,
    val score: Float,
    val reason: String
)

class SherpaSpeakerVerifier(
    private val context: Context,
    private val config: APPConfig,
) : AutoCloseable {
    private val speakerVerificationNumThreads = 2

    private var extractor: SpeakerEmbeddingExtractor? = null
    private var enrolledEmbedding: FloatArray? = null
    private var failed = false
    private var failureReason = ""

    val isReady: Boolean
        get() = !failed && extractor != null && enrolledEmbedding != null

    init {
        initialize()
    }

    fun verify(audio: FloatArray): SpeakerVerificationResult {
        if (audio.isEmpty()) {
            return SpeakerVerificationResult(
                accepted = false,
                score = 0f,
                reason = "empty_audio"
            )
        }

        val localExtractor = extractor
        val localEnrolled = enrolledEmbedding

        if (failed || localExtractor == null || localEnrolled == null) {
            return SpeakerVerificationResult(
                accepted = false,
                score = 0f,
                reason = failureReason.ifBlank { "not_initialized" }
            )
        }

        return try {
            val stream = localExtractor.createStream()
            stream.acceptWaveform(audio, config.sampleRate)
            stream.inputFinished()

            if (!localExtractor.isReady(stream)) {
                stream.release()
                SpeakerVerificationResult(
                    accepted = false,
                    score = 0f,
                    reason = "insufficient_audio"
                )
            } else {
                val testEmbedding = localExtractor.compute(stream)
                stream.release()

                val score = cosineSimilarity(localEnrolled, testEmbedding)
                SpeakerVerificationResult(
                    accepted = score >= config.speakerVerificationThreshold,
                    score = score,
                    reason = if (score >= config.speakerVerificationThreshold) "accepted" else "below_threshold"
                )
            }
        } catch (t: Throwable) {
            Timber.w(t, "Speaker verification failed at runtime")
            SpeakerVerificationResult(
                accepted = false,
                score = 0f,
                reason = "runtime_error"
            )
        }
    }

    private fun initialize() {
        val configuredModelPath = config.speakerVerificationModelPath.trim()
        val embeddingPath = config.speakerVerificationEmbeddingPath.trim()

        if (configuredModelPath.isEmpty()) {
            markFailed("missing_model_path")
            return
        }
        if (embeddingPath.isEmpty()) {
            markFailed("missing_embedding_path")
            return
        }

        val localModelPath = try {
            resolveModelFilePath(configuredModelPath)
        } catch (t: Throwable) {
            Timber.w(t, "Failed to resolve speaker model path")
            markFailed("model_path_resolve_failed")
            return
        }

        try {
            val extractorConfig = SpeakerEmbeddingExtractorConfig(
                localModelPath,
                speakerVerificationNumThreads,
                false,
                "cpu"
            )
            extractor = SpeakerEmbeddingExtractor(null, extractorConfig)
        } catch (t: Throwable) {
            Timber.w(t, "Failed to initialize Sherpa extractor")
            markFailed("extractor_init_failed")
            return
        }

        val localExtractor = extractor
        if (localExtractor == null) {
            markFailed("extractor_null")
            return
        }

        val enrolled = loadEmbeddingFromFile(embeddingPath)
        if (enrolled == null || enrolled.isEmpty()) {
            markFailed("embedding_load_failed")
            return
        }

        val expectedDim = localExtractor.dim()
        if (enrolled.size != expectedDim) {
            Timber.w("Speaker embedding dimension mismatch expected=%d actual=%d", expectedDim, enrolled.size)
            markFailed("embedding_dim_mismatch")
            return
        }

        enrolledEmbedding = enrolled
        failed = false
        failureReason = ""
        Timber.i("Speaker verifier initialized (dim=%d threshold=%.2f)", expectedDim, config.speakerVerificationThreshold)
    }

    private fun resolveModelFilePath(modelPath: String): String {
        val direct = File(modelPath)
        if (direct.isFile) {
            return direct.absolutePath
        }

        // Treat as asset path and copy to app-local storage for JNI file-based loading.
        val targetDir = File(context.filesDir, "speaker-models")
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }
        val ext = modelPath.substringAfterLast('.', "onnx")
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(modelPath.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val targetFile = File(targetDir, "speaker-model-$digest.$ext")
        if (!targetFile.exists() || targetFile.length() == 0L) {
            context.assets.open(modelPath).use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
        return targetFile.absolutePath
    }

    private fun loadEmbeddingFromFile(path: String): FloatArray? {
        return try {
            val text = File(path).readText(Charsets.UTF_8).trim()
            if (text.isEmpty()) return null

            val compact = text.removePrefix("[").removeSuffix("]")
            if (compact.isBlank()) return null

            val tokens = compact.split(',', ' ', '\n', '\t', '\r')
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            val values = FloatArray(tokens.size)
            for (i in tokens.indices) {
                values[i] = tokens[i].toFloat()
            }
            values
        } catch (t: Throwable) {
            Timber.w(t, "Failed to read enrolled speaker embedding: %s", path)
            null
        }
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            val av = a[i].toDouble()
            val bv = b[i].toDouble()
            dot += av * bv
            normA += av * av
            normB += bv * bv
        }
        val denom = sqrt(normA) * sqrt(normB)
        if (denom <= 1e-12) return 0f
        return (dot / denom).toFloat()
    }

    private fun markFailed(reason: String) {
        failed = true
        failureReason = reason
        Timber.w("Speaker verifier unavailable: %s", reason)
    }

    override fun close() {
        runCatching { extractor?.release() }
        extractor = null
        enrolledEmbedding = null
    }
}
