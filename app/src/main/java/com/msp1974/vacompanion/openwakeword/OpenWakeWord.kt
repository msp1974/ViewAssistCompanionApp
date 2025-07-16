package com.msp1974.vacompanion.openwakeword

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OrtSession.SessionOptions
import android.content.Context
import android.content.res.AssetManager
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.utils.Logger
import java.io.IOException
import java.nio.FloatBuffer
import java.util.ArrayDeque
import java.util.Collections
import java.util.Random
import kotlin.math.max

enum class SupportedWakewords { alexa, hey_jarvis, hey_mycroft, hey_rhasspy, ok_nabu, ok_computer }

class ONNXModelRunner(var assetManager: AssetManager, wakeWord: String) {
    private var log = Logger()
    var ort_session: OrtSession? = null
    var ort_env: OrtEnvironment = OrtEnvironment.getEnvironment()

    lateinit var embedBytes: ByteArray
    var embedding_model: OrtSession? = null
    lateinit var melBytes: ByteArray
    var melspec_model: OrtSession? = null

    init {
        try {
            val wakeWordFile = wakeWord.lowercase() + ".onnx"
            ort_session = ort_env.createSession(readModelFile(assetManager, wakeWordFile))
        } catch (e: IOException) {
            log.e("Error reading model file: $e")
            throw RuntimeException(e)
        }

        // Load the ONNX model from the assets folder
    }

    fun end() {
        if (melspec_model != null) {
            melspec_model?.close()
        }
        if (embedding_model != null) {
            embedding_model?.close()
        }
        if (ort_session != null) {
            ort_session?.close()
        }
        ort_env.close()
    }

    @Throws(OrtException::class, IOException::class)
    fun get_mel_spectrogram(inputArray: FloatArray): Array<FloatArray>? {
        if (melspec_model == null) {
            assetManager.open("melspectrogram.onnx").use { modelInputStream ->
                melBytes = ByteArray(modelInputStream.available())
                modelInputStream.read(melBytes)
            }
            val sessionOptions = SessionOptions()
            sessionOptions.setInterOpNumThreads(1)
            sessionOptions.setIntraOpNumThreads(1)

            melspec_model = ort_env.createSession(melBytes, sessionOptions)
        }


        var outputArray: Array<FloatArray>? = null
        val SAMPLES = inputArray.size
        // Convert the input array to ONNX Tensor
        val floatBuffer = FloatBuffer.wrap(inputArray)
        val inputTensor = OnnxTensor.createTensor(
            ort_env, floatBuffer,
            longArrayOf(BATCH_SIZE.toLong(), SAMPLES.toLong())
        )

        // Run the model
        // Adjust this based on the actual expected output shape
        try {
            melspec_model
                ?.run(
                    Collections.singletonMap(
                        melspec_model!!.inputNames.iterator().next(),
                        inputTensor
                    )
                ).use { results ->
                    val outputTensor = results?.get(0)?.value as Array<Array<Array<FloatArray>>>
                    // Here you need to cast the output appropriately
                    // Object outputObject = outputTensor.getValue();

                    // Check the actual type of 'outputObject' and cast accordingly
                    // The following is an assumed cast based on your error message
                    val squeezed = squeeze(outputTensor)
                    outputArray = applyMelSpecTransform(squeezed)
                }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            inputTensor?.close()
        }
        return outputArray
    }

