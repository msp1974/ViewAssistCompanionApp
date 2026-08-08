package com.msp1974.vacompanion.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10

class AudioDSP {

    fun audioLevel(audioBuffer: ByteArray): Float {
        val shortArray = byteArrayToShortArray(audioBuffer)
        val floatArray = normaliseAudioBuffer(shortArray)
        return floatArray.map { i -> abs(i) }.average().toFloat()
    }

    fun audioLevel(audioBuffer: FloatArray): Float {
        return audioBuffer.map { i -> abs(i) }.average().toFloat()
    }

    /** Converts a linear 0..1 [audioLevel] reading to dBFS, floored at [AUDIO_LEVEL_FLOOR_DBFS]. */
    fun linearToDbfs(level: Float): Float {
        val magnitude = abs(level)
        return if (magnitude > MIN_LINEAR_LEVEL) {
            (20f * log10(magnitude)).coerceAtLeast(AUDIO_LEVEL_FLOOR_DBFS)
        } else {
            AUDIO_LEVEL_FLOOR_DBFS
        }
    }

    companion object {
        /** Displayed as "silence" - quiet enough that the exact dB figure isn't meaningful. */
        const val AUDIO_LEVEL_FLOOR_DBFS = -80f
        private const val MIN_LINEAR_LEVEL = 1e-6f
    }

    fun reduceVolume(audioBuffer: ByteArray, reductionFactor: Float): ByteArray {
        val shortArray = byteArrayToShortArray(audioBuffer)
        for (i in shortArray.indices) {
            shortArray[i] = (shortArray[i] * reductionFactor).toInt().coerceIn(-32768, 32767).toShort()
        }
        return shortArrayToByteBuffer(shortArray)
    }

    fun normaliseAudioBuffer(audioBuffer: ShortArray): FloatArray {
        val floatBuffer = audioBuffer.map { (it.toFloat() / 32768.0f) }.toFloatArray()
        return floatBuffer
    }

    /**
     * Converts PCM16 samples to the float scale expected by OpenWakeWord's
     * upstream mel-spectrogram model.
     *
     * The model accepts float tensors, but their values retain the original
     * signed 16-bit PCM magnitude.
     */
    fun openWakeWordInput(audioBuffer: ShortArray): FloatArray {
        return FloatArray(audioBuffer.size) { index -> audioBuffer[index].toFloat() }
    }

    fun shortArrayToByteBuffer(audioBuffer: ShortArray): ByteArray {
        val byteBuffer = ByteArray(audioBuffer.size * 2)
        for (i in audioBuffer.indices) {
            val value: Int = audioBuffer[i].toInt()
            byteBuffer[i * 2] = (value and 0xFF).toByte()
            byteBuffer[i * 2 + 1] = (value shr 8).toByte()
        }
        return byteBuffer
    }

    fun byteArrayToShortArray(byteArray: ByteArray): ShortArray {
        val shortArray = ShortArray(byteArray.size / 2)
        ByteBuffer.wrap(byteArray)
            .order(ByteOrder.LITTLE_ENDIAN) // Or BIG_ENDIAN depending on your data source
            .asShortBuffer()
            .get(shortArray)
        return shortArray
    }

    fun byteArrayToFloatArray(byteArray: ByteArray): FloatArray {
        val shortArray = ShortArray(byteArray.size / 2)
        ByteBuffer.wrap(byteArray)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(shortArray)

        val floatArray = FloatArray(shortArray.size)
        for (i in shortArray.indices) {
            floatArray[i] = shortArray[i].toFloat() / 32768.0f
        }
        return floatArray
    }

    fun floatArrayToByteBuffer(audioBuffer: FloatArray): ByteArray {
        val byteBuffer = ByteArray(audioBuffer.size * 2)
        for (i in audioBuffer.indices) {
            val value: Int = (audioBuffer[i] * 32768.0f).toInt()
            byteBuffer[i * 2] = (value and 0xFF).toByte()
            byteBuffer[i * 2 + 1] = (value shr 8).toByte()
        }
        return byteBuffer
    }
}
