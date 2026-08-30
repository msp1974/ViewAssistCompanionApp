package com.msp1974.vacompanion.streaming

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
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
import java.util.concurrent.CountDownLatch

/**
 * Renders camera frames onto a target Surface (the video encoder's input surface) via a
 * single textured quad, applying a clockwise rotation (0/90/180/270) and/or a horizontal
 * mirror in the process.
 *
 * This exists because CameraX's Preview.Builder.setTargetRotation()/setMirrorMode() only
 * affect consumers that participate in CameraX's own transform pipeline (PreviewView does,
 * via SurfaceRequest's TransformationInfo) - a raw Surface handed straight to a
 * SurfaceProvider, as RtspCameraStreamer does for the encoder, does not get pre-rotated.
 * Verified empirically against a physical device: setting setTargetRotation alone produced
 * no visible change in the stream. This applies the transform itself with a minimal GL
 * pipeline instead - the same technique real camera apps use (e.g. Grafika's
 * TextureMovieEncoder), just trimmed to exactly what's needed here (one input texture, one
 * output surface, one rotation+mirror transform).
 *
 * GL/EGL contexts are thread-affine, so this owns a dedicated HandlerThread for the whole
 * lifecycle: CameraX renders onto [inputSurface] (backed by an OES SurfaceTexture); each
 * new frame is drawn onto [outputSurface] with the requested transform applied and the
 * frame's original presentation time carried over so encoder timestamps/RTP timing stay
 * correct.
 */