    @Throws(OrtException::class, IOException::class)
    fun generateEmbeddings(input: Array<Array<Array<FloatArray>>>): Array<FloatArray>? {
        //OrtEnvironment env = OrtEnvironment.getEnvironment();
        if (embedding_model == null) {
            val `is` = assetManager.open("embedding_model.onnx")
            embedBytes = ByteArray(`is`.available())
            `is`.read(embedBytes)
            `is`.close()

            val sessionOptions = SessionOptions()
            sessionOptions.setInterOpNumThreads(1)
            sessionOptions.setIntraOpNumThreads(1)

            embedding_model = ort_env.createSession(embedBytes, sessionOptions)
        }

        val inputTensor = OnnxTensor.createTensor(ort_env, input)
        try {
            embedding_model!!.run(Collections.singletonMap("input_1", inputTensor)).use { results ->
                // Extract the output tensor
                val rawOutput = results[0].value as Array<Array<Array<FloatArray>>>

                // Assuming the output shape is (41, 1, 1, 96), and we want to reshape it to
                // (41, 96)
                val reshapedOutput = Array(rawOutput.size) {
                    FloatArray(
                        rawOutput[0][0][0].size
                    )
                }
                for (i in rawOutput.indices) {
                    System.arraycopy(
                        rawOutput[i][0][0], 0,
                        reshapedOutput[i], 0, rawOutput[i][0][0].size
                    )
                }
                return reshapedOutput
            }
        } catch (e: Exception) {
            log.d("not_predicted " + e.message)
        } finally {
            inputTensor?.close() // You're doing this, which is good.
        }
        return null
    }

    @Throws(OrtException::class)
    fun predictWakeWord(inputArray: Array<Array<FloatArray>>): String {
        var result = arrayOfNulls<FloatArray>(0)
        var resultant = ""

        var inputTensor: OnnxTensor? = null

        try {
            // Create a tensor from the input array
            inputTensor = OnnxTensor.createTensor(ort_env, inputArray)
            // Run the inference
            val outputs = ort_session
                ?.run(
                    Collections.singletonMap(
                        ort_session!!.inputNames.iterator().next(),
                        inputTensor
                    )
                )
            // Extract the output tensor, convert it to the desired type
            result = outputs?.get(0)?.value as Array<FloatArray?>
            resultant = String.format("%.5f", result[0]!![0].toDouble())
        } catch (e: OrtException) {
            e.printStackTrace()
        } finally {
            inputTensor?.close()
            // Add this to ensure the session is properly closed.
        }
        return resultant
    }

    @Throws(IOException::class)
    private fun readModelFile(assetManager: AssetManager, filename: String): ByteArray {
        assetManager.open(filename).use { `is` ->
            val buffer = ByteArray(`is`.available())
            `is`.read(buffer)
            return buffer
        }
    }

    companion object {
        private const val BATCH_SIZE = 1 // Replace with your batch size

        fun squeeze(originalArray: Array<Array<Array<FloatArray>>>): Array<FloatArray> {
            val squeezedArray = Array(originalArray[0][0].size) {
                FloatArray(
                    originalArray[0][0][0].size
                )
            }
            for (i in originalArray[0][0].indices) {
                for (j in originalArray[0][0][0].indices) {
                    squeezedArray[i][j] = originalArray[0][0][i][j]
                }
            }

            return squeezedArray
        }

        fun applyMelSpecTransform(array: Array<FloatArray>): Array<FloatArray> {
            val transformedArray = Array(array.size) {
                FloatArray(
                    array[0].size
                )
            }

            for (i in array.indices) {
                for (j in array[i].indices) {
                    transformedArray[i][j] = array[i][j] / 10.0f + 2.0f
                }
            }

            return transformedArray
        }

        fun getWakeWords(): List<String> {
            return enumValues<SupportedWakewords>().map { it.name }
        }
    }
}

class Model internal constructor(context: Context, modelRunner: ONNXModelRunner?) {
    var n_prepared_samples: Int = 1280
    var sampleRate: Int = 16000
    var melspectrogramMaxLen: Int = 10 * 97
    var feature_buffer_max_len: Int = 120
    lateinit var modelRunner: ONNXModelRunner
    var featureBuffer: Array<FloatArray>? = null
    var raw_data_buffer: ArrayDeque<Float> = ArrayDeque(sampleRate * 10)
    var raw_data_remainder: FloatArray = FloatArray(0)
    var melspectrogramBuffer: Array<FloatArray?>
    var accumulated_samples: Int = 0
    val config = APPConfig.getInstance(context)


