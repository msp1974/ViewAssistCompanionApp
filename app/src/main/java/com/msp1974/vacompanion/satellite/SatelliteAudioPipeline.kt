package com.msp1974.vacompanion.satellite

import android.content.Context
import com.msp1974.vacompanion.audio.AudioDSP
import com.msp1974.vacompanion.satellite.Satellite.Companion.isoNow
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.wakeword.WakeWordEngineProvider
import com.msp1974.vacompanion.wyoming.WyomingPacket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import timber.log.Timber

enum class PipelineStage {
    INIT,
    STARTING,
    STARTED,
    LISTENING,
    VOICE_STARTED,
    VOICE_STOPPED,
    AWAITING_RESPONSE,
    AWAITING_TTS,
    STARTING_TTS,
    STREAMING_TTS,
    COMPLETED_TTS,
    ENDED,
}

enum class PipelineStartMode {
    WAKE_WORD_DETECTED,
    REQUESTED_BY_SERVER,
    CONTINUE_CONVERSATION,
    START_STREAM_TTS
}

enum class PipelineEndReason {
    NONE,
    END_OF_PIPELINE,
    FORCE_STOPPED,
    TIMED_OUT,
    ERRORED,
    DUPLICATE_WAKEUP
}

interface IAudioPipeline {
    fun sendMessage(packet: WyomingPacket)
    fun onStateChange(state: PipelineStage)
    fun onFinish(reason: PipelineEndReason, continueConversation: Boolean)
}

