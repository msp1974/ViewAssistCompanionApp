package com.msp1974.vacompanion

import android.content.Context.AUDIO_SERVICE
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Process
import java.io.IOException
import kotlin.reflect.KProperty

internal interface AudioInCallback {
    fun onAudio(audioBuffer: ShortArray)
    fun onError(err: String)
}

internal class AudioManager(context: Context) {
    private val audioManager = context.getSystemService(AUDIO_SERVICE) as AudioManager

    private fun streamMaxVolume(stream: Int): Int {
        return audioManager.getStreamMaxVolume(stream)
    }

    fun setVolume(stream: Int, volume: Float) {
        audioManager.setStreamVolume(stream, (streamMaxVolume(stream)*volume).toInt(), 0)
    }

    fun getVolume(stream: Int): Float {
        return audioManager.getStreamVolume(stream).toFloat() / streamMaxVolume(stream).toFloat()
    }
}

internal class AudioRecorderThread(private val cbAudio: AudioInCallback) : Thread() {
    private lateinit var audioRecord: AudioRecord
    private var isRecording = false
    private var config = Global.config

    @SuppressLint("MissingPermission")
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

internal class WakeWordSoundPlayer(private val context: Context, private val resId: Int) {
    private lateinit var wwSound: MediaPlayer
    private val stream = AudioManager.STREAM_NOTIFICATION

    fun play() {
        // Create MediaPlayer instance with a resource audio resource
        wwSound = MediaPlayer.create(context, resId)
        wwSound.start()
    }
}

internal class MusicPlayer() {
    private val config = Global.config
    private val status = Global.status
    private var mediaPlayer: MediaPlayer = MediaPlayer()
    private var currentVolume: Float = config.musicVolume
    private var statusListener: InterfaceStatusChangeListener? = null

    fun play(url: String) {
        try {
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(url)
                prepare() // might take long! (for buffering, etc)
            }
            mediaPlayer.start()
            Global.log.i("Music started")
        } catch (e: IllegalStateException) {
            e.printStackTrace()
        } catch (e: IOException) {
            e.printStackTrace()
        }

        statusListener = object : InterfaceStatusChangeListener {
            override fun onStatusChange(property: KProperty<*>, oldValue: Any, newValue: Any) {
                Global.log.i("Status changed to ${property.name}: $newValue")
                if (property.name == "pipelineStatus" && newValue == PipelineStatus.LISTENING || newValue == PipelineStatus.STREAMING) {
                    duckVolume()
                }
                if (property.name == "pipelineStatus" && newValue == PipelineStatus.INACTIVE) {
                    unDuckVolume()
                }

            }
        }
        status.statusChangeListeners.add(statusListener as InterfaceStatusChangeListener)

    }

    fun pause() {
        mediaPlayer.pause()
        Global.log.i("Music paused")
    }

    fun resume() {
        mediaPlayer.start()
        Global.log.i("Music resumed")
    }

    fun stop() {
        if (mediaPlayer.isPlaying) {
            mediaPlayer.stop()
            mediaPlayer.release()
            status.statusChangeListeners.remove(statusListener)
            Global.log.i("Music stopped")
        }
    }

    fun setVolume(volume: Float) {
        mediaPlayer.setVolume(volume, volume)
        currentVolume = volume
        Global.log.i("Music volume set to $volume")
    }

    fun duckVolume() {
        Global.log.i("Ducking music volume")
        val vol = currentVolume * (config.duckingPercent.toFloat() / 100)
        mediaPlayer.setVolume(vol, vol)
    }

    fun unDuckVolume() {
        Global.log.i("Restoring music volume")
        setVolume(currentVolume)
    }

}

internal class PCMMediaPlayer(context: Context) {
    private val stream = AudioManager.STREAM_NOTIFICATION
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