    init {
        melspectrogramBuffer = Array(76) { FloatArray(32) }
        for (i in melspectrogramBuffer.indices) {
            for (j in melspectrogramBuffer[i]!!.indices) {
                melspectrogramBuffer[i]!![j] = 1.0f // Assign 1.0f to simulate numpy.ones
            }
        }
        if (modelRunner != null) {
            this.modelRunner = modelRunner
        }
        try {
            this.featureBuffer = this._getEmbeddings(this.generateRandomIntArray(16000 * 4), 76, 8)
        } catch (e: Exception) {
            print(e.message)
        }
    }

    fun stop() {
        modelRunner.end()
    }

    fun getFeatures(nFeatureFrames: Int, startNdx: Int): Array<Array<FloatArray>> {
        var startNdx = startNdx
        val endNdx: Int
        if (startNdx != -1) {
            endNdx =
                if (startNdx + nFeatureFrames != 0) (startNdx + nFeatureFrames) else featureBuffer!!.size
        } else {
            startNdx = max(
                0.0,
                (featureBuffer!!.size - nFeatureFrames).toDouble()
            ).toInt() // Ensure startNdx is not negative
            endNdx = featureBuffer!!.size
        }

        val length = endNdx - startNdx
        val result = Array(1) {
            Array(length) {
                FloatArray(
                    featureBuffer!![0].size
                )
            }
        }  // Assuming the second dimension has fixed

        // size.
        for (i in 0..<length) {
            System.arraycopy(
                featureBuffer!![startNdx + i], 0,
                result[0][i], 0, featureBuffer!![startNdx + i].size
            )
        }

        return result
    }

    // Java equivalent to _get_embeddings method
    @Throws(OrtException::class, IOException::class)
    private fun _getEmbeddings(x: FloatArray, windowSize: Int, stepSize: Int): Array<FloatArray>? {
        val spec =
            modelRunner.get_mel_spectrogram(x) // Assuming this method exists and returns float[][]
        val windows = ArrayList<Array<FloatArray>>()

        run {
            var i = 0
            while (i <= spec!!.size - windowSize) {
                val window = Array(windowSize) {
                    FloatArray(
                        spec[0].size
                    )
                }

                for (j in 0..<windowSize) {
                    System.arraycopy(spec[i + j], 0, window[j], 0, spec[0].size)
                }

                // Check if the window is full-sized (not truncated)
                if (window.size == windowSize) {
                    windows.add(window)
                }
                i += stepSize
            }
        }

        // Convert ArrayList to array and add the required extra dimension
        val batch = Array(windows.size) {
            Array(windowSize) {
                Array(
                    spec!![0].size
                ) { FloatArray(1) }
            }
        }
        for (i in windows.indices) {
            for (j in 0..<windowSize) {
                for (k in spec!![0].indices) {
                    batch[i][j][k][0] = windows[i][j][k] // Add the extra dimension here
                }
            }
        }

        try {
            val result = modelRunner.generateEmbeddings(batch)
            return result
        } catch (e: IOException) {
            throw RuntimeException(e)
        }
        // Assuming embeddingModelPredict is defined and returns float[][]
    }

    // Utility function to generate random int array, equivalent to
    // np.random.randint
    private fun generateRandomIntArray(size: Int): FloatArray {
        val arr = FloatArray(size)
        val random = Random()
        for (i in 0..<size) {
            arr[i] = random.nextInt(2000).toFloat() - 1000 // range [-1000, 1000)
        }
        return arr
    }

    fun bufferRawData(x: FloatArray?) { // Change double[] to match your actual data type
        // Check if input x is not null
        if (x != null) {
            // Check if raw_data_buffer has enough space, if not, remove old data
            while (raw_data_buffer.size + x.size > sampleRate * 10) {
                raw_data_buffer.poll() // or pollFirst() - removes and returns the first element of this deque
            }
            for (value in x) {
                raw_data_buffer.offer(value) // or offerLast() - Inserts the specified element at the end of this deque
            }
        }
    }