abstract class SatelliteAudioPipeline(
    val context: Context,
    val scope: CoroutineScope,
    val config: APPConfig,
    val pipelineId: Int,
    val mediaManager: SatelliteMediaManager,
): IAudioPipeline {

    companion object {
        val CONTINUATION_STOP_WORDS = listOf("never mind")
    }

    private val json = Json { ignoreUnknownKeys = true }
    private var pipelineRunning = CompletableDeferred<PipelineEndReason>()
    private var audioInMessageQueue = Channel<WyomingPacket>(capacity = 1000)
    private var audioOutQueue = Channel<WakeWordEngineProvider.AudioResult.Audio>(capacity = 1000)
    private var result: PipelineEndReason = PipelineEndReason.NONE
    private var shouldContinueConversation = config.continueConversation
    var stageStartTime: Long = System.currentTimeMillis()
    var pipelineStartMode: PipelineStartMode = PipelineStartMode.WAKE_WORD_DETECTED
    var pipelineStage = PipelineStage.INIT
        set(value) {
            // Only allow increasing statuses
            if (value.ordinal > pipelineStage.ordinal) {
                field = value
                stageStartTime = System.currentTimeMillis()
                onStateChange(value)
            }
        }

    var silenceAudioBefore: Long = 0L

    val isAcceptingMessages: Boolean
        get() = !pipelineRunning.isCompleted && pipelineStage != PipelineStage.ENDED

    private val isContinuation
        get() = pipelineStartMode == PipelineStartMode.CONTINUE_CONVERSATION

    fun run(startStage: PipelineStartMode = PipelineStartMode.WAKE_WORD_DETECTED) {
        scope.launch {
            start(startStage)
        }
    }

    fun watchDogTimer() {
        scope.launch {
            try {
                withTimeout(20000) {
                    while (pipelineStage != PipelineStage.STREAMING_TTS) {
                        if (hasExceededTimeInStage()) {
                            stop(PipelineEndReason.TIMED_OUT)
                            break
                        }
                        delay(200)
                    }
                }
            } catch (e: Exception) {
                stop(PipelineEndReason.TIMED_OUT)
            }
        }
    }

    fun hasExceededTimeInStage(): Boolean {
        val timeInStage: Int = ((System.currentTimeMillis() - stageStartTime) / 1000).toInt()

        return when(pipelineStage) {
            PipelineStage.STARTED -> timeInStage >  5
            PipelineStage.LISTENING -> timeInStage > if (isContinuation) 5 else 10
            PipelineStage.VOICE_STARTED -> timeInStage > 20
            PipelineStage.AWAITING_TTS -> timeInStage > 5
            else -> false
        }
    }

    suspend fun start(startMode: PipelineStartMode) {
        pipelineStartMode = startMode
        val job = scope.launch(Dispatchers.Default) {
            try {
                pipelineStage = PipelineStage.STARTING

                mediaManager.voicePlayer.start()
                scope.launch { audioInMessageHandler() }
                scope.launch { audioOutHandler() }
                watchDogTimer()
                pipelineStage = PipelineStage.STARTED

                Timber.d("Starting listening pipeline at stage $pipelineStartMode: [$pipelineId]")

                when(pipelineStartMode) {
                    PipelineStartMode.WAKE_WORD_DETECTED -> {
                        sendMessage(buildDetectionMessage())
                        sendMessage(buildRunPipelineMessage(pipelineStartMode))
                    }
                    PipelineStartMode.CONTINUE_CONVERSATION -> {
                        silenceAudioBefore = 1L
                        sendMessage(buildRunPipelineMessage(pipelineStartMode))
                    }
                    PipelineStartMode.START_STREAM_TTS -> {
                        shouldContinueConversation = false
                    }

                    PipelineStartMode.REQUESTED_BY_SERVER -> {
                        silenceAudioBefore = 1L
                        shouldContinueConversation = false
                    }
                }

                awaitCancellation()
            } finally {
                //TODO: Change to audio stop
                withContext(NonCancellable) {
                    val msg = when (result) {
                        PipelineEndReason.END_OF_PIPELINE -> { "Pipeline ended normally" }
                        PipelineEndReason.FORCE_STOPPED -> { "Pipeline was terminated" }
                        PipelineEndReason.ERRORED -> { "Pipeline ended with an error" }
                        PipelineEndReason.TIMED_OUT -> { "Pipeline timed out at stage: $pipelineStage" }
                        PipelineEndReason.DUPLICATE_WAKEUP -> { "Pipeline ended due to duplicate wake up" }
                        else -> { "Pipeline ended for unknown reason" }
                    }
                    Timber.d("$msg [$pipelineId]")
                    mediaManager.voicePlayer.stop()
                    pipelineStage = PipelineStage.ENDED
                    onFinish(result, shouldContinueConversation)
                }
            }
        }
        result = pipelineRunning.await()
        job.cancel()
        Timber.d("Pipeline stopped [$pipelineId] -> $result.")
    }

    fun stop(endReason: PipelineEndReason = PipelineEndReason.FORCE_STOPPED) {
        if (pipelineStage != PipelineStage.ENDED) sendMessage(buildAudioStopMessage())
        shouldContinueConversation = false
        pipelineRunning.complete(endReason)
    }

    suspend fun processAudioPipelineMessage(packet: WyomingPacket) {
        if (pipelineRunning.isCompleted) {
            Timber.w(
                "Ignoring ${packet.type} for completed pipeline. " +
                    "pipelineId=$pipelineId startMode=$pipelineStartMode stage=$pipelineStage"
            )
            return
        }

        when (packet.type) {
            "transcribe" -> handleTranscribe()
            "voice-started" -> handleVoiceStarted()
            "voice-stopped" -> handleVoiceStopped()
            "transcript" -> handleTranscript(packet)
            "handled" -> handleHandled(packet)
            "synthesize" -> handleSynthesize()
            "audio-start", "audio-chunk", "audio-stop" -> queueAudioMessage(packet)
            "pipeline-ended" -> handlePipelineEnded()
            "error" -> handlePipelineError(packet)
        }
    }

    private suspend fun queueAudioMessage(packet: WyomingPacket) {
        audioInMessageQueue.send(packet)
    }

    private suspend fun audioInMessageHandler() {
        val job = scope.launch(Dispatchers.Default) {
            Timber.d("AudioIn message handler started.")
            try {
                while (true) {
                    val msg = audioInMessageQueue.receive()
                    when (msg.type) {
                        "audio-start" -> handleAudioStart(msg)
                        "audio-chunk" -> handleAudioChunk(msg)
                        "audio-stop" -> handleAudioStop()
                    }
                    yield()
                }
            } finally {
                withContext(NonCancellable) {
                    Timber.d("Ending AudioIn message handler.")
                    audioInMessageQueue.cancel()
                }
            }
        }
        result = pipelineRunning.await()
        job.cancel()
        Timber.d("Audio message handler stopped.")

    }

    private suspend fun audioOutHandler() {
        val job = scope.launch(Dispatchers.Default) {
            Timber.d("AudioOut handler started.")
            try {
                while (true) {
                    if (silenceAudioBefore > 0L) {
                        val audio = audioOutQueue.receive()
                        var audioByteArray = audio.audio.toByteArray()
                        if (audio.timestamp < silenceAudioBefore - 100) {
                            audioByteArray = AudioDSP().reduceVolume(audioByteArray, 0.1F)
                        }
                        val packet = buildAudioPacketMessage(audioByteArray)
                        sendMessage(packet)
                    }
                    yield()
                }
            } finally {
                withContext(NonCancellable) {
                    Timber.d("Ending audio message handler.")
                    audioOutQueue.cancel()
                }
            }
        }
        result = pipelineRunning.await()
        job.cancel()
        Timber.d("AudioOut handler stopped.")
    }

    suspend fun sendMicAudio(audio: WakeWordEngineProvider.AudioResult.Audio): Boolean {
        if (pipelineStage == PipelineStage.LISTENING  || pipelineStage == PipelineStage.VOICE_STARTED) {
            audioOutQueue.send(audio)
            return true
        }
        return false
    }

    internal fun handleTranscribe() {
        pipelineStage = PipelineStage.LISTENING
    }

    internal fun handleVoiceStarted() {
        pipelineStage = PipelineStage.VOICE_STARTED
    }

    internal fun handleVoiceStopped() {
        pipelineStage = PipelineStage.VOICE_STOPPED
    }

    internal fun handleTranscript(packet: WyomingPacket) {
        // Handle pipeline cancel words
        if (isContinuation && packet.getProp("text").lowercase().replace(".", "") in CONTINUATION_STOP_WORDS) {
            stop()
            return
        }
        pipelineStage = PipelineStage.AWAITING_RESPONSE
    }

    internal fun handleHandled(packet: WyomingPacket) {
        if (shouldContinueConversation) return

        if (packet.getProp("context") != "") {
            val context = json.parseToJsonElement(packet.getProp("context")).jsonObject
            val intentOutput = context["intent_output"]?.jsonObject
            if (intentOutput != null) {
                shouldContinueConversation = intentOutput["continue_conversation"]?.jsonPrimitive?.boolean ?: false
            }
            Timber.d("Continue conversation: $shouldContinueConversation")
        }
    }

    internal fun handleSynthesize() {
        if (pipelineStage != PipelineStage.STREAMING_TTS) {
            pipelineStage = PipelineStage.AWAITING_TTS
        }
    }

    internal suspend fun handleAudioStart(msg: WyomingPacket) {
        val rate = msg.getProp("rate").toInt()
        val width = msg.getProp("width").toInt()
        val channels = msg.getProp("channels").toInt()
        try {
            withTimeout(1000) {
                while (!mediaManager.voicePlayer.isRunning()) {
                    delay(50)
                }
            }
            withTimeout(1000) {
                mediaManager.voicePlayer.play(rate,width,channels)
                while (!mediaManager.voicePlayer.isReady()) {
                    delay(50)
                }
            }
        } catch (e: Exception) {
            return
        }
        pipelineStage = PipelineStage.STARTING_TTS
    }

    internal fun handleAudioChunk(event: WyomingPacket) {
        if (pipelineStage == PipelineStage.STARTING_TTS) pipelineStage = PipelineStage.STREAMING_TTS

        if (mediaManager.voicePlayer.isReady() && pipelineStage == PipelineStage.STREAMING_TTS) {
            try {
                mediaManager.voicePlayer.writeData(event.payload)
            } catch (e: Exception) {
                Timber.e("Error writing audio data")
            }
        }
    }

    internal fun handleAudioStop() {
        scope.launch {
            try {
                if (pipelineStage == PipelineStage.STREAMING_TTS && mediaManager.voicePlayer.isPlaying()) {
                    // We send 'played' but we DON'T stop immediately, to allow draining.
                    // The next synthesizer or a reset will stop it properly.
                    mediaManager.voicePlayer.flush()
                    withTimeout(10000) {
                        while (mediaManager.voicePlayer.isPlaying()) {
                            delay(100)
                        }
                    }
                }
                // Force stop audio as something is wrong
                mediaManager.voicePlayer.forceStop()
            } catch (e: Exception) {
                Timber.d("Audio stop timed out")
            } finally {
                withContext(NonCancellable) {
                    sendMessage(buildPlayedMessage())
                    pipelineStage = PipelineStage.COMPLETED_TTS
                    handlePipelineEnded()
                }
            }
        }
    }

    internal fun handlePipelineEnded() {
        if (pipelineStage == PipelineStage.AWAITING_TTS || pipelineStage == PipelineStage.STREAMING_TTS) {
            Timber.d("Pipeline ended but TTS is in stage $pipelineStage. Waiting for TTS audio to complete")
        } else {
            pipelineRunning.complete(PipelineEndReason.END_OF_PIPELINE)
        }
    }


    internal fun handlePipelineError(event: WyomingPacket) {
        val code = event.getProp("code")
        val text = event.getProp("text")

        val isDuplicateWakeUp = code == "duplicate_wake_up_detected"

        if (isDuplicateWakeUp) {
            Timber.d("Speech-to-text cancelled to avoid duplicate wake-up. Handled gracefully.")
            pipelineRunning.complete(PipelineEndReason.DUPLICATE_WAKEUP)
            return
        }

        val toastMessage = text.ifEmpty { "Error: $code" }
        config.eventBroadcaster.notifyEvent(Event("recognitionError", toastMessage, code))
        shouldContinueConversation = false

        sendMessage(buildAudioStopMessage())
        pipelineRunning.complete(PipelineEndReason.ERRORED)
    }


    /**
     * Notifies of wake word detection.
     */
    internal fun buildDetectionMessage(): WyomingPacket {
        val detectionPacket = WyomingPacket(
            "detection",
            buildJsonObject {
                put("name", config.wakeWord)
                put("timestamp", isoNow())
                put("speaker", "")
            },
        )
        return detectionPacket
    }

    /**
     * Initiates a pipeline run.
     */
    internal fun buildRunPipelineMessage(startStage: PipelineStartMode): WyomingPacket {
        val packet = WyomingPacket(
            "run-pipeline",
            buildJsonObject {
                put("name", "VACA ${config.uuid}")
                put("start_stage", if (startStage == PipelineStartMode.WAKE_WORD_DETECTED || startStage == PipelineStartMode.CONTINUE_CONVERSATION) "asr" else "tts")
                put("end_stage", "tts")
                put("restart_on_end", false)
                putJsonObject("snd_format") {
                    put("rate", config.sampleRate)
                    put("width", config.audioWidth)
                    put("channels", config.audioChannels)
                }
            }
        )
        return packet
    }

    internal fun buildAudioPacketMessage(audio: ByteArray): WyomingPacket {
        val packet = WyomingPacket(
            "audio-chunk",
            buildJsonObject {
                put("rate", config.sampleRate)
                put("width", config.audioWidth)
                put("channels", config.audioChannels)
            },
            audio
        )
        return packet
    }

    internal fun buildAudioStopMessage(): WyomingPacket {
        val packet = WyomingPacket(
            "audio-stop",
            buildJsonObject { put("timestamp", isoNow()) },
            ByteArray(0),
        )
        return packet
    }

    internal fun buildPlayedMessage(): WyomingPacket {
        val packet = WyomingPacket(
            "played",
            buildJsonObject {},
            ByteArray(0),
        )
        return packet
    }
}
