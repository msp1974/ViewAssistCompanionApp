package com.msp1974.vacompanion.device.camera

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.Future

/**
 * One destination the shared camera feed is drawn into: the on-screen preview, the RTSP
 * encoder's input surface, etc. [mirror] requests the familiar "selfie" look (left-right
 * flipped); the camera's own sensor mirroring is always corrected for first, so with
 * mirror=false the output is true-to-life - what a remote RTSP viewer expects.
 *
 * Once the image is turned upright it can be a different shape from the output (a portrait image
 * going to a landscape output). It always keeps its proportions and is centre-cropped to fill the
 * output - never stretched, and never letterboxed.
 */
class TeeOutput(
    val id: String,
    val surface: Surface,
    val width: Int,
    val height: Int,
    val mirror: Boolean = false,
)

/**
 * Fans a single CameraX [Preview] stream out to any number of destination surfaces, using a
 * dedicated GL thread. CameraX only allows one bound [Preview] use case at a time, so this is
 * what lets the local camera view and the RTSP encoder share the same camera feed concurrently.
 * Outputs can be added/removed at any time without [CameraController] having to rebind the camera.
 */
class SurfaceTee : Preview.SurfaceProvider {

    private val thread = HandlerThread("CameraTeeGL").apply { start() }
    private val handler = Handler(thread.looper)
    private val glExecutor = Executor { handler.post(it) }

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var defaultSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var cameraTexture: SurfaceTexture? = null
    private var cameraSurface: Surface? = null
    private var textureId: Int = -1
    private var program: Int = -1
    private var positionHandle = -1
    private var texCoordHandle = -1
    private var texMatrixHandle = -1
    private var textureUniform = -1

    private val stMatrix = FloatArray(16)
    private val adjustMatrix = FloatArray(16)
    private val finalMatrix = FloatArray(16)

    // Rotation to make the camera image upright: reported by CameraX, or set from the device
    // orientation for the direct Camera2 backend (see setDirectRotation).
    @Volatile private var cameraRotation = 0

    // Extra rotation the user asked for, for devices whose camera orientation is misreported.
    @Volatile private var rotationOffset = 0

    // Size of the camera's own frames, to work out the shape of the upright image.
    @Volatile private var bufferWidth = 0
    @Volatile private var bufferHeight = 0

    @Volatile private var sourceMirroring = false
    @Volatile private var released = false

    /** Told what CameraX reports each time it sends a transform, for diagnostics. */
    @Volatile var onCameraXTransform: ((rotationDegrees: Int, mirroring: Boolean) -> Unit)? = null

    private class OutputState(val output: TeeOutput) {
        var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    }

    private val outputs = CopyOnWriteArrayList<OutputState>()

    override fun onSurfaceRequested(request: SurfaceRequest) {
        handler.post {
            if (released) {
                request.willNotProvideSurface()
                return@post
            }
            try {
                ensureEgl()
                val resolution = request.resolution
                setupCameraTexture(resolution.width, resolution.height)
                val surface = cameraSurface
                if (surface == null) {
                    request.willNotProvideSurface()
                    return@post
                }
                request.setTransformationInfoListener(glExecutor) { info ->
                    cameraRotation = info.rotationDegrees
                    sourceMirroring = info.isMirroring
                    onCameraXTransform?.invoke(info.rotationDegrees, info.isMirroring)
                }
                request.provideSurface(surface, glExecutor) { result ->
                    Timber.d("SurfaceTee: surface request result: ${result.resultCode}")
                }
            } catch (e: Exception) {
                Timber.e("SurfaceTee: failed to satisfy surface request: $e")
                request.willNotProvideSurface()
            }
        }
    }

    /** Turns every output by a further [degrees], on top of the camera's own orientation. */
    fun setRotationOffset(degrees: Int) {
        rotationOffset = ((degrees % 360) + 360) % 360
    }