    fun streamingMelSpectrogram(n_samples: Int) {
        require(raw_data_buffer.size >= 400) { "The number of input frames must be at least 400 samples @ 16kHz (25 ms)!" }

        // Converting the last n_samples + 480 (3 * 160) samples from raw_data_buffer to
        // an ArrayList
        val tempArray = FloatArray(n_samples + 480) // 160 * 3 = 480
        val rawDataArray: Array<Any> = raw_data_buffer.toTypedArray()
        val  a = max(0.0,(rawDataArray.size - n_samples - 480).toDouble())
        val b = rawDataArray.size.toDouble() -1
        var i = a
        while (i < b) {
            tempArray[(i - a).toInt()] =  rawDataArray[i.toInt()] as Float
            i++
        }

        // Assuming getMelSpectrogram returns a two-dimensional float array
        val new_mel_spectrogram: Array<FloatArray>?
        try {
            new_mel_spectrogram = modelRunner.get_mel_spectrogram(tempArray)
        } catch (e: OrtException) {
            throw RuntimeException(e)
        } catch (e: IOException) {
            throw RuntimeException(e)
        }

        // Combine existing melspectrogram_buffer with new_mel_spectrogram
        val combined =
            arrayOfNulls<FloatArray>(melspectrogramBuffer.size + new_mel_spectrogram!!.size)

        System.arraycopy(
            this.melspectrogramBuffer, 0, combined, 0,
            melspectrogramBuffer.size
        )
        System.arraycopy(
            new_mel_spectrogram, 0, combined,
            melspectrogramBuffer.size,
            new_mel_spectrogram.size
        )
        this.melspectrogramBuffer = combined

        // Trim the melspectrogram_buffer if it exceeds the max length
        if (melspectrogramBuffer.size > melspectrogramMaxLen) {
            val trimmed = arrayOfNulls<FloatArray>(melspectrogramMaxLen)
            System.arraycopy(
                this.melspectrogramBuffer,
                melspectrogramBuffer.size - melspectrogramMaxLen,
                trimmed, 0, melspectrogramMaxLen
            )
            this.melspectrogramBuffer = trimmed
        }
    }

