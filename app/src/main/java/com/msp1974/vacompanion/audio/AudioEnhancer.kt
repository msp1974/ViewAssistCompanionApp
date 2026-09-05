  package com.msp1974.vacompanion.audio

import android.content.Context
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Software AGC + noise suppression fallback for microphone capture.
 *
 * Most devices expose these as platform [android.media.audiofx] effects
 * (`AutomaticGainControl`, `NoiseSuppressor`) implemented in the audio HAL,
 * which are always preferable - they run on the DSP/HAL rather than the
 * app's CPU and are tuned for the specific hardware. Not every device
 * supports them though (or supports one but not the other), so this class
 * provides an equivalent software implementation that [MicrophoneInput]
 * enables per-feature, only for whichever effect(s) the device lacks.
 *
 * The AGC always levels towards a target loudness (see [setMicGainDb]) rather
 * than only ever attenuating, so devices with a quiet built-in mic still get
 * a usable average level with `mic_gain` left at its default of 0.
 */
class AudioEnhancer(
    private val sampleRate: Int = VACAAudioFormat.SAMPLE_RATE_HZ,
    private val context: Context? = null,
) {

    var agcEnabled = false
    var noiseSuppressionEnabled = false

    private val agc = AutomaticGainController(sampleRate)
    private val noiseSuppressor: NoiseSuppressor = SpectralNoiseSuppressor(sampleRate = sampleRate)

    /**
     * Maps the HA-configurable `mic_gain` setting (dB, range -10..10) onto the AGC's
     * target level: at 0 the mic is always leveled to
     * [AutomaticGainController.BASE_TARGET_LEVEL_DBFS] (-18 dBFS) regardless of how quiet
     * or loud the raw microphone is, and moving mic_gain up or down shifts that target
     * louder or quieter by the same number of dB.
     */
    fun setMicGainDb(micGainDb: Float) {
        agc.targetLevelDbfs = AutomaticGainController.BASE_TARGET_LEVEL_DBFS +
            micGainDb.coerceIn(MIC_GAIN_MIN_DB, MIC_GAIN_MAX_DB)
    }

    fun processFrame(input: ShortArray): ShortArray {
        if (input.isEmpty()) return input
        // Noise suppression cleans up generic noise, then the AGC levels the final, cleaned signal.
        var processed = input

        if (agcEnabled) processed = agc.process(processed)
        if (noiseSuppressionEnabled) processed = noiseSuppressor.process(processed)
        return processed
    }

    fun reset() {
        agc.reset()
        noiseSuppressor.reset()
    }

    /** Releases any native resources the current [NoiseSuppressor] implementation holds. */
    fun release() {
        noiseSuppressor.release()
    }

    companion object {
        /** `mic_gain` setting range - also used by the diagnostics UI to size the mic level gauge. */
        const val MIC_GAIN_MIN_DB = -10f
        const val MIC_GAIN_MAX_DB = 10f
    }
}

/**
 * Digital automatic gain control operating in the dBFS domain: an envelope
 * follower estimates the current signal level, gain is derived from the gap
 * to [targetLevelDbfs] and clamped to [-maxAttenuationDb, maxGainDb], and a
 * noise gate fades the correction out during near-silence so background
 * noise isn't cranked up towards the target level between utterances. A
 * limiter is applied as a safety net against clipping.
 *
 * [targetLevelDbfs] defaults to [BASE_TARGET_LEVEL_DBFS] so, out of the box,
 * this always levels the microphone to a consistent -18 dBFS average -
 * including boosting devices with a quiet built-in mic - rather than only
 * ever attenuating; [maxGainDb]/[maxAttenuationDb] are generous enough to
 * cover the full range [AudioEnhancer.setMicGainDb] can shift the target to.
 *
 * The gate itself is threshold-free by design: a single fixed dBFS cutoff can't fit every
 * microphone, since raw input level for the same physical loudness varies wildly device to
 * device (a quiet-mic device's speech may never reach a threshold tuned for a sensitive one,
 * while a sensitive-mic device's idle self-noise alone may already exceed it, leaving the gate
 * permanently open). Instead the gate threshold auto-calibrates per device, tracked
 * [noiseGateMarginDb] above a running estimate of *this* microphone's own ambient floor
 * ([noiseFloorDb] - drops instantly to a quieter reading so it converges on the true floor found
 * in gaps between speech/noise, rises only slowly so a transient loud burst isn't mistaken for a
 * shift in the baseline), clamped to [minNoiseGateThresholdDbfs]/[maxNoiseGateThresholdDbfs] as a
 * safety net against a pathological floor reading.
 */
