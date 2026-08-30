package com.msp1974.vacompanion.audio

import android.media.AudioFormat
import android.media.MediaRecorder

/**
 * Canonical audio format constants for the VACA capture/playback path.
 *
 * All components that produce or consume audio on the pipeline
 * (MicrophoneInput, VoicePlayer, SatelliteClientHandler, etc.)
 * must agree on these values. Centralising them here prevents silent mismatches
 * when one file is updated but others are not.
 */
object VACAAudioFormat {
    /** Sample rate in Hz. */
    const val SAMPLE_RATE_HZ = 16000

    /** Number of audio channels (mono). */
    const val CHANNELS = 1

    /** Bytes per sample (16-bit PCM = 2). */
    const val BYTES_PER_SAMPLE = 2

    /** Android AudioFormat encoding constant for 16-bit PCM. */
    @JvmField val ENCODING = AudioFormat.ENCODING_PCM_16BIT

    /** Android AudioFormat channel config for mono input. */
    @JvmField val CHANNEL_IN_CONFIG = AudioFormat.CHANNEL_IN_MONO

    /** Capture source for USB/Bluetooth mics (SCO requires VOICE_COMMUNICATION). */
    const val DEFAULT_AUDIO_SOURCE = MediaRecorder.AudioSource.VOICE_COMMUNICATION

    /** Capture source for the built-in mic; preserves far-field speech. */
    const val BUILT_IN_MIC_AUDIO_SOURCE = MediaRecorder.AudioSource.VOICE_RECOGNITION

    const val FALLBACK_AUDIO_SOURCE = MediaRecorder.AudioSource.MIC

    const val DEFAULT_BUFFER_SIZE_IN_SHORTS = 1280
}