class GlFrameTransformer(
    private val outputSurface: Surface,
    outputWidth: Int,
    outputHeight: Int,
    rotationDegrees: Int,
    mirror: Boolean,
) {
    private val thread = HandlerThread("GlFrameTransformer").apply { start() }
    private val handler = Handler(thread.looper)

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var textureId = 0
    private var program = 0
    private var uMvpMatrixHandle = 0
    private var uStMatrixHandle = 0
    private var aPositionHandle = 0
    private var aTextureCoordHandle = 0

    private var inputSurfaceTexture: SurfaceTexture? = null

    /** CameraX should render onto this - it's the GL pipeline's input. */
    var inputSurface: Surface? = null
        private set

    private val stMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    @Volatile
    private var released = false

    init {
        // Deliberately NOT correcting for the camera's own sensor mounting angle
        // (CameraCharacteristics.SENSOR_ORIENTATION) here - that varies per device and
        // getting it right for every device isn't worth chasing blind. Instead this just
        // guarantees each 90-degree step in rotationDegrees is a real, correctly-signed
        // 90-degree clockwise turn of whatever the camera's raw (uncorrected) output looks
        // like, so a user can dial in the right value for their specific device/mounting
        // via the on-device rotate control rather than relying on it defaulting correctly.
        Matrix.setIdentityM(mvpMatrix, 0)
        // android.opengl.Matrix.*M calls post-multiply (m := m * X), which means the LAST
        // call ends up applied FIRST to a given vertex (innermost) and the FIRST call ends
        // up applied LAST (outermost) - (A*B)*v = A*(B*v), B (called second) hits v first.
        // We want rotation applied to the raw frame first, then the mirror applied to that
        // already-rotated result (mirroring the final image, not the pre-rotation sensor
        // frame) - so scaleM (mirror, outermost/last-applied) must be called BEFORE
        // rotateM (innermost/first-applied) here, not after.
        if (mirror) {
            Matrix.scaleM(mvpMatrix, 0, -1f, 1f, 1f)
        }
        // android.opengl.Matrix.rotateM treats a positive angle as counter-clockwise
        // (standard math convention), so negate to get the clockwise rotation we want.
        Matrix.rotateM(mvpMatrix, 0, -rotationDegrees.toFloat(), 0f, 0f, 1f)

        val latch = CountDownLatch(1)
        handler.post {
            setupEgl(outputWidth, outputHeight)
            setupTexture()
            setupProgram()
            val st = SurfaceTexture(textureId)
            st.setDefaultBufferSize(outputWidth, outputHeight)
            inputSurfaceTexture = st
            inputSurface = Surface(st)
            st.setOnFrameAvailableListener({ onFrameAvailable() }, handler)
            latch.countDown()
        }
        latch.await()
    }

    private fun onFrameAvailable() {
        if (released) return
        try {
            val st = inputSurfaceTexture ?: return
            makeCurrent()
            st.updateTexImage()
            st.getTransformMatrix(stMatrix)
            drawFrame()
            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, st.timestamp)
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        } catch (e: Exception) {
            Timber.e("GlFrameTransformer: error drawing frame: $e")
        }
    }

    private fun drawFrame() {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        GLES20.glVertexAttribPointer(aPositionHandle, 2, GLES20.GL_FLOAT, false, 0, quadVertices)
        GLES20.glEnableVertexAttribArray(aPositionHandle)
        GLES20.glVertexAttribPointer(aTextureCoordHandle, 2, GLES20.GL_FLOAT, false, 0, quadTexCoords)
        GLES20.glEnableVertexAttribArray(aTextureCoordHandle)

        GLES20.glUniformMatrix4fv(uMvpMatrixHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(uStMatrixHandle, 1, false, stMatrix, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPositionHandle)
        GLES20.glDisableVertexAttribArray(aTextureCoordHandle)
    }

    private fun setupEgl(width: Int, height: Int) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) throw RuntimeException("eglGetDisplay failed")

        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw RuntimeException("eglInitialize failed")
        }

        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] == 0) {
            throw RuntimeException("eglChooseConfig failed")
        }
        val config = configs[0]!!

        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) throw RuntimeException("eglCreateContext failed")

        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, outputSurface, surfaceAttribs, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) throw RuntimeException("eglCreateWindowSurface failed")

        makeCurrent()
        GLES20.glViewport(0, 0, width, height)
    }

    private fun makeCurrent() {
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw RuntimeException("eglMakeCurrent failed")
        }
    }

    private fun setupTexture() {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun setupProgram() {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw RuntimeException("Program link failed: $log")
        }

        aPositionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        aTextureCoordHandle = GLES20.glGetAttribLocation(program, "aTextureCoord")
        uMvpMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        uStMatrixHandle = GLES20.glGetUniformLocation(program, "uSTMatrix")
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Shader compile failed: $log")
        }
        return shader
    }

    /**
     * Blocks (briefly - this only runs once per PLAY/TEARDOWN, not per-frame) until the GL
     * thread has actually torn down its EGL surface/context, mirroring the constructor's
     * own synchronous setup. The caller (RtspCameraStreamer.unbindCamera) must release this
     * before releasing the encoder - eglSwapBuffers targets the encoder's input Surface, and
     * calling it concurrently with the encoder releasing that same Surface would race, the
     * same class of bug already fixed once for the encoder's own drain loop.
     */
    fun release() {
        released = true
        val latch = CountDownLatch(1)
        handler.post {
            try {
                inputSurfaceTexture?.setOnFrameAvailableListener(null)
                inputSurfaceTexture?.release()
                inputSurface?.release()
                if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
                    if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                    EGL14.eglTerminate(eglDisplay)
                }
            } catch (e: Exception) {
                Timber.w("GlFrameTransformer: error releasing GL resources: $e")
            } finally {
                latch.countDown()
                thread.quitSafely()
            }
        }
        latch.await()
    }

    companion object {
        private val QUAD_VERTICES = floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f,
        )
        private val QUAD_TEX_COORDS = floatArrayOf(
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f,
        )

        private val quadVertices: FloatBuffer = ByteBuffer.allocateDirect(QUAD_VERTICES.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(QUAD_VERTICES); position(0) }
        private val quadTexCoords: FloatBuffer = ByteBuffer.allocateDirect(QUAD_TEX_COORDS.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(QUAD_TEX_COORDS); position(0) }

        private const val VERTEX_SHADER = """
            uniform mat4 uMVPMatrix;
            uniform mat4 uSTMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            varying vec2 vTextureCoord;
            void main() {
                gl_Position = uMVPMatrix * aPosition;
                vTextureCoord = (uSTMatrix * aTextureCoord).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTextureCoord);
            }
        """
    }
}
