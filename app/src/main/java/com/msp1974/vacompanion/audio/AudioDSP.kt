package com.msp1974.vacompanion.audio

import com.msp1974.vacompanion.utils.Logger
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow


class AudioDSP {

    fun preProcessAudio(input: ShortArray, sensitivity: Int): ShortArray {
        //var audioBuffer = normaliseAudioBuffer(input)
        //audioBuffer = bandPassFilter(audioBuffer, 1500f, 2500f, 16000f)
        val gain = autoGain(input, sensitivity)
        val output = input.map { (it * gain).toInt().toShort() }.toShortArray()
        return output
    }

    fun autoGain(audioBuffer: ShortArray, sensitivity: Int = 0): Float {
        // Auto gain
        val max = audioBuffer.maxOrNull() ?: 0
        val min = audioBuffer.minOrNull() ?: 0

        val range = max - min
        // Range will be 0 if mic is muted
        if (range == 0) {
            return 0f
        }
        val gain = (12000f + (sensitivity * 800)) / range
        return gain
    }


    fun lowPassSPFilter(input: FloatArray, freq: Float, sampleRate: Float): FloatArray {
        val filter = LowPassSPFilter(freq, sampleRate)
        return filter.process(input)
    }

    fun lowPassFSFilter(input: FloatArray, freq: Float, sampleRate: Float): FloatArray {
        val filter = LowPassFSFilter(freq, sampleRate)
        return filter.process(input)
    }

    fun highPassFilter(input: FloatArray, freq: Float, sampleRate: Float): FloatArray {
        val filter = HighPassFilter(freq, sampleRate)
        return filter.process(input)
    }

    fun bandPassFilter(input: FloatArray, freq: Float, bandwidth: Float, sampleRate: Float): FloatArray {
        val filter = BandPassFilter(freq, bandwidth, sampleRate)
        return filter.process(input)
    }

    fun normaliseAudioBuffer(audioBuffer: ShortArray): FloatArray {
        val floatBuffer = audioBuffer.map { (it.toFloat() / 32768.0f) }.toFloatArray()
        return floatBuffer
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

}

class LowPassSPFilter: IIRFilter {
    constructor(freq: Float, sampleRate: Float) : super(freq, sampleRate) {

    }

    override fun calcCoefficient() {
        val fracFreq = getFrequency() / sampleRate
        val x = exp(-2 * Math.PI * fracFreq).toFloat()
        a = floatArrayOf(1 - x)
        b = floatArrayOf(x)
    }

}

class LowPassFSFilter: IIRFilter {
    constructor(freq: Float, sampleRate: Float) : super(freq, sampleRate) {

    }

    override fun calcCoefficient() {
        val freqFrac = getFrequency() / sampleRate
        val x = exp(-14.445 * freqFrac).toFloat()
        a = floatArrayOf((1 - x).toDouble().pow(4.0).toFloat())
        b = floatArrayOf(4 * x, -6 * x * x, 4 * x * x * x, -x * x * x * x)
    }

}

class HighPassFilter: IIRFilter {
    constructor(freq: Float, sampleRate: Float) : super(freq, sampleRate) {

    }

    override fun calcCoefficient() {
        val freqFrac = getFrequency() / sampleRate
        val x = exp(-2 * Math.PI * freqFrac).toFloat()
        a = floatArrayOf((1+x)/2, -(1+x)/2)
        b = floatArrayOf(x)
    }
}

class BandPassFilter: IIRFilter {
/**
 * Constructs a band pass filter with the requested center frequency,
 * bandwidth and sample rate.
 *
 * @param freq
 * the center frequency of the band to pass (in Hz)
 * @param bandWidth
 * the width of the band to pass (in Hz)
 * @param sampleRate
 * the sample rate of audio that will be filtered by this filter
 */
    private var bw = 0f

    constructor(freq: Float, bandWidth: Float, sampleRate: Float) : super(freq, sampleRate) {
        setBandWidth(bandWidth)
    }

    fun setBandWidth(bandWidth: Float) {
        bw = bandWidth / sampleRate
        calcCoefficient()
    }

    override fun calcCoefficient() {
        val R = 1 - 3 * bw
        val fracFreq = getFrequency() / sampleRate
        val T = 2 * cos(2 * Math.PI * fracFreq).toFloat()
        val K = (1 - R * T + R * R) / (2 - T)
        a = floatArrayOf(1 - K, (K - R) * T, R * R - K)
        b = floatArrayOf(R * T, -R * R)
    }
}

abstract class IIRFilter(private var frequency: Float, protected val sampleRate: Float) {
    /** The b coefficients.  */
    protected lateinit var b: FloatArray

    /** The a coefficients.  */
    protected lateinit var a: FloatArray

    /**
     * The input values to the left of the output value currently being
     * calculated.
     */
    protected var `in`: FloatArray

    /** The previous output values.  */
    protected var out: FloatArray


    /**
     * Constructs an IIRFilter with the given cutoff frequency that will be used
     * to filter audio recorded at `sampleRate`.
     *
     * @param frequency
     * the cutoff frequency
     * @param sampleRate
     * the sample rate of audio to be filtered
     */
    init {
        calcCoefficient()
        `in` = FloatArray(a.size)
        out = FloatArray(b.size)
    }

    fun setFrequency(freq: Float) {
        this.frequency = freq
        calcCoefficient()
    }

    /**
     * Returns the cutoff frequency (in Hz).
     *
     * @return the current cutoff frequency (in Hz).
     */
    protected fun getFrequency(): Float {
        return frequency
    }

    /**
     * Calculates the coefficients of the filter using the current cutoff
     * frequency. To make your own IIRFilters, you must extend IIRFilter and
     * implement this function. The frequency is expressed as a fraction of the
     * sample rate. When filling the coefficient arrays, be aware that
     * `b[0]` corresponds to the coefficient
     * `b<sub>1</sub>`.
     *
     */
    protected abstract fun calcCoefficient()


    fun process(audioData: FloatArray): FloatArray {
        for (i in 0..<audioData.size) {
            //shift the in array
            System.arraycopy(`in`, 0, `in`, 1, `in`.size - 1)
            `in`[0] = audioData[i]

            //calculate y based on a and b coefficients
            //and in and out.
            var y = 0f
            for (j in a.indices) {
                y += a[j] * `in`[j]
            }
            for (j in b.indices) {
                y += b[j] * out[j]
            }
            //shift the out array
            System.arraycopy(out, 0, out, 1, out.size - 1)
            out[0] = y

            audioData[i] = y
        }
        return audioData
    }
}