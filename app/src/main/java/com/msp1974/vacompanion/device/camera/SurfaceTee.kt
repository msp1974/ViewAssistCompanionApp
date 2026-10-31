package com.msp1974.vacompanion.device.camera

import android.graphics.RectF
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
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Future

/**
 * One destination the shared camera feed is drawn into: the on-screen preview, the RTSP
 * encoder's input surface, etc. [mirror] requests the familiar "selfie" look (left-right
 * flipped); the camera's own frames are true to life, so with mirror=false the output is too -
 * what a remote RTSP viewer expects. The user's mirror setting flips every output from there.
 *
 * Once the image is turned upright it can be a different shape from the output (a portrait image
 * going to a landscape output). It always keeps its proportions, never stretched. By default it is
 * centre-cropped to fill the output; with [fit] the whole image is kept instead and the spare
 * space is left black.
 */
class TeeOutput(
    val id: String,
    val surface: Surface,
    val width: Int,
    val height: Int,
    val mirror: Boolean = false,
    val fit: Boolean = false,
)

/**
 * Fans the camera's single stream out to any number of destination surfaces, using a dedicated GL
 * thread, drawing it turned upright, mirrored and fitted for each. Only one capture session can
 * hold the camera, so this is what lets the local camera view and the RTSP encoder share the same
 * camera feed concurrently. Outputs can be added/removed at any time without [CameraController]
 * having to reopen the camera.
 */
class SurfaceTee {

    private val thread = HandlerThread("CameraTeeGL").apply { start() }
    private val handler = Handler(thread.looper)

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
    private val mirrorMatrix = FloatArray(16)
    private val stMirrorMatrix = FloatArray(16)
    private val finalMatrix = FloatArray(16)

    // The camera's own transform as of the last frame drawn, for [imageToOutput].
    @Volatile private var lastStMatrix: FloatArray? = null

    // Rotation to make the camera image upright, set from the camera's report (see setDirectRotation).
    @Volatile private var cameraRotation = 0

    // Flips every output left to right, from the user's mirror setting.
    @Volatile var userMirror = false

    // For a frame holding a picture of the transposed shape stretched into the buffer (see
    // CameraOrientation.isFrameSqueezed), so the image is drawn as if the buffer were that shape:
    // the texture still fills the quad, which undoes the stretch.
    @Volatile var bufferIsTransposed = false

    // Extra rotation the user asked for, for devices whose camera orientation is misreported.
    @Volatile private var rotationOffset = 0

    // Size of the camera's own frames, to work out the shape of the upright image.
    @Volatile private var bufferWidth = 0
    @Volatile private var bufferHeight = 0

    @Volatile private var released = false

    private class OutputState(val output: TeeOutput) {
        var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        var loggedWidth = 0
        var loggedHeight = 0
    }

    private val outputs = CopyOnWriteArrayList<OutputState>()

    /** Turns every output by a further [degrees], on top of the camera's own orientation. */
    fun setRotationOffset(degrees: Int) {
        rotationOffset = ((degrees % 360) + 360) % 360
    }

    /**
     * Opens the tee's camera input. The camera writes into the returned surface and every output is
     * then drawn from it with the orientation set by [setDirectRotation] - without this the raw
     * frames reach the outputs exactly as the sensor produced them. Completes with null if the tee
     * was released.
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
                    Timber.i("SurfaceTee: camera input ${width}x$height")
                    result.complete(cameraSurface)
                }
            } catch (e: Exception) {
                result.completeExceptionally(e)
            }
        }
        return result
    }

    /** Sets the rotation that makes frames from [createDirectInput] upright (see
     *  [CameraOrientation.reportedRotation]). */
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
            // A replaced output (the view was resized, e.g. by a rotation) may be the same window,
            // which a second EGL surface can't connect to until the old one is let go.
            outputs.filter { it.output.id == output.id }.forEach {
                destroyOutputSurface(it)
                outputs.remove(it)
            }
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
        lastStMatrix = stMatrix.copyOf()

