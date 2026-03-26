package com.msp1974.vacompanion.audio

import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.min

/**
 * Speex Audio Processor
 * Provides voice enhancement using Speex-inspired preprocessing algorithms
 * including noise suppression, echo cancellation, AGC, and VAD
 */
class SpeexProcessor(
    private val sampleRate: Int = 16000,
    private val frameSize: Int = 320,
    private val filterLength: Int = 200
) {
    // Preprocessing state
    private var denoiseState = DenoiseState(frameSize)
    private var echoState = EchoState(frameSize, filterLength, sampleRate)
    private var agcState = AGCState()
    private var vadState = VADState()
    
    // Feature flags
    var denoiseEnabled = true
    var echoSuppressionEnabled = true
    var agcEnabled = true
    var vadEnabled = true
    
    /**
     * Process audio frame with all enabled Speex preprocessing
     * @param input Audio samples (PCM 16-bit)
     * @return Processed audio samples
     */
    fun processFrame(input: ShortArray): ShortArray {
        if (input.size != frameSize) {
            Timber.w("Frame size mismatch: expected $frameSize, got ${input.size}")
            return input
        }
        
        var processed = input.copyOf()
        
        // 1. Echo cancellation (should be first)
        if (echoSuppressionEnabled) {
            processed = suppressEcho(processed)
        }
        
        // 2. Noise suppression
        if (denoiseEnabled) {
            processed = suppressNoise(processed)
        }
        
        // 3. Automatic Gain Control
        if (agcEnabled) {
            processed = applyAGC(processed)
        }
        
        return processed
    }
    
    /**
     * Process audio with float samples
     */
    fun processFrameFloat(input: FloatArray): FloatArray {
        val shorts = FloatArray(input.size)
        for (i in input.indices) {
            shorts[i] = (input[i] * 32767f).coerceIn(-32768f, 32767f)
        }
        
        val shortArray = ShortArray(shorts.size) { shorts[it].toInt().toShort() }
        val processed = processFrame(shortArray)
        
        return FloatArray(processed.size) { processed[it] / 32768f }
    }
    
    /**
     * Voice Activity Detection
     * @param input Audio samples
     * @return VAD probability (0.0 = silence, 1.0 = speech)
     */
    fun detectVoiceActivity(input: ShortArray): Float {
        if (!vadEnabled) return 1.0f
        
        return vadState.process(input)
    }
    
    /**
     * Noise Suppression using spectral subtraction
     */
    private fun suppressNoise(input: ShortArray): ShortArray {
        return denoiseState.process(input)
    }
    
    /**
     * Echo Suppression using adaptive filtering
     */
    private fun suppressEcho(input: ShortArray): ShortArray {
        return echoState.process(input)
    }
    
    /**
     * Automatic Gain Control
     */
    private fun applyAGC(input: ShortArray): ShortArray {
        return agcState.process(input)
    }
    
    /**
     * Set denoise suppression level
     * @param level 0 to 15 (higher = more aggressive)
     */
    fun setDenoiseSuppression(level: Int) {
        denoiseState.suppressionLevel = level.coerceIn(0, 15)
    }
    
    /**
     * Set AGC target level
     * @param level Target level in dB (typically 8000-32000)
     */
    fun setAGCLevel(level: Int) {
        agcState.targetLevel = level
    }
    
    /**
     * Set echo suppression strength
     * @param level 0 to 100 (higher = more suppression)
     */
    fun setEchoSuppression(level: Int) {
        echoState.suppressionLevel = level.coerceIn(0, 100)
    }
    
    /**
     * Reset all processing states
     */
    fun reset() {
        denoiseState.reset()
        echoState.reset()
        agcState.reset()
        vadState.reset()
    }
}

/**
 * Denoise State - Implements spectral noise suppression
 */
private class DenoiseState(private val frameSize: Int) {
    var suppressionLevel = 10
    private val noiseEstimate = FloatArray(frameSize)
    private val smoothingFactor = 0.9f
    private var frameCount = 0
    
