package com.msp1974.vacompanion.webrtc

import android.content.Context
import android.view.Surface
import com.msp1974.vacompanion.device.camera.CameraController
import com.msp1974.vacompanion.device.camera.TeeOutput
import com.msp1974.vacompanion.rtsp.H264Encoder
import com.msp1974.vacompanion.settings.APPConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpParameters
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The shared front camera as a WebRTC video track. Frames come from the same
 * [com.msp1974.vacompanion.device.camera.SurfaceTee] as the on-device preview and the RTSP encoder,
 * so this runs alongside them without taking the camera for itself: the tee draws into a
 * [SurfaceTextureHelper] surface and libwebrtc encodes (H.264 in hardware where the device has it)
 * and packetises the result itself.
 *
 * Everything is created by [start] and released by [stop]; the server starts it when the first
 * viewer arrives and stops it again once the last has gone, so an idle server holds nothing.
 */
class WebRtcCameraSource(
    private val context: Context,
    private val cameraController: CameraController,
    private val config: APPConfig,
) {
    /** One connected viewer. */
    class Session(val id: String, val peer: PeerConnection) {
        @Volatile var stats: java.util.concurrent.ScheduledFuture<*>? = null
    }

    private val statsExecutor = Executors.newSingleThreadScheduledExecutor()

    private var eglBase: EglBase? = null
    private var sharedEncoder: H264Encoder? = null
    private var encoderFactory: SharedH264EncoderFactory? = null
    private var factory: PeerConnectionFactory? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var inputSurface: Surface? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null

    private val sessions = ConcurrentHashMap<String, Session>()

    // Which sessions have reached CONNECTED, kept from the connection callback so nobody has to ask
    // a PeerConnection that may already have been disposed (that is a native crash, not an exception).
    private val connectedSessions = ConcurrentHashMap.newKeySet<String>()
    private val connectionStates = ConcurrentHashMap<String, PeerConnection.PeerConnectionState>()

    fun isConnected(id: String): Boolean = connectedSessions.contains(id)

    @Volatile var isRunning = false
        private set

    /** Called when a viewer's connection has failed or closed, so the server can drop it. */
    var onSessionEnded: ((String) -> Unit)? = null

    @Synchronized
    fun start() {
        if (isRunning) return
        initialiseLibrary(context)

        val egl = EglBase.create()
        // One encoder for every viewer (see SharedH264EncoderFactory).
        val shared = H264Encoder(cameraController, config, outputId = "webrtc_encoder", baseline = true)
        shared.start()
        val encoders = SharedH264EncoderFactory(
            shared,
            DefaultVideoEncoderFactory(egl.eglBaseContext, true, false)
        )
        val newFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoders)
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
        sharedEncoder = shared
        encoderFactory = encoders

        val size = cameraController.streamResolution()
        val fps = config.rtspFps
        val source = newFactory.createVideoSource(false)
        // The rate is set by the camera and the encoder. Telling libwebrtc 15 here makes it throw
        // away frames that arrive a little early, and the encoder then runs ahead of its input.
        source.adaptOutputFormat(size.width, size.height, MAX_INPUT_FPS)
        val track = newFactory.createVideoTrack(TRACK_ID, source)

        val helper = SurfaceTextureHelper.create("WebRtcCamera", egl.eglBaseContext)
        helper.setTextureSize(size.width, size.height)
        val surface = Surface(helper.surfaceTexture)
        source.capturerObserver.onCapturerStarted(true)
        helper.startListening(VideoSink { frame -> source.capturerObserver.onFrameCaptured(frame) })

        eglBase = egl
        factory = newFactory
        videoSource = source
        videoTrack = track
        textureHelper = helper
        inputSurface = surface
        isRunning = true

        cameraController.setEncoderFps(fps)
        cameraController.addSurfaceOutput(
            TeeOutput(id = OUTPUT_ID, surface = surface, width = size.width, height = size.height, mirror = false)
        )
        Timber.i("WebRtcCameraSource: started ${size.width}x${size.height}@${fps}fps")
    }

    /**
     * Answers a viewer's SDP offer, sending the camera back. The answer has all of this device's
     * ICE candidates in it, because WHEP has no trickle: the viewer gets one answer and nothing
     * after it.
     */
    suspend fun createSession(offerSdp: String): Pair<Session, String> {
        val peerFactory = factory ?: throw IllegalStateException("not started")
        val track = videoTrack ?: throw IllegalStateException("not started")

        val gathered = CompletableDeferred<Unit>()
        val sessionId = java.util.UUID.randomUUID().toString()
        val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }
        val peer = peerFactory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                if (state == PeerConnection.IceGatheringState.COMPLETE) gathered.complete(Unit)
            }

            override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                Timber.d("WebRtcCameraSource: $sessionId connection $state")
                if (state == PeerConnection.PeerConnectionState.CONNECTED) connectedSessions.add(sessionId)
                connectionStates[sessionId] = state
                if (state == PeerConnection.PeerConnectionState.DISCONNECTED) {
                    // A viewer that has gone quiet (go2rtc probes and drops without saying goodbye)
                    // would take 10 seconds to fail; give it a moment to recover, then let go.
                    try {
                        statsExecutor.schedule({
                            if (connectionStates[sessionId] == PeerConnection.PeerConnectionState.DISCONNECTED) {
                                onSessionEnded?.invoke(sessionId)
                            }
                        }, DISCONNECT_GRACE_S, TimeUnit.SECONDS)
                    } catch (_: java.util.concurrent.RejectedExecutionException) {
                        // The source has been stopped; its sessions are already closed.
                    }
                }
                if (state == PeerConnection.PeerConnectionState.FAILED ||
                    state == PeerConnection.PeerConnectionState.CLOSED
                ) {
                    onSessionEnded?.invoke(sessionId)
                }
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceCandidate(candidate: IceCandidate) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(channel: org.webrtc.DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: org.webrtc.RtpReceiver, streams: Array<out MediaStream>) {}
        }) ?: throw IllegalStateException("couldn't create the peer connection")

        try {
            Timber.d("WebRtcCameraSource: ${sessionId.take(8)} offer: $offerSdp")
            peer.setRemote(SessionDescription(SessionDescription.Type.OFFER, offerSdp))

            // Send the camera on the offer's video line, and answer nothing for anything else (an
            // audio line would start the microphone-owning audio device module).
            val sender = peer.addTrack(track, listOf(STREAM_ID))
            var videoLineTaken = false
            for (transceiver in peer.transceivers) {
                if (transceiver.sender.id() == sender.id() && !videoLineTaken) {
                    transceiver.direction = RtpTransceiver.RtpTransceiverDirection.SEND_ONLY
                    preferH264(peerFactory, transceiver)
                    videoLineTaken = true
                } else {
                    transceiver.direction = RtpTransceiver.RtpTransceiverDirection.INACTIVE
                }
            }
            if (!videoLineTaken) throw IllegalStateException("the offer has no video to send")

            val answer = peer.createAnswerSdp()
            peer.setLocal(answer)
            withTimeoutOrNull(ICE_GATHER_TIMEOUT_MS) { gathered.await() }
                ?: Timber.w("WebRtcCameraSource: ICE gathering timed out, answering with what there is")

            tuneSender(peer, sender)
            val session = Session(sessionId, peer)
            sessions[sessionId] = session
            session.stats = statsExecutor.scheduleWithFixedDelay(
                { logStats(session) }, STATS_INTERVAL_S, STATS_INTERVAL_S, TimeUnit.SECONDS
            )
            val answerSdp = peer.localDescription?.description ?: answer.description
            Timber.d("WebRtcCameraSource: ${sessionId.take(8)} answer: $answerSdp")
            return session to answerSdp
        } catch (e: Throwable) {
            peer.close()
            peer.dispose()
            throw e
        }
    }

    fun closeSession(id: String) {
        connectedSessions.remove(id)
        connectionStates.remove(id)
        val session = sessions.remove(id) ?: return
        session.stats?.cancel(false)
        try {
            // Held so a stats request can't be made on a peer that is being disposed - that is a
            // native crash, not an exception.
            synchronized(session) {
                session.peer.close()
                session.peer.dispose()
            }
        } catch (e: Exception) {
            Timber.w("WebRtcCameraSource: error closing session $id: $e")
        }
    }

    @Synchronized
    fun stop() {
        if (!isRunning) return
        isRunning = false
        sessions.keys.toList().forEach { closeSession(it) }
        statsExecutor.shutdownNow()

        cameraController.removeSurfaceOutput(OUTPUT_ID)
        try {
            textureHelper?.stopListening()
            videoSource?.capturerObserver?.onCapturerStopped()
            textureHelper?.dispose()
            inputSurface?.release()
            videoTrack?.dispose()
            videoSource?.dispose()
            factory?.dispose()
            encoderFactory?.close()
            sharedEncoder?.stop()
            eglBase?.release()
        } catch (e: Exception) {
            Timber.w("WebRtcCameraSource: error stopping: $e")
        }
        textureHelper = null
        inputSurface = null
        videoTrack = null
        videoSource = null
        factory = null
        encoderFactory = null
        sharedEncoder = null
        eglBase = null
        Timber.i("WebRtcCameraSource: stopped")
    }

    /**
     * Holds the stream at the configured bitrate. A WHEP viewer such as go2rtc doesn't send the
     * bandwidth feedback a browser does, so libwebrtc's estimate would sit at its ~300 kbps starting
     * point - and at that rate it shrinks the picture to 320x240 within a second. Starting at the
     * configured rate, with a floor, and keeping resolution over frame rate keeps it sharp.
     */
    /** Answers with H.264 first when the viewer offers it, so the shared encoder is what gets used
     *  rather than a software VP8 encoder per viewer. */
    private fun preferH264(peerFactory: PeerConnectionFactory, transceiver: RtpTransceiver) {
        try {
            val codecs = peerFactory.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).codecs
            val (h264, others) = codecs.partition { it.name.equals("H264", ignoreCase = true) }
            transceiver.setCodecPreferences(h264 + others)
        } catch (e: Exception) {
            Timber.w("WebRtcCameraSource: couldn't prefer H.264: $e")
        }
    }

    private fun tuneSender(peer: PeerConnection, sender: org.webrtc.RtpSender) {
        // The real rate is fixed by the shared encoder. libwebrtc's frame dropper assumes the encoder
        // obeys its target and throws input frames away when keyframes make it look like it
        // overspends, so give it room.
        val maxBitrate = config.rtspBitrate * 2
        try {
            peer.setBitrate(null, maxBitrate, maxBitrate)
        } catch (e: Exception) {
            Timber.w("WebRtcCameraSource: couldn't set the start bitrate: $e")
        }
        try {
            val parameters = sender.parameters
            parameters.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
            parameters.encodings.forEach {
                it.maxBitrateBps = maxBitrate
            }
            sender.parameters = parameters
        } catch (e: Exception) {
            Timber.w("WebRtcCameraSource: couldn't set the sender limits: $e")
        }
    }

    /** Logs what is actually being sent, so a stream that looks wrong can be told apart from one
     *  that is not being sent properly (size, rate, keyframes, and what the viewer asks for). */
    private fun logStats(session: Session) {
        synchronized(session) {
            if (sessions[session.id] !== session) return
            session.peer.getStats { report ->
                val id = session.id.take(8)
                report.statsMap.values.forEach {
                    val m = it.members
                    when {
                        it.type == "outbound-rtp" && m["kind"] == "video" -> Timber.d(
                            "WebRtcCameraSource: $id sending ${m["frameWidth"]}x${m["frameHeight"]} " +
                                "${m["framesPerSecond"]}fps, ${m["framesEncoded"]} frames (${m["keyFramesEncoded"]} key), " +
                                "${m["packetsSent"]} packets, ${m["bytesSent"]} bytes, target ${m["targetBitrate"]}, " +
                                "limited by ${m["qualityLimitationReason"]}, " +
                                "viewer asked: nack=${m["nackCount"]} pli=${m["pliCount"]} fir=${m["firCount"]}"
                        )
                        // What the viewer reports back about what it received.
                        it.type == "remote-inbound-rtp" -> Timber.d(
                            "WebRtcCameraSource: $id viewer reports lost=${m["packetsLost"]} " +
                                "fractionLost=${m["fractionLost"]} jitter=${m["jitter"]} rtt=${m["roundTripTime"]}"
                        )
                        it.type == "candidate-pair" && m["state"] == "succeeded" && m["nominated"] == true -> Timber.d(
                            "WebRtcCameraSource: $id ice pair sent=${m["bytesSent"]} received=${m["bytesReceived"]} " +
                                "requestsReceived=${m["requestsReceived"]} availableOut=${m["availableOutgoingBitrate"]}"
                        )
                    }
                }
            }
        }
    }

    private suspend fun PeerConnection.setRemote(description: SessionDescription) =
        suspendCancellableCoroutine<Unit> { cont ->
            setRemoteDescription(object : SdpResult() {
                override fun onSetSuccess() = cont.resume(Unit)
                override fun onSetFailure(error: String?) =
                    cont.resumeWithException(IllegalStateException("setRemoteDescription: $error"))
            }, description)
        }

    private suspend fun PeerConnection.setLocal(description: SessionDescription) =
        suspendCancellableCoroutine<Unit> { cont ->
            setLocalDescription(object : SdpResult() {
                override fun onSetSuccess() = cont.resume(Unit)
                override fun onSetFailure(error: String?) =
                    cont.resumeWithException(IllegalStateException("setLocalDescription: $error"))
            }, description)
        }

    private suspend fun PeerConnection.createAnswerSdp() =
        suspendCancellableCoroutine<SessionDescription> { cont ->
            createAnswer(object : SdpResult() {
                override fun onCreateSuccess(description: SessionDescription) = cont.resume(description)
                override fun onCreateFailure(error: String?) =
                    cont.resumeWithException(IllegalStateException("createAnswer: $error"))
            }, MediaConstraints())
        }

    /** An [SdpObserver] where each call only has to supply the outcomes it can get. */
    private abstract class SdpResult : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }

    companion object {
        private const val OUTPUT_ID = "webrtc"
        private const val TRACK_ID = "camera"
        private const val STREAM_ID = "camera"
        private const val ICE_GATHER_TIMEOUT_MS = 3_000L
        private const val STATS_INTERVAL_S = 3L
        private const val DISCONNECT_GRACE_S = 3L
        private const val MAX_INPUT_FPS = 60

        @Volatile private var libraryReady = false

        private fun initialiseLibrary(context: Context) {
            if (libraryReady) return
            synchronized(this) {
                if (libraryReady) return
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                        .setInjectableLogger(WebRtcLogger(), org.webrtc.Logging.Severity.LS_WARNING)
                        .createInitializationOptions()
                )
                libraryReady = true
            }
        }
    }
}
