package com.msp1974.vacompanion.wakeword.microwakeword.microwakeword

import com.example.microfeatures.MicroFrontend
import com.msp1974.vacompanion.utils.fillFrom
import com.msp1974.vacompanion.wakeword.WakeWordEngineProvider
import timber.log.Timber
import java.nio.ByteBuffer

private const val SAMPLES_PER_SECOND = 16000
private const val SAMPLES_PER_CHUNK = 160  // 10ms
private const val BYTES_PER_SAMPLE = 2  // 16-bit
private const val BYTES_PER_CHUNK = SAMPLES_PER_CHUNK * BYTES_PER_SAMPLE

class MicroWakeWordDetector(private val wakeWords: List<MicroWakeWord>) : AutoCloseable {
    private val frontend = MicroFrontend()
    private val buffer = ByteBuffer.allocateDirect(BYTES_PER_CHUNK)

    fun detect(audio: ByteBuffer): List<WakeWordEngineProvider.WakeWordDetection> {
        val detections = mutableListOf<WakeWordEngineProvider.WakeWordDetection>()
        buffer.fillFrom(audio)
        while (buffer.flip().remaining() == BYTES_PER_CHUNK) {
            val processOutput = frontend.processSamples(buffer)
            buffer.position(buffer.position() + processOutput.samplesRead * BYTES_PER_SAMPLE)
            buffer.compact()
            buffer.fillFrom(audio)
            if (processOutput.features.isEmpty())
                continue
            for (wakeWord in wakeWords) {
                val result = wakeWord.processAudioFeatures(processOutput.features)

                // Hold highest detect or highest none detect if no detection
                if (!detections.any { it.wakeWordId == wakeWord.id } || (result.detected && detections.any { it.wakeWordId == wakeWord.id && it.score < result.score }))
                    detections.removeIf { it.wakeWordId == wakeWord.id }
                    detections.add(
                        WakeWordEngineProvider.WakeWordDetection(
                            wakeWord.id,
                            wakeWord.wakeWord,
                            result.detected,
                            result.score
                        )
                    )
            }
        }
        buffer.compact()
        return detections
    }

    override fun close() {
        frontend.close()
        for (model in wakeWords)
            model.close()
    }
}