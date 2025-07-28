package com.msp1974.vacompanion.audio

import android.annotation.SuppressLint
import android.content.Context
import android.content.Context.AUDIO_SERVICE
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import androidx.core.content.ContextCompat.getSystemService
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.utils.Logger

internal class AudioRecorderThread(val context: Context, val cbAudio: AudioInCallback) : Thread() {
    private lateinit var audioRecord: AudioRecord
    private var isRecording = false
    private var config: APPConfig = APPConfig.getInstance(context)
    private var log = Logger()
    private var audioDSP = AudioDSP()

    @SuppressLint("MissingPermission", "ServiceCast")
    override fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

        // Ensure the buffer size is at least as large as the chunk size needed
        var minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSizeInShorts = 1280 // This is your 'chunk size' in terms of shorts
        if (minBufferSize / 2 < bufferSizeInShorts) {
            minBufferSize =
                bufferSizeInShorts * 2 // Ensure buffer is large enough, adjusting if necessary
        }

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            minBufferSize
        )

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            // Initialization error handling
            return
        }

        // Set auto gain control
        //if (AutomaticGainControl.isAvailable()) {
        //    log.i("Enabling auto gain control")
        //    val agc = AutomaticGainControl.create(audioRecord.audioSessionId)
        //    agc.enabled = true
        //}

        val audioBuffer = ShortArray(bufferSizeInShorts) // Allocate buffer for 'chunk size' shorts
        audioRecord.startRecording()
        isRecording = true

        while (isRecording) {
            // Reading data from the microphone in chunks
            if (!config.isMuted) {
                audioRecord.read(audioBuffer, 0, audioBuffer.size)

                cbAudio.onAudio(audioDSP.preProcessAudio(audioBuffer, config.micGain))
            }
        }

        releaseResources()
    }

    fun stopRecording() {
        isRecording = false
    }

    private fun releaseResources() {
        audioRecord.stop()
        audioRecord.release()
    }

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }
}