    fun process(input: ShortArray): ShortArray {
        val output = ShortArray(input.size)
        
        // Convert to float for processing
        val floatInput = FloatArray(input.size) { input[it] / 32768f }
        
        // Update noise estimate (first few frames)
        if (frameCount < 20) {
            for (i in floatInput.indices) {
                noiseEstimate[i] = smoothingFactor * noiseEstimate[i] + 
                                  (1 - smoothingFactor) * abs(floatInput[i])
            }
            frameCount++
        }
        
        // Apply noise suppression
        val suppressionFactor = suppressionLevel / 15f
        for (i in floatInput.indices) {
            val signalLevel = abs(floatInput[i])
            val noiseLevel = noiseEstimate[i] * suppressionFactor
            
            // Spectral subtraction with floor
            val gain = if (signalLevel > noiseLevel) {
                1.0f - (noiseLevel / (signalLevel + 1e-6f))
            } else {
                0.1f // Noise floor
            }
            
            val processed = floatInput[i] * gain
            output[i] = (processed * 32767).toInt().coerceIn(-32768, 32767).toShort()
        }
        
        return output
    }
    
    fun reset() {
        noiseEstimate.fill(0f)
        frameCount = 0
    }
}

/**
 * Echo Cancellation State
 */
private class EchoState(
    private val frameSize: Int,
    private val filterLength: Int,
    private val sampleRate: Int
) {
    var suppressionLevel = 50
    private val echoBuffer = FloatArray(filterLength)
    private val adaptiveFilter = FloatArray(filterLength)
    private var bufferIndex = 0
    private val learningRate = 0.001f
    
    fun process(input: ShortArray): ShortArray {
        val output = ShortArray(input.size)
        val floatInput = FloatArray(input.size) { input[it] / 32768f }
        
        // Simple echo suppression using adaptive filtering
        for (i in floatInput.indices) {
            // Store in circular buffer
            echoBuffer[bufferIndex] = floatInput[i]
            
            // Estimate echo
            var echoEstimate = 0f
            for (j in adaptiveFilter.indices) {
                val idx = (bufferIndex - j + filterLength) % filterLength
                echoEstimate += adaptiveFilter[j] * echoBuffer[idx]
            }
            
            // Subtract echo estimate
            val suppressionFactor = suppressionLevel / 100f
            val processed = floatInput[i] - (echoEstimate * suppressionFactor)
            
            // Update adaptive filter (LMS algorithm)
            val error = processed
            for (j in adaptiveFilter.indices) {
                val idx = (bufferIndex - j + filterLength) % filterLength
                adaptiveFilter[j] += learningRate * error * echoBuffer[idx]
            }
            
            bufferIndex = (bufferIndex + 1) % filterLength
            output[i] = (processed * 32767).toInt().coerceIn(-32768, 32767).toShort()
        }
        
        return output
    }
    
    fun reset() {
        echoBuffer.fill(0f)
        adaptiveFilter.fill(0f)
        bufferIndex = 0
    }
}

/**
 * Automatic Gain Control State
 */
private class AGCState {
    var targetLevel = 20000
    private var currentGain = 1.0f
    private val attackTime = 0.001f
    private val releaseTime = 0.1f
    private var peakLevel = 0f
    
    fun process(input: ShortArray): ShortArray {
        val output = ShortArray(input.size)
        
        // Measure peak level
        var maxSample = 0f
        for (sample in input) {
            val absSample = abs(sample.toFloat())
            if (absSample > maxSample) {
                maxSample = absSample
            }
        }
        
        // Update peak level with attack/release
        if (maxSample > peakLevel) {
            peakLevel = maxSample * attackTime + peakLevel * (1 - attackTime)
        } else {
            peakLevel = maxSample * releaseTime + peakLevel * (1 - releaseTime)
        }
        
        // Calculate desired gain
        val desiredGain = if (peakLevel > 0) {
            targetLevel / peakLevel
        } else {
            1.0f
        }
        
        // Limit gain to reasonable range
        currentGain = desiredGain.coerceIn(0.1f, 10.0f)
        
        // Apply gain
        for (i in input.indices) {
            val amplified = input[i] * currentGain
            output[i] = amplified.toInt().coerceIn(-32768, 32767).toShort()
        }
        
        return output
    }
    