    /**
     * Opens the tee's camera input for a camera that isn't driven by CameraX (the direct Camera2
     * backend). The camera writes into the returned surface and every output is then drawn from it
     * with the orientation set by [setDirectRotation] - without this the raw frames reach the
     * outputs exactly as the sensor produced them. Completes with null if the tee was released.
     */
    fun createDirectInput(width: Int, height: Int): Future<Surface?> {
        val result = CompletableFuture<Surface?>()
        handler.post {
            try {
                if (released) {
                    result.complete(null)
                } else {
                    ensureEgl()
                    setupCameraTexture(width, height)
                    // The direct backend delivers true-to-life frames; only CameraX flags mirroring.
                    sourceMirroring = false
                    result.complete(cameraSurface)
                }
            } catch (e: Exception) {
                result.completeExceptionally(e)
            }
        }
        return result
    }

    /** Sets the rotation that makes frames from [createDirectInput] upright. */
    fun setDirectRotation(degrees: Int) {
        cameraRotation = ((degrees % 360) + 360) % 360
    }

    /**
     * Lets go of the outputs' surfaces without removing the outputs. A surface can only be written
     * to by one producer at a time, so this must happen before the camera is pointed straight at
     * them again; they are reconnected on the next frame drawn.
     */
    fun releaseOutputSurfaces(): Future<Unit> {
        val result = CompletableFuture<Unit>()
        handler.post {
            outputs.forEach { destroyOutputSurface(it) }
            result.complete(Unit)
        }
        return result
    }

    fun addOutput(output: TeeOutput) {
        handler.post {
            outputs.removeAll { it.output.id == output.id }
            outputs.add(OutputState(output))
        }
    }

    fun removeOutput(id: String) {
        handler.post {
            val state = outputs.find { it.output.id == id } ?: return@post
            outputs.remove(state)
            destroyOutputSurface(state)
        }
    }

    fun release() {
        handler.post {
            released = true
            outputs.forEach { destroyOutputSurface(it) }
            outputs.clear()
            cameraTexture?.release()
            cameraTexture = null
            cameraSurface?.release()
            cameraSurface = null
            if (textureId != -1) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            if (program != -1) GLES20.glDeleteProgram(program)
            teardownEgl()
        }
        thread.quitSafely()
    }

    // --- EGL / GL setup. Everything below runs only on [handler]'s thread. ---