class AutomaticGainController(
    sampleRate: Int,
    var targetLevelDbfs: Float = BASE_TARGET_LEVEL_DBFS,
    var maxGainDb: Float = 50f,
    var maxAttenuationDb: Float = 30f,
    var noiseGateMarginDb: Float = 3f,
    var minNoiseGateThresholdDbfs: Float = -55f,
    var maxNoiseGateThresholdDbfs: Float = -20f,
    envelopeAttackMs: Float = 3f,
    envelopeReleaseMs: Float = 250f,
    noiseFloorRiseMs: Float = 5000f,
) {
    private val attackCoeff = timeConstantToCoeff(envelopeAttackMs, sampleRate)
    private val releaseCoeff = timeConstantToCoeff(envelopeReleaseMs, sampleRate)
    private val noiseFloorRiseCoeff = timeConstantToCoeff(noiseFloorRiseMs, sampleRate)
    private var envelope = 0f

    /** Per-device ambient noise floor estimate (dBFS) the gate threshold is calibrated against. */
    private var noiseFloorDb = Float.POSITIVE_INFINITY

    fun process(input: ShortArray): ShortArray {
        val output = ShortArray(input.size)
        for (i in input.indices) {
            val sample = input[i] / 32768f
            val absSample = abs(sample)

            envelope = if (absSample > envelope) {
                envelope + (absSample - envelope) * attackCoeff
            } else {
                envelope + (absSample - envelope) * releaseCoeff
            }

            val levelDb = linearToDb(envelope)
            val computedGainDb = (targetLevelDbfs - levelDb).coerceIn(-maxAttenuationDb, maxGainDb)

            // Auto-calibrating noise floor - see the class doc. Float.POSITIVE_INFINITY as the
            // initial value means the very first sample always takes the instant-drop branch,
            // seeding the estimate immediately rather than climbing up to it from some arbitrary
            // fixed starting point.
            noiseFloorDb = if (levelDb < noiseFloorDb) {
                levelDb
            } else {
                noiseFloorDb + (levelDb - noiseFloorDb) * noiseFloorRiseCoeff
            }
            val adaptiveGateThresholdDbfs = (noiseFloorDb + noiseGateMarginDb)
                .coerceIn(minNoiseGateThresholdDbfs, maxNoiseGateThresholdDbfs)

            // Fade the correction out smoothly below the gate threshold instead of an on/off
            // step, so there's no audible click as speech resumes.
            val gateOpenness = ((levelDb - adaptiveGateThresholdDbfs) / GATE_TRANSITION_DB).coerceIn(0f, 1f)
            val gainDb = computedGainDb * gateOpenness

            val processed = (sample * dbToLinear(gainDb)).coerceIn(-0.98f, 0.98f)
            output[i] = (processed * 32768f).toInt().coerceIn(-32768, 32767).toShort()
        }
        return output
    }

    fun reset() {
        envelope = 0f
        noiseFloorDb = Float.POSITIVE_INFINITY
    }

    companion object {
        /** Target loudness with `mic_gain` at 0 - see [AudioEnhancer.setMicGainDb]. */
        const val BASE_TARGET_LEVEL_DBFS = -18f

        private const val GATE_TRANSITION_DB = 10f

        private fun timeConstantToCoeff(timeConstantMs: Float, sampleRate: Int): Float {
            val tau = (timeConstantMs / 1000f).coerceAtLeast(0.0001f)
            return (1f - exp(-1f / (tau * sampleRate))).coerceIn(0f, 1f)
        }

        private fun linearToDb(value: Float): Float =
            if (value > 1e-6f) 20f * log10(value) else -120f

        private fun dbToLinear(db: Float): Float = 10f.pow(db / 20f)
    }
}

/**
 * Common contract for [AudioEnhancer]'s noise suppression stage - see [SpectralNoiseSuppressor],
 * the hand-rolled spectral subtraction implementation.
 */
interface NoiseSuppressor {
    /** Accepts arbitrary-length chunks, returning exactly `input.size` samples. */
    fun process(input: ShortArray): ShortArray

