package com.msp1974.vacompanion.streaming

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.util.Range
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.SessionConfig
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.EventListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Owns the camera side of RTSP streaming: while a viewer is PLAYing, binds a CameraX
 * [Preview] use case whose output Surface *is* the H.264 hardware encoder's input
 * surface (CameraX renders straight onto it - no extra copy), packetizes the encoded
 * output into RTP (RtpH264Packetizer) and serves it over [RtspServer].
 *
 * Camera2/CameraX only allow one client to hold the camera at a time, and this app
 * already has two other camera consumers - motion detection (device/Camera.kt) and
 * the on-device diagnostic preview (ui/layouts/CameraStreamLayout). Rather than a
 * shared capture-session owner (a bigger refactor of both of those), this uses the
 * same arbitration style the codebase already applies to that exact conflict:
 * - APPConfig.cameraStreamActive already means "the on-device diagnostic preview is
 *   showing"; device/Camera.kt already stops motion detection while it is true. If it
 *   is true when a PLAY arrives, the RTSP PLAY is refused (503) rather than racing it.
 * - APPConfig.rtspStreamActive is the new mirror image: true only while an RTSP
 *   viewer holds the camera. device/Camera.kt and SatelliteCustomEventHandler react to
 *   it exactly like cameraStreamActive, releasing/resuming motion detection.
 * The camera itself is only opened between a successful PLAY and TEARDOWN/disconnect
 * (not for the lifetime of the server), so the common case - RTSP enabled but nobody
 * pulling the stream - never touches the camera at all.
 */