    fun streaming_features(audiobuffer: FloatArray): Int {
        var audiobuffer = audiobuffer
        var processed_samples = 0
        this.accumulated_samples = 0
        if (raw_data_remainder.size != 0) {
            // Create a new array to hold the result of concatenation
            val concatenatedArray = FloatArray(raw_data_remainder.size + audiobuffer.size)

            // Copy elements from raw_data_remainder to the new array
            System.arraycopy(raw_data_remainder, 0, concatenatedArray, 0, raw_data_remainder.size)

            // Copy elements from x to the new array, starting right after the last element
            // of raw_data_remainder
            System.arraycopy(
                audiobuffer,
                0,
                concatenatedArray,
                raw_data_remainder.size,
                audiobuffer.size
            )

            // Assign the concatenated array back to x
            audiobuffer = concatenatedArray

            // Reset raw_data_remainder to an empty array
            raw_data_remainder = FloatArray(0)
        }

        if (this.accumulated_samples + audiobuffer.size >= 1280) {
            val remainder = (this.accumulated_samples + audiobuffer.size) % 1280
            if (remainder != 0) {
                // Create an array for x_even_chunks that excludes the last 'remainder' elements
                // of 'x'
                val x_even_chunks = FloatArray(audiobuffer.size - remainder)
                System.arraycopy(audiobuffer, 0, x_even_chunks, 0, audiobuffer.size - remainder)

                // Buffer the even chunks of data
                this.bufferRawData(x_even_chunks)

                // Update accumulated_samples by the length of x_even_chunks
                this.accumulated_samples += x_even_chunks.size

                // Set raw_data_remainder to the last 'remainder' elements of 'x'
                this.raw_data_remainder = FloatArray(remainder)
                System.arraycopy(
                    audiobuffer, audiobuffer.size - remainder,
                    this.raw_data_remainder, 0, remainder
                )
            } else if (remainder == 0) {
                // Buffer the entire array 'x'
                this.bufferRawData(audiobuffer)

                // Update accumulated_samples by the length of 'x'
                this.accumulated_samples += audiobuffer.size

                // Set raw_data_remainder to an empty array
                this.raw_data_remainder = FloatArray(0)
            }
        } else {
            this.accumulated_samples += audiobuffer.size
            this.bufferRawData(audiobuffer) // Adapt this method according to your class
        }

        if (this.accumulated_samples >= 1280 && this.accumulated_samples % 1280 == 0) {
            this.streamingMelSpectrogram(this.accumulated_samples)

            val x = Array(1) { Array(76) { Array(32) { FloatArray(1) } } }

            for (i in (accumulated_samples / 1280) - 1 downTo 0) {
                var ndx = -8 * i
                if (ndx == 0) {
                    ndx = melspectrogramBuffer.size
                }
                // Calculate start and end indices for slicing
                val start = max(0.0, (ndx - 76).toDouble()).toInt()
                val end = ndx

                var j = start
                var k = 0
                while (j < end) {
                    for (w in 0..31) {
                        x[0][k][w][0] = melspectrogramBuffer[j]!![w]
                    }
                    j++
                    k++
                }
                if (x[0].size == 76) {
                    try {
                        val newFeatures = modelRunner.generateEmbeddings(x)
                        if (featureBuffer == null) {
                            featureBuffer = newFeatures
                        } else {
                            val totalRows = featureBuffer!!.size + newFeatures!!.size
                            val numColumns =
                                featureBuffer!![0].size // Assuming all rows have the same length
                            val updatedBuffer = Array(totalRows) { FloatArray(numColumns) }

                            // Copy original featureBuffer into updatedBuffer
                            for (l in featureBuffer!!.indices) {
                                System.arraycopy(
                                    featureBuffer!![l], 0,
                                    updatedBuffer[l], 0, featureBuffer!![l].size
                                )
                            }

                            // Copy newFeatures into the updatedBuffer, starting after the last original row
                            for (k in newFeatures.indices) {
                                System.arraycopy(
                                    newFeatures[k], 0, updatedBuffer[k + featureBuffer!!.size], 0,
                                    newFeatures[k].size
                                )
                            }

                            featureBuffer = updatedBuffer
                        }
                    } catch (e: Exception) {
                        throw RuntimeException(e)
                    }
                }
            }
            processed_samples = this.accumulated_samples
            this.accumulated_samples = 0
        }
        if (featureBuffer!!.size > feature_buffer_max_len) {
            val trimmedFeatureBuffer = Array(feature_buffer_max_len) {
                FloatArray(
                    featureBuffer!![0].size
                )
            }

            // Copy the last featureBufferMaxLen rows of featureBuffer into
            // trimmedFeatureBuffer
            for (i in 0..<feature_buffer_max_len) {
                trimmedFeatureBuffer[i] =
                    featureBuffer!![featureBuffer!!.size - feature_buffer_max_len + i]
            }

            // Update featureBuffer to point to the new trimmedFeatureBuffer
            featureBuffer = trimmedFeatureBuffer
        }
        return if (processed_samples != 0) processed_samples else this.accumulated_samples
    }

    fun predict_WakeWord(audioBuffer: FloatArray): String {
        n_prepared_samples = this.streaming_features(audioBuffer)
        val res = this.getFeatures(16, -1)
        var result = ""
        try {
            result = modelRunner.predictWakeWord(res)
        } catch (e: OrtException) {
            throw RuntimeException(e)
        }
        return result
    }
}
