package com.msp1974.vacompanion.wyoming

object WyomingEvent {
    const val PING = "ping"
    const val PONG = "pong"
    const val DESCRIBE = "describe"
    const val INFO = "info"
    const val RUN_SATELLITE = "run-satellite"
    const val PAUSE_SATELLITE = "pause-satellite"
    const val TRANSCRIBE = "transcribe"
    const val VOICE_STARTED = "voice-started"
    const val VOICE_STOPPED = "voice-stopped"
    const val TRANSCRIPT = "transcript"
    const val SYNTHESIZE = "synthesize"
    const val AUDIO_START = "audio-start"
    const val AUDIO_CHUNK = "audio-chunk"
    const val AUDIO_STOP = "audio-stop"
    const val PLAYED = "played"
    const val PIPELINE_ENDED = "pipeline-ended"
    const val ERROR = "error"
    const val CUSTOM_EVENT = "custom-event"
    const val TIMER_STARTED = "timer-started"
    const val TIMER_UPDATED = "timer-updated"
    const val TIMER_CANCELLED = "timer-cancelled"
    const val TIMER_FINISHED = "timer-finished"
}

/**
 * Represents the status of the TCP Server
 */
enum class ServerState {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    ERRORED,
}

/**
 * Represents the high-level connection state of the Wyoming satellite.
 */
enum class SatelliteState { 
    STOPPED, 
    RUNNING,
    STARTING,
    STOPPING,
    WAITING_FOR_SETTINGS,
    ERROR
}

/**
 * Represents the current stage of an active voice pipeline.
 */
enum class PipelineStage {
    IDLE,
    LISTENING,
    STREAMING,
    AWAITING_TTS
}

/**
 * Tracks the state of an individual voice pipeline session.
 */
data class PipelineSession(
    val id: Int,
    @Volatile var logicFinished: Boolean = false,
    @Volatile var audioFinished: Boolean = false,
    @Volatile var finalized: Boolean = false,
    @Volatile var forceContinue: Boolean = false
)