    fun reset() {
        currentGain = 1.0f
        peakLevel = 0f
    }
}

/**
 * Voice Activity Detection State
 */
private class VADState {
    private var energyThreshold = 100f
    private val historySize = 10
    private val energyHistory = mutableListOf<Float>()
    private var noiseFloor = 50f
    
    fun process(input: ShortArray): Float {
        // Calculate frame energy
        var energy = 0f
        for (sample in input) {
            energy += sample * sample
        }
        energy = kotlin.math.sqrt(energy / input.size)
        
        // Update energy history
        energyHistory.add(energy)
        if (energyHistory.size > historySize) {
            energyHistory.removeAt(0)
        }
        
        // Update noise floor (minimum energy)
        if (energyHistory.size >= historySize) {
            val minEnergy = energyHistory.minOrNull() ?: 50f
            noiseFloor = 0.95f * noiseFloor + 0.05f * minEnergy
        }
        
        // Calculate VAD probability
        val snr = (energy - noiseFloor) / (noiseFloor + 1f)
        val probability = (snr / 3f).coerceIn(0f, 1f)
        
        return probability
    }
    
    fun reset() {
        energyHistory.clear()
        noiseFloor = 50f
    }
}

/**
 * Speex Codec Wrapper for encoding/decoding
 */
class SpeexCodec(
    private val quality: Int = 8, // 0-10 (higher = better quality)
    private val complexity: Int = 3, // 1-10 (higher = slower)
    private val sampleRate: Int = 16000
) {
    private val frameSize = when (sampleRate) {
        8000 -> 160
        16000 -> 320
        32000 -> 640
        else -> 320
    }
    
    /**
     * Encode audio samples
     * @param input PCM samples
     * @return Encoded bytes
     */
    fun encode(input: ShortArray): ByteArray {
        // Simulate encoding (would use native Speex library in production)
        val buffer = ByteBuffer.allocate(input.size * 2)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        
        // Apply simple compression simulation
        for (sample in input) {
            buffer.putShort(sample)
        }
        
        return buffer.array()
    }
    
    /**
     * Decode audio samples
     * @param input Encoded bytes
     * @return PCM samples
     */
    fun decode(input: ByteArray): ShortArray {
        // Simulate decoding
        val buffer = ByteBuffer.wrap(input)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        
        val output = ShortArray(input.size / 2)
        for (i in output.indices) {
            output[i] = buffer.short
        }
        
        return output
    }
    
    fun getFrameSize(): Int = frameSize
}

/**
 * Utility functions for Speex processing
 */
object SpeexUtils {
    /**
     * Convert float samples to short PCM
     */
    fun floatToShort(input: FloatArray): ShortArray {
        return ShortArray(input.size) { i ->
            (input[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }
    }
    
    /**
     * Convert short PCM to float samples
     */
    fun shortToFloat(input: ShortArray): FloatArray {
        return FloatArray(input.size) { i ->
            input[i] / 32768f
        }
    }
    
    /**
     * Split audio into frames for processing
     */
    fun splitIntoFrames(input: ShortArray, frameSize: Int): List<ShortArray> {
        val frames = mutableListOf<ShortArray>()
        var offset = 0
        
        while (offset + frameSize <= input.size) {
            val frame = input.copyOfRange(offset, offset + frameSize)
            frames.add(frame)
            offset += frameSize
        }
        
        return frames
    }
    
    /**
     * Concatenate processed frames
     */
    fun concatenateFrames(frames: List<ShortArray>): ShortArray {
        val totalSize = frames.sumOf { it.size }
        val output = ShortArray(totalSize)
        var offset = 0
        
        for (frame in frames) {
            frame.copyInto(output, offset)
            offset += frame.size
        }
        
        return output
    }
}
