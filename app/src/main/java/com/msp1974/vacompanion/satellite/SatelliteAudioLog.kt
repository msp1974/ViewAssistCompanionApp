package com.msp1974.vacompanion.satellite

import com.msp1974.vacompanion.settings.APPConfig
import timber.log.Timber
import java.time.LocalTime
import java.time.format.DateTimeFormatter

data class AudioLogEntry(
    val timestamp: String,
    val wakeWord: String,
    val maxScore: Float,
    val level: Float? = null,
    var request: String,
    var response: String,
    var audioFilePath: String? = null,
    var isPlaying: Boolean = false
)

class SatelliteAudioLog(val config: APPConfig) {
    private val log: MutableMap<Long, AudioLogEntry> = mutableMapOf()
    private val lock = Any()

    private var audioRecorder: AudioPipelineRecorder? = null
    var onPlaybackStatusChanged: (Boolean) -> Unit = {}

    init {
        initAudioRecorder()
    }

    fun onWakeWordDetected(eventId: Long, wakeWord: String, score: Float, level: Float?) {
        if (config.recordingWakewordEnabled) {
            if (audioRecorder == null) {
                initAudioRecorder()
            }
            audioRecorder?.startRecording(eventId)
        } else {
            audioRecorder?.stopRecording()
        }

        synchronized(lock) {
            log[eventId] = AudioLogEntry(
                timestamp = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                wakeWord = wakeWord,
                maxScore = score,
                level = level,
                request = "",
                response = ""
            )
        }
    }

    fun onRequest(eventId: Long, request: String) {
        synchronized(lock) {
            val entry = log[eventId]
            if (entry != null) {
                entry.request = request
            }
            onEndAudioPipeline()
        }
    }

    fun onResponse(eventId: Long, response: String) {
        synchronized(lock) {
            val entry = log[eventId]
            if (entry != null) {
                entry.response = response
            }
        }
    }

    fun onErrorResponse(eventId: Long, error: String) {
        synchronized(lock) {
            val entry = log[eventId]
            if (entry != null) {
                if (entry.request.isEmpty()) {
                    entry.request = error
                } else {
                    entry.response = error
                }
            }
            onEndAudioPipeline()
        }
    }

    fun onEndAudioPipeline() {
        audioRecorder?.stopRecording()
    }

    fun onAudio(audio: ShortArray) {
        if (audioRecorder != null) {
            audioRecorder?.onAudio(audio)
        }
    }

    fun getLog(): MutableMap<Long, AudioLogEntry> {
        synchronized(lock) {
            return log.toMutableMap()
        }
    }

    private var currentlyPlayingEventId: Long? = null

    fun playRecording(eventId: Long) {
        synchronized(lock) {
            val entry = log[eventId]
            if (entry != null && entry.audioFilePath != null) {
                if (currentlyPlayingEventId == eventId) {
                    audioRecorder?.stopPlayback()
                } else {
                    currentlyPlayingEventId = eventId
                    Timber.d("Playing recording: ${entry.audioFilePath}")
                    audioRecorder?.playRecording(entry.audioFilePath!!, eventId)
                }
            }
        }
    }

    private fun initAudioRecorder() {
        audioRecorder = AudioPipelineRecorder(config.context).apply {
            onRecordingSaved = { info ->
                synchronized(lock) {
                    val entry = log[info.eventId]
                    if (entry != null) {
                        entry.audioFilePath = info.file.absolutePath
                    }
                }
            }
            onPlaybackStatusChanged = { playing, eventId ->
                synchronized(lock) {
                    log.values.forEach { it.isPlaying = false }
                    if (playing && eventId != null) {
                        log[eventId]?.isPlaying = true
                        currentlyPlayingEventId = eventId
                    } else {
                        currentlyPlayingEventId = null
                    }
                }
                this@SatelliteAudioLog.onPlaybackStatusChanged(playing)
            }
        }
    }
}
