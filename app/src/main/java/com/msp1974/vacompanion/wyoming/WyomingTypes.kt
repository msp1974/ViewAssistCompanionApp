package com.msp1974.vacompanion.wyoming

object WyomingEvent {
    const val PING = "ping"
    const val PONG = "pong"
    const val DESCRIBE = "describe"
    const val CAPABILITIES = "capabilities"
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
    const val RUN_PIPELINE = "run-pipeline"
    const val PIPELINE_ENDED = "pipeline-ended"
    const val ERROR = "error"
    const val HANDLED = "handled"
    const val CUSTOM_EVENT = "custom-event"
}

const val EVENT_TYPE = "event_type"

object WyomingCustomEventType {
    const val ACTION = "action"
    const val SETTINGS = "settings"
    const val CAPABILITIES = "capabilities"
    const val STATUS = "status"
    const val LOGS = "logs"
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
    ERROR
}