    fun reset()

    /** Releases any native/OS resources held. No-op unless overridden. */
    fun release() {}
}

/**
 * Frequency-domain noise suppressor using overlap-add STFT spectral
 * subtraction (Berouti over-subtraction with a spectral floor), a standard,
 * well-understood approach to single-channel noise suppression.
 *
 * The noise magnitude per bin is tracked with a simple minimum-following
 * estimator (drops instantly, rises slowly) so it converges on the noise
 * floor found in gaps between speech without a separate VAD, and the gain
 * curve is smoothed across frames to reduce "musical noise" artifacts.
 *
 * Accepts arbitrary-length chunks per [process] call (buffering internally
 * to the fixed analysis hop size) so callers don't need to know or match any
 * particular frame size.
 */
class SpectralNoiseSuppressor(
    private val fftSize: Int = 256,
    private val sampleRate: Int = VACAAudioFormat.SAMPLE_RATE_HZ,
) : NoiseSuppressor {
    init {
        require(fftSize and (fftSize - 1) == 0) { "fftSize must be a power of two" }
    }

    private val hopSize = fftSize / 2
    private val numBins = fftSize / 2 + 1

    // Periodic (not symmetric) Hann window: at 50% overlap, adjacent analysis
    // windows sum to a constant, so overlap-add reconstructs the signal
    // correctly without needing a separate synthesis window.
    private val window = FloatArray(fftSize) { i -> 0.5f - 0.5f * cos(2.0 * PI * i / fftSize).toFloat() }

    private var prevInputTail = FloatArray(hopSize)
    private val overlapCarry = FloatArray(hopSize)
    private var pendingInput = FloatArray(0)
    private val outputFifo = FloatFifo()

    private val noiseMagnitude = FloatArray(numBins)
    private val prevGain = FloatArray(numBins) { 1f }
    private var framesSeen = 0

    /** Over-subtraction factor (alpha): higher suppresses more noise but risks musical artifacts. */
    var overSubtractionFactor = 3.0f

    /** Residual noise floor kept after subtraction (beta), to mask musical noise. */
    var spectralFloor = 0.06f

    /** How fast the noise-floor estimate is allowed to rise; it can drop instantly. */
    var noiseAdaptRateMs = 300f

    /** Temporal smoothing applied to the per-bin gain curve to reduce musical noise. */
    var gainSmoothing = 0.6f

    override fun process(input: ShortArray): ShortArray {
        if (input.isEmpty()) return input

        val combined = FloatArray(pendingInput.size + input.size)
        pendingInput.copyInto(combined)
        for (i in input.indices) combined[pendingInput.size + i] = input[i] / 32768f

        var offset = 0
        while (combined.size - offset >= hopSize) {
            val hop = combined.copyOfRange(offset, offset + hopSize)
            offset += hopSize
            outputFifo.push(processHop(hop))
        }
        pendingInput = if (offset < combined.size) combined.copyOfRange(offset, combined.size) else FloatArray(0)

        val out = outputFifo.pop(input.size)
        return ShortArray(out.size) { i -> (out[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
    }

    private fun processHop(hop: FloatArray): FloatArray {
        val frame = FloatArray(fftSize)
        prevInputTail.copyInto(frame, 0)
        hop.copyInto(frame, hopSize)
        prevInputTail = hop

        val spectrum = FloatArray(fftSize * 2)
        for (i in 0 until fftSize) {
            spectrum[i * 2] = frame[i] * window[i]
        }
        FFT.forward(spectrum, fftSize)

        framesSeen++
        val adaptCoeff = hopTimeConstantToCoeff(noiseAdaptRateMs)

        for (bin in 0 until numBins) {
            val re = spectrum[bin * 2]
            val im = spectrum[bin * 2 + 1]
            val magnitude = sqrt(re * re + im * im)

            noiseMagnitude[bin] = when {
                framesSeen <= NOISE_WARMUP_FRAMES || magnitude < noiseMagnitude[bin] -> magnitude
                else -> noiseMagnitude[bin] + (magnitude - noiseMagnitude[bin]) * adaptCoeff
            }

            val power = magnitude * magnitude
            val noisePower = noiseMagnitude[bin] * noiseMagnitude[bin]
            val subtractedPower = power - overSubtractionFactor * noisePower
            val floorPower = spectralFloor * power
            val outputPower = max(subtractedPower, floorPower)
            var gain = if (power > MIN_POWER) sqrt(outputPower / power) else 1f
            gain = prevGain[bin] + (gain - prevGain[bin]) * gainSmoothing
            prevGain[bin] = gain

            spectrum[bin * 2] = re * gain
            spectrum[bin * 2 + 1] = im * gain
            if (bin in 1 until numBins - 1) {
                // Real input -> conjugate-symmetric spectrum, so the mirror bin
                // can be derived directly instead of re-reading a stale value.
                val mirror = fftSize - bin
                spectrum[mirror * 2] = re * gain
                spectrum[mirror * 2 + 1] = -im * gain
            }
        }

        FFT.inverse(spectrum, fftSize)

        val outHop = FloatArray(hopSize)
        for (i in 0 until hopSize) {
            outHop[i] = spectrum[i * 2] + overlapCarry[i]
            overlapCarry[i] = spectrum[(i + hopSize) * 2]
        }
        return outHop
    }

    override fun reset() {
        prevInputTail.fill(0f)
        overlapCarry.fill(0f)
        pendingInput = FloatArray(0)
        noiseMagnitude.fill(0f)
        prevGain.fill(1f)
        framesSeen = 0
    }

    private fun hopTimeConstantToCoeff(timeConstantMs: Float): Float {
        val hopSeconds = hopSize.toFloat() / sampleRate
        val tau = (timeConstantMs / 1000f).coerceAtLeast(0.001f)
        return (1f - exp(-hopSeconds / tau)).coerceIn(0f, 1f)
    }

    companion object {
        private const val NOISE_WARMUP_FRAMES = 6
        private const val MIN_POWER = 1e-12f
    }
}

/** FIFO of float samples, decoupling the STFT's fixed hop size from arbitrary caller chunk sizes. */
private class FloatFifo {
    private val buffer = ArrayDeque<Float>()

    fun push(samples: FloatArray) {
        for (sample in samples) buffer.addLast(sample)
    }

    /** Removes and returns [count] samples, zero-padding (silence) if fewer are currently buffered. */
    fun pop(count: Int): FloatArray = FloatArray(count) { if (buffer.isEmpty()) 0f else buffer.removeFirst() }
}

/** In-place iterative radix-2 Cooley-Tukey FFT on interleaved [re0, im0, re1, im1, ...] arrays. */
private object FFT {
    fun forward(data: FloatArray, n: Int) = transform(data, n, invert = false)
    fun inverse(data: FloatArray, n: Int) = transform(data, n, invert = true)

    private fun transform(data: FloatArray, n: Int, invert: Boolean) {
        bitReverse(data, n)

        var len = 2
        while (len <= n) {
            val half = len / 2
            val angle = (if (invert) 1.0 else -1.0) * 2.0 * PI / len
            val wLenR = cos(angle).toFloat()
            val wLenI = sin(angle).toFloat()

            var i = 0
            while (i < n) {
                var wr = 1f
                var wi = 0f
                for (k in 0 until half) {
                    val evenIdx = (i + k) * 2
                    val oddIdx = (i + k + half) * 2
                    val evenR = data[evenIdx]
                    val evenI = data[evenIdx + 1]
                    val oddRRaw = data[oddIdx]
                    val oddIRaw = data[oddIdx + 1]
                    val oddR = oddRRaw * wr - oddIRaw * wi
                    val oddI = oddRRaw * wi + oddIRaw * wr

                    data[evenIdx] = evenR + oddR
                    data[evenIdx + 1] = evenI + oddI
                    data[oddIdx] = evenR - oddR
                    data[oddIdx + 1] = evenI - oddI

                    val nextWr = wr * wLenR - wi * wLenI
                    val nextWi = wr * wLenI + wi * wLenR
                    wr = nextWr
                    wi = nextWi
                }
                i += len
            }
            len = len shl 1
        }

        if (invert) {
            for (i in 0 until n) {
                data[i * 2] /= n
                data[i * 2 + 1] /= n
            }
        }
    }

    private fun bitReverse(data: FloatArray, n: Int) {
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val ri = i * 2
                val rj = j * 2
                var t = data[ri]; data[ri] = data[rj]; data[rj] = t
                t = data[ri + 1]; data[ri + 1] = data[rj + 1]; data[rj + 1] = t
            }
        }
    }
}
