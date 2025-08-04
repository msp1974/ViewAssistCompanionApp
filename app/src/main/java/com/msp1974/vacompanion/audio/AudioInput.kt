package com.msp1974.vacompanion.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import com.msp1974.vacompanion.settings.APPConfig

internal class AudioRecorder(val context: Context, val cbAudio: AudioInCallback) {
    private lateinit var audioRecord: AudioRecord
    private var isRecording = false
    private var config: APPConfig = APPConfig.getInstance(context)

    @SuppressLint("MissingPermission", "ServiceCast")
    fun start() {
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

        val audioBuffer = ShortArray(bufferSizeInShorts) // Allocate buffer for 'chunk size' shorts
        audioRecord.startRecording()
        isRecording = true

        while (isRecording) {
            // Reading data from the microphone in chunks
            if (!config.isMuted) {
                audioRecord.read(audioBuffer, 0, audioBuffer.size)

                cbAudio.onAudio(audioBuffer)
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