        val rotation = (cameraRotation + rotationOffset) % 360
        for (state in outputs) {
            ensureOutputSurface(state)
            if (state.eglSurface == EGL14.EGL_NO_SURFACE) continue
            EGL14.eglMakeCurrent(eglDisplay, state.eglSurface, state.eglSurface, eglContext)
            val output = state.output
            // The surface's real size, which is what the viewport must be worked out against: the
            // size the output was registered with is out of date if the view has been resized since.
            val eglSize = IntArray(2)
            EGL14.eglQuerySurface(eglDisplay, state.eglSurface, EGL14.EGL_WIDTH, eglSize, 0)
            EGL14.eglQuerySurface(eglDisplay, state.eglSurface, EGL14.EGL_HEIGHT, eglSize, 1)
            val outWidth = if (eglSize[0] > 0) eglSize[0] else output.width
            val outHeight = if (eglSize[1] > 0) eglSize[1] else output.height
            if (state.loggedWidth != outWidth || state.loggedHeight != outHeight) {
                state.loggedWidth = outWidth
                state.loggedHeight = outHeight
                Timber.i(
                    "SurfaceTee: output ${output.id} registered ${output.width}x${output.height}, " +
                        "surface ${eglSize[0]}x${eglSize[1]}, buffer ${bufferWidth}x$bufferHeight, rotation $rotation"
                )
            }
            val (x, y, w, h) = fillViewport(outWidth, outHeight, rotation, output.fit)
            GLES20.glViewport(x, y, w, h)
            computeAdjustMatrix(output.mirror, rotation)
            Matrix.multiplyMM(stMirrorMatrix, 0, stMatrix, 0, mirrorMatrix, 0)
            Matrix.multiplyMM(finalMatrix, 0, adjustMatrix, 0, stMirrorMatrix, 0)
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
     * on the cropped axis, which GL allows. With [fit] it is drawn smaller than the output instead,
     * so the whole image shows.
     */
    private fun fillViewport(outWidth: Int, outHeight: Int, rotation: Int, fit: Boolean): IntArray {
        val bw = if (bufferIsTransposed) bufferHeight else bufferWidth
        val bh = if (bufferIsTransposed) bufferWidth else bufferHeight
        if (bw <= 0 || bh <= 0) return intArrayOf(0, 0, outWidth, outHeight)
        val quarterTurn = rotation % 180 != 0
        val imageAspect = if (quarterTurn) bh.toFloat() / bw else bw.toFloat() / bh
        val outAspect = outWidth.toFloat() / outHeight
        // Covering the output means matching its shorter side, fitting it means matching its longer.
        return if ((imageAspect > outAspect) != fit) {
            val w = (outHeight * imageAspect).toInt()
            intArrayOf((outWidth - w) / 2, 0, w, outHeight)
        } else {
            val h = (outWidth / imageAspect).toInt()
            intArrayOf(0, (outHeight - h) / 2, outWidth, h)
        }
    }

    /** The width over height of the image as drawn (before any fitting), or null before the camera
     *  has produced a frame. */
    fun imageAspect(): Float? {
        val bw = if (bufferIsTransposed) bufferHeight else bufferWidth
        val bh = if (bufferIsTransposed) bufferWidth else bufferHeight
        if (bw <= 0 || bh <= 0) return null
        val quarterTurn = (cameraRotation + rotationOffset) % 180 != 0
        return if (quarterTurn) bh.toFloat() / bw else bw.toFloat() / bh
    }

    /**
     * Where [rect] - a rectangle in the camera frame, 0..1 across and down from its top left, as
     * the motion and face detectors report - appears in an output's image, 0..1 across and down
     * from the top left of the image as drawn (not of the whole surface). Worked out by running
     * the very transform the frames are drawn with backwards, so it follows the rotation, mirror and
     * camera transform whatever they are. Null until a frame has been drawn.
     */
    fun imageToOutput(rect: RectF, outputMirror: Boolean): RectF? {
        val st = lastStMatrix ?: return null
        val rotation = (cameraRotation + rotationOffset) % 360
        val adjust = FloatArray(16)
        val mirror = FloatArray(16)
        computeAdjustMatrix(outputMirror, rotation, adjust, mirror)
        val stMirror = FloatArray(16)
        val full = FloatArray(16)
        val inverse = FloatArray(16)
        Matrix.multiplyMM(stMirror, 0, st, 0, mirror, 0)
        Matrix.multiplyMM(full, 0, adjust, 0, stMirror, 0)
        if (!Matrix.invertM(inverse, 0, full, 0)) return null

        // Texture coordinates run down the frame from t=0 at its first row, GL's v runs up the output.
        val out = FloatArray(4)
        val xs = FloatArray(4)
        val ys = FloatArray(4)
        val corners = floatArrayOf(rect.left, rect.top, rect.right, rect.top, rect.left, rect.bottom, rect.right, rect.bottom)
        for (i in 0 until 4) {
            Matrix.multiplyMV(out, 0, inverse, 0, floatArrayOf(corners[i * 2], corners[i * 2 + 1], 0f, 1f), 0)
            xs[i] = out[0]
            ys[i] = 1f - out[1]
        }
        return RectF(xs.min(), ys.min(), xs.max(), ys.max())
    }

    private fun computeAdjustMatrix(
        outputMirror: Boolean,
        rotation: Int,
        adjustMatrix: FloatArray = this.adjustMatrix,
        mirrorMatrix: FloatArray = this.mirrorMatrix,
    ) {
        Matrix.setIdentityM(adjustMatrix, 0)
        Matrix.translateM(adjustMatrix, 0, 0.5f, 0.5f, 0f)
        Matrix.rotateM(adjustMatrix, 0, rotation.toFloat(), 0f, 0f, 1f)
        Matrix.translateM(adjustMatrix, 0, -0.5f, -0.5f, 0f)

        // The camera's frames are true to life, so a non-mirrored output (e.g. the RTSP stream) is
        // as they are, and a mirror is applied only when this particular output wants the familiar
        // "selfie" look, or the user has asked for one. The flip is of the output's left-right, so it
        // is applied to the output coordinates before the camera's transform and the rotation;
        // applied after the rotation it would flip top-bottom whenever the image is turned a quarter.
        Matrix.setIdentityM(mirrorMatrix, 0)
        if (outputMirror xor userMirror) {
            Matrix.translateM(mirrorMatrix, 0, 0.5f, 0.5f, 0f)
            Matrix.scaleM(mirrorMatrix, 0, -1f, 1f, 1f)
            Matrix.translateM(mirrorMatrix, 0, -0.5f, -0.5f, 0f)
        }
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
