package com.msp1974.vacompanion.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack

class PCMMediaPlayer(context: Context) {
    private var sampleRate = 22050
    private var channelCount = 1
    private var bytesPerSample = 2
    private val frameSize = 480 // samples per channel
    private val bufferSize = frameSize * channelCount * bytesPerSample
    private val buffer = ByteArray(bufferSize)
    private var audioTrack: AudioTrack? = null
    var isPlaying = false

    private fun createAudioTrack(): AudioTrack {
        val channels = if (channelCount == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val bytesPerSample = if (bytesPerSample == 2) AudioFormat.ENCODING_PCM_16BIT else AudioFormat.ENCODING_PCM_8BIT

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setChannelMask(channels)
            .setEncoding(bytesPerSample)
            .build()

        return AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(audioFormat)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(
                AudioTrack.getMinBufferSize(
                    sampleRate,
                    channels,
                    bytesPerSample
                )
            )
            .build()
    }

    fun play() {
        if (isPlaying) return

        isPlaying = true
        audioTrack = createAudioTrack().apply { play() }

        Thread {
            try {
                while (isPlaying && buffer.size == bufferSize) {
                    Thread.sleep(10)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    fun writeAudio(buffer: ByteArray) {
        this.audioTrack?.write(buffer, 0, buffer.size)
    }

    fun stop() {
        isPlaying = false
        audioTrack?.apply {
            stop()
            flush()
            release()
        }
        audioTrack = null
    }
}