class RtspCameraStreamer(
    private val context: Context,
    private val config: APPConfig,
) : EventListener {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)

    private var server: RtspServer? = null
    private var serverJob: Job? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var encoder: H264Encoder? = null
    private var glFrameTransformer: GlFrameTransformer? = null
    private var packetizer: RtpH264Packetizer? = null
    private var cachedSpsPps: Pair<ByteArray, ByteArray>? = null

    // Encoded access units are handed off here (from the encoder's drain-loop callback)
    // and packetized/sent by a single dedicated consumer coroutine below, so RTP packets
    // for consecutive frames can never be reordered or interleaved on the wire by two
    // concurrently-running coroutines racing each other.
    private data class PendingAccessUnit(val nals: List<ByteArray>, val timestamp90k: Long, val isKeyFrame: Boolean)
    private val accessUnitChannel = Channel<PendingAccessUnit>(capacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val streamPath = "stream"

    init {
        scope.launch {
            for (unit in accessUnitChannel) {
                sendPacketizedAccessUnit(unit)
            }
        }
    }

    fun init() {
        config.eventBroadcaster.addListener(this)
        if (config.rtspStreamEnabled) start()
    }

    fun release() {
        config.eventBroadcaster.removeListener(this)
        stop()
        accessUnitChannel.close()
        job.cancel()
    }

    override fun onEventTriggered(event: Event) {
        when (event.eventName) {
            "rtspStreamEnabled" -> {
                if (event.newValue == true) start() else stop()
            }
            "rtspStreamPort" -> {
                if (config.rtspStreamEnabled) {
                    Timber.i("RtspCameraStreamer: port changed, restarting server")
                    stop()
                    start()
                }
            }
        }
    }

    fun start() {
        if (server != null) return
        val lifecycleOwner = context as? LifecycleOwner
        if (lifecycleOwner == null) {
            Timber.e("RtspCameraStreamer: context is not a LifecycleOwner, cannot start")
            return
        }

        val rtspServer = object : RtspServer(config.rtspStreamPort) {
            override fun onState(state: RtspServerState) {
                Timber.i("RTSP server state: $state")
            }

            override suspend fun buildSdp(): String = buildSdpInternal()

            override suspend fun onViewerConnect(): Boolean {
                if (config.cameraStreamActive) {
                    Timber.w("RtspCameraStreamer: refusing viewer - on-device camera preview is active")
                    return false
                }
                return bindCamera(lifecycleOwner)
            }

            override suspend fun onViewerDisconnect() {
                withContext(Dispatchers.Main) { unbindCamera() }
            }
        }
        server = rtspServer
        serverJob = scope.launch { rtspServer.startServer() }
    }

    fun stop() {
        server?.stopServer()
        server = null
        serverJob?.cancel()
        serverJob = null
        // In case a viewer was still mid-stream when streaming was disabled.
        scope.launch { withContext(Dispatchers.Main) { unbindCamera() } }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private suspend fun bindCamera(lifecycleOwner: LifecycleOwner): Boolean = withContext(Dispatchers.Main) {
        try {
            setRtspStreamActive(true)

            val cameraProviderInstance = getCameraProvider()
            cameraProvider = cameraProviderInstance

            val targetWidth = config.rtspStreamWidth
            val targetHeight = config.rtspStreamHeight
            val fps = config.rtspStreamFps

            val previewBuilder = Preview.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(targetWidth, targetHeight),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        )
                        .build()
                )
            // Deliberately not using Preview.Builder.setTargetRotation/setMirrorMode here -
            // verified empirically that they have no effect on a raw Surface handed
            // straight to a SurfaceProvider (as we do below); GlFrameTransformer applies
            // the actual rotation/mirror instead. See its docs for the full story.
            Camera2Interop.Extender(previewBuilder).setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE,
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO
            )
            val preview = previewBuilder.build()

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
            val baseSessionConfig = SessionConfig.Builder(preview).build()
            val cameraInfo = cameraProviderInstance.getCameraInfo(cameraSelector, baseSessionConfig)
            // Purely for correct output-canvas SHAPE, not "which way is up" (that's the
            // user-facing rotation control's job, deliberately not auto-corrected - see
            // GlFrameTransformer's docs). Some devices' camera HAL reports frames through
            // SurfaceTexture with a 90/270-degree axis swap already baked into
            // getTransformMatrix() (verified empirically: a device whose front camera
            // reports SENSOR_ORIENTATION=90 produced a visibly distorted, squished-toward-
            // square stream when the output canvas was sized as if that swap didn't
            // happen) while others report it near-identity. Whether *this* device's camera
            // needs that swap accounted for when sizing the canvas is exactly what
            // SENSOR_ORIENTATION tells us, and it's available before the first frame
            // (unlike the transform matrix itself), so it's used only for that yes/no
            // swap decision below - not fed into the rotation angle.
            val sensorOrientation =
                Camera2CameraInfo.from(cameraInfo).getCameraCharacteristic(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

            preview.setSurfaceProvider(
                object : Preview.SurfaceProvider {
                    override fun onSurfaceRequested(request: SurfaceRequest) {
                        onCameraSurfaceRequested(request, fps, sensorOrientation)
                    }
                }
            )

            val supportedFrameRateRanges = cameraInfo.getSupportedFrameRateRanges(baseSessionConfig)
            val targetFrameRateRange = selectFrameRateRange(supportedFrameRateRanges, fps)
            val sessionConfig = SessionConfig.Builder(preview).apply {
                if (targetFrameRateRange != null) setFrameRateRange(targetFrameRateRange)
            }.build()

            cameraProviderInstance.unbindAll()
            cameraProviderInstance.bindToLifecycle(lifecycleOwner, cameraSelector, sessionConfig)
            true
        } catch (e: Exception) {
            Timber.e("RtspCameraStreamer: failed to bind camera: $e")
            setRtspStreamActive(false)
            false
        }
    }

    private fun onCameraSurfaceRequested(request: SurfaceRequest, fps: Int, sensorOrientation: Int) {
        val resolution = request.resolution
        val rotation = config.rtspStreamRotation
        val mirror = config.rtspStreamMirror
        // Two independent things can each require a width/height swap for the output
        // canvas to be correctly proportioned (not stretched): the user's requested
        // rotation, and this device's camera HAL baking a 90/270 axis swap into
        // SurfaceTexture's own transform (see the sensorOrientation doc above). Two
        // swaps cancel out, so it's an XOR, not an OR.
        val sensorImpliesSwap = sensorOrientation.mod(180) != 0
        val rotationImpliesSwap = rotation.mod(180) != 0
        val (encWidth, encHeight) = if (sensorImpliesSwap != rotationImpliesSwap) {
            resolution.height to resolution.width
        } else {
            resolution.width to resolution.height
        }
        Timber.i(
            "RtspCameraStreamer: camera granted resolution ${resolution.width}x${resolution.height}, " +
                "encoding at ${encWidth}x${encHeight} " +
                "(sensorOrientation=$sensorOrientation, rotation=$rotation, mirror=$mirror)"
        )
        val bitrate = estimateBitrate(encWidth, encHeight, fps)

        cachedSpsPps = null
        packetizer = RtpH264Packetizer()
        val newEncoder = object : H264Encoder(encWidth, encHeight, fps, bitrate) {
            override fun onConfig(sps: ByteArray, pps: ByteArray) {
                cachedSpsPps = sps to pps
            }

            override fun onAccessUnit(nals: List<ByteArray>, presentationTimeUs: Long, isKeyFrame: Boolean) {
                sendAccessUnit(nals, presentationTimeUs, isKeyFrame)
            }

            override fun onEncoderError(error: Throwable) {
                Timber.e("RtspCameraStreamer: encoder error: $error")
            }
        }
        encoder = newEncoder
        val encoderSurface = newEncoder.start()

        // CameraX's own setTargetRotation/setMirrorMode only affect consumers that
        // participate in its transform pipeline (PreviewView does) - a raw Surface handed
        // straight to a SurfaceProvider, as we do for the encoder, does not get
        // pre-rotated (verified empirically against a physical device). So CameraX renders
        // onto the transformer's input surface instead, and the transformer applies the
        // rotation/mirror itself via a small GL pass before handing frames to the encoder.
        val transformer = GlFrameTransformer(encoderSurface, encWidth, encHeight, rotation, mirror)
        glFrameTransformer = transformer

        request.provideSurface(transformer.inputSurface!!, ContextCompat.getMainExecutor(context)) { result ->
            Timber.d("RtspCameraStreamer: camera surface released, result=${result.resultCode}")
        }
    }

    private suspend fun unbindCamera() {
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Timber.w("RtspCameraStreamer: error unbinding camera: $e")
        }
        cameraProvider = null
        // Release order matters, same class of race as the encoder's own drain loop
        // (see H264Encoder.stop): the transformer's eglSwapBuffers targets the encoder's
        // input Surface, so it must fully stop before the encoder releases that Surface -
        // and the encoder's stop() must itself await its drain loop's exit before
        // releasing the codec.
        glFrameTransformer?.release()
        glFrameTransformer = null
        encoder?.stop()
        encoder = null
        packetizer = null
        cachedSpsPps = null
        setRtspStreamActive(false)
    }

    private fun setRtspStreamActive(active: Boolean) {
        if (config.rtspStreamActive != active) {
            config.rtspStreamActive = active
        }
    }

    private fun sendAccessUnit(nals: List<ByteArray>, presentationTimeUs: Long, isKeyFrame: Boolean) {
        val timestamp90k = (presentationTimeUs * 90L) / 1000L
        val result = accessUnitChannel.trySend(PendingAccessUnit(nals, timestamp90k, isKeyFrame))
        if (result.isFailure) {
            Timber.w("RtspCameraStreamer: dropped an access unit, sender is falling behind")
        }
    }

    private suspend fun sendPacketizedAccessUnit(unit: PendingAccessUnit) {
        val srv = server ?: return
        val pkt = packetizer ?: return

        // Send SPS/PPS in-band before every keyframe. This is not strictly required by
        // RFC 6184 when parameter sets are also signalled out-of-band (in the SDP), but
        // we deliberately omit sprop-parameter-sets from the SDP (see buildSdpInternal)
        // so this is the only place a client/decoder ever gets them.
        val allNals = if (unit.isKeyFrame) {
            val spsPps = cachedSpsPps
            if (spsPps != null) listOf(spsPps.first, spsPps.second) + unit.nals else unit.nals
        } else {
            unit.nals
        }

        for ((index, nal) in allNals.withIndex()) {
            val isLast = index == allNals.size - 1
            pkt.packetize(nal, unit.timestamp90k, isLast).forEach { rtpPacket ->
                srv.sendRtpToActiveViewer(rtpPacket)
            }
        }
    }

    private fun estimateBitrate(width: Int, height: Int, fps: Int): Int {
        // Rough heuristic (~0.1 bits/pixel/frame), clamped to a sane range for a device
        // that is also running a WebView + always-on voice pipeline concurrently.
        val bps = (width * height * fps * 0.1).toInt()
        return bps.coerceIn(400_000, 4_000_000)
    }

    private fun buildSdpInternal(): String {
        return buildString {
            append("v=0\r\n")
            append("o=- 0 0 IN IP4 0.0.0.0\r\n")
            append("s=VACA Camera\r\n")
            append("c=IN IP4 0.0.0.0\r\n")
            append("t=0 0\r\n")
            append("a=tool:VACA\r\n")
            append("a=range:npt=0-\r\n")
            append("m=video 0 RTP/AVP 96\r\n")
            append("a=rtpmap:96 H264/90000\r\n")
            // profile-level-id is a generic hint only (Constrained Baseline, level 3.0);
            // parameter sets are not negotiated out-of-band here, the decoder gets the
            // authoritative SPS/PPS in-band before every keyframe (see sendAccessUnit).
            append("a=fmtp:96 packetization-mode=1;profile-level-id=42e01e\r\n")
            append("a=control:$streamPath\r\n")
        }
    }

    private suspend fun getCameraProvider(): ProcessCameraProvider = suspendCancellableCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                cont.resume(future.get())
            } catch (e: Exception) {
                cont.resumeWithException(e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun selectFrameRateRange(supportedRanges: Set<Range<Int>>, desiredFps: Int): Range<Int>? {
        // Prefer the lowest fixed camera rate that can still meet the encoder target,
        // mirroring device/Camera.kt's selection logic for the motion-detection camera.
        val fixedRanges = supportedRanges.filter { it.lower == it.upper }
        return fixedRanges.filter { it.upper >= desiredFps }.minByOrNull { it.upper }
            ?: fixedRanges.maxByOrNull { it.upper }
    }
}