    private fun ensureEgl() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) return

        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) throw RuntimeException("Unable to get EGL display")
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw RuntimeException("Unable to initialize EGL")
        }

        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] == 0) {
            throw RuntimeException("Unable to find a suitable EGL config")
        }
        eglConfig = configs[0]

        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) throw RuntimeException("Unable to create EGL context")

        // A 1x1 pbuffer gives the context a current surface even when there are no outputs yet,
        // so the camera's SurfaceTexture can still be drained every frame.
        val pbufferAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
        defaultSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, pbufferAttribs, 0)
        EGL14.eglMakeCurrent(eglDisplay, defaultSurface, defaultSurface, eglContext)

        setupTextureAndProgram()
    }

    private fun teardownEgl() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (defaultSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, defaultSurface)
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        defaultSurface = EGL14.EGL_NO_SURFACE
    }

    private fun setupTextureAndProgram() {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        texMatrixHandle = GLES20.glGetUniformLocation(program, "uTexMatrix")
        textureUniform = GLES20.glGetUniformLocation(program, "uTexture")
    }

    private fun setupCameraTexture(width: Int, height: Int) {
        cameraTexture?.setOnFrameAvailableListener(null)
        cameraTexture?.release()
        cameraSurface?.release()

        bufferWidth = width
        bufferHeight = height
        val texture = SurfaceTexture(textureId)
        texture.setDefaultBufferSize(width, height)
        texture.setOnFrameAvailableListener({ handler.post { drawFrame() } }, handler)
        cameraTexture = texture
        cameraSurface = Surface(texture)
    }

    private fun drawFrame() {
        val texture = cameraTexture ?: return
        if (released) return

        EGL14.eglMakeCurrent(eglDisplay, defaultSurface, defaultSurface, eglContext)
        texture.updateTexImage()
        texture.getTransformMatrix(stMatrix)

        val rotation = (cameraRotation + rotationOffset) % 360
        for (state in outputs) {
            ensureOutputSurface(state)
            if (state.eglSurface == EGL14.EGL_NO_SURFACE) continue
            EGL14.eglMakeCurrent(eglDisplay, state.eglSurface, state.eglSurface, eglContext)
            val output = state.output
            val (x, y, w, h) = fillViewport(output.width, output.height, rotation)
            GLES20.glViewport(x, y, w, h)
            computeAdjustMatrix(output.mirror, rotation)
            Matrix.multiplyMM(finalMatrix, 0, adjustMatrix, 0, stMatrix, 0)
            drawQuad()
            EGL14.eglSwapBuffers(eglDisplay, state.eglSurface)
        }

        // Leave the context pointed at the default surface rather than whichever output drew
        // last, so the next updateTexImage() above always has somewhere valid to be current on.
        EGL14.eglMakeCurrent(eglDisplay, defaultSurface, defaultSurface, eglContext)
    }

    /**
     * The viewport that makes the upright image cover an output without changing its proportions.
     * A quarter-turn swaps the camera frame's width and height, so a landscape frame becomes a
     * portrait image; to fill a landscape output it is drawn larger than the output and centred, so
     * the overhang (top and bottom) is clipped off by the surface. The origin is therefore negative
     * on the cropped axis, which GL allows.
     */
    private fun fillViewport(outWidth: Int, outHeight: Int, rotation: Int): IntArray {
        val bw = bufferWidth
        val bh = bufferHeight
        if (bw <= 0 || bh <= 0) return intArrayOf(0, 0, outWidth, outHeight)
        val quarterTurn = rotation % 180 != 0
        val imageAspect = if (quarterTurn) bh.toFloat() / bw else bw.toFloat() / bh
        val outAspect = outWidth.toFloat() / outHeight
        return if (imageAspect > outAspect) {
            val w = (outHeight * imageAspect).toInt()
            intArrayOf((outWidth - w) / 2, 0, w, outHeight)
        } else {
            val h = (outWidth / imageAspect).toInt()
            intArrayOf(0, (outHeight - h) / 2, outWidth, h)
        }
    }

    private fun computeAdjustMatrix(outputMirror: Boolean, rotation: Int) {
        Matrix.setIdentityM(adjustMatrix, 0)
        Matrix.translateM(adjustMatrix, 0, 0.5f, 0.5f, 0f)
        // The sensor buffer for a front camera is inherently mirrored; undo that first so a
        // non-mirrored output (e.g. the RTSP stream) is true-to-life, then re-apply a mirror
        // only when this particular output wants the familiar "selfie" look.
        if (sourceMirroring xor outputMirror) {
            Matrix.scaleM(adjustMatrix, 0, -1f, 1f, 1f)
        }
        Matrix.rotateM(adjustMatrix, 0, rotation.toFloat(), 0f, 0f, 1f)
        Matrix.translateM(adjustMatrix, 0, -0.5f, -0.5f, 0f)
    }

    private fun ensureOutputSurface(state: OutputState) {
        if (state.eglSurface != EGL14.EGL_NO_SURFACE) return
        val attribs = intArrayOf(EGL14.EGL_NONE)
        state.eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, state.output.surface, attribs, 0)
    }

    private fun destroyOutputSurface(state: OutputState) {
        if (state.eglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, state.eglSurface)
            state.eglSurface = EGL14.EGL_NO_SURFACE
        }
    }

    private fun drawQuad() {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, VERTEX_BUF)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, TEXCOORD_BUF)
        GLES20.glEnableVertexAttribArray(texCoordHandle)

        GLES20.glUniformMatrix4fv(texMatrixHandle, 1, false, finalMatrix, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(textureUniform, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
    }

    private fun createProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        val programId = GLES20.glCreateProgram()
        GLES20.glAttachShader(programId, vertexShader)
        GLES20.glAttachShader(programId, fragmentShader)
        GLES20.glLinkProgram(programId)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(programId, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetProgramInfoLog(programId)
            GLES20.glDeleteProgram(programId)
            throw RuntimeException("Could not link GL program: $log")
        }
        return programId
    }

    private fun loadShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Could not compile shader $type: $log")
        }
        return shader
    }

    companion object {
        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """

        private val VERTEX_BUF = floatBuffer(floatArrayOf(
            -1f, -1f,
             1f, -1f,
            -1f,  1f,
             1f,  1f
        ))

        private val TEXCOORD_BUF = floatBuffer(floatArrayOf(
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f
        ))

        private fun floatBuffer(values: FloatArray): FloatBuffer {
            return ByteBuffer.allocateDirect(values.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply { put(values); position(0) }
        }
    }
}
