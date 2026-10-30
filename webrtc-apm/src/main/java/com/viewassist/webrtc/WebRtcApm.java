package com.viewassist.webrtc;

import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * WebRTC Audio Processing Module wrapper for View Assist.
 *
 * Uses the modern WebRTC {@code AudioProcessingBuilder} / {@code Config} API
 * with AEC3, AGC2, RNN-based noise suppression, and transient suppression.
 *
 * Native library: libwebrtc_apms.so
 * Audio format:   16 kHz, 16-bit PCM, mono, 10 ms frames (160 samples).
 */
public class WebRtcApm implements AutoCloseable {

    private static final String TAG = "WebRtcApm";
    /** 10 ms at 16 kHz. Must match VacaAudioFormat.FRAME_SIZE_10MS in the app module. */
    private static final int FRAME_SIZE = 160;

    private long handle;
    private boolean closed = false;

    // Reusable buffers to avoid per-call allocations on the hot audio path
    private short[] renderLeftover = new short[0];
    private short[] renderMergeBuf = null;  // lazily allocated, reused across calls
    private short[] byteConvertBuf = null;  // reused by feedRenderAudioBytes

    // ── Noise Suppression Level ─────────────────────────────────────────────

    public enum NsLevel {
        LOW(0),
        MODERATE(1),
        HIGH(2),
        VERY_HIGH(3);

        final int value;
        NsLevel(int value) { this.value = value; }
    }

    // ── Configuration ───────────────────────────────────────────────────────

    public static class Config {
        /** Enable AEC3 echo cancellation. */
        public boolean aecEnabled = true;

        /** Enable RNN-based noise suppression. */
        public boolean nsEnabled = true;

        /** Noise suppression aggressiveness. */
        public NsLevel nsLevel = NsLevel.HIGH;

        /** Enable AGC2 adaptive digital gain control. */
        public boolean agcEnabled = true;

        /** Enable high-pass filter (removes DC offset and low-frequency rumble). */
        public boolean hpfEnabled = true;

        /** Enable transient suppressor (keyboard clicks, tapping). */
        public boolean transientSuppressionEnabled = true;

        /** Enable voice activity detection. */
        public boolean vadEnabled = false;
    }

    // ── Construction ────────────────────────────────────────────────────────

    /**
     * Create a new WebRTC APM instance with the given configuration.
     *
     * @throws Exception if native APM creation fails
     */
    public WebRtcApm(Config config) throws Exception {
        handle = NativeApm.nativeCreate(
                config.aecEnabled,
                config.nsEnabled,
                config.nsLevel.value,
                config.agcEnabled,
                config.hpfEnabled,
                config.transientSuppressionEnabled,
                config.vadEnabled
        );
        if (handle == 0) {
            throw new Exception("Failed to create native WebRTC APM");
        }
        Log.d(TAG, "WebRtcApm initialized successfully");
    }

    // ── Runtime Reconfiguration ─────────────────────────────────────────────

    /**
     * Change APM settings at runtime without destroying and recreating.
     *
     * @return 0 on success
     */
    public int reconfigure(Config config) {
        checkNotClosed();
        return NativeApm.nativeReconfigure(
                handle,
                config.aecEnabled,
                config.nsEnabled,
                config.nsLevel.value,
                config.agcEnabled,
                config.hpfEnabled,
                config.transientSuppressionEnabled,
                config.vadEnabled
        );
    }

    // ── Stream Processing ───────────────────────────────────────────────────

    /**
     * Process captured audio (near-end / microphone) in-place.
     * Applies AEC3, RNN NS, AGC2, HPF on the buffer.
     *
     * @param samples 16-bit PCM samples (mono, 16 kHz)
     * @param offset  starting index; must have 160 samples from offset
     * @return 0 on success
     */
    public int processCaptureStream(short[] samples, int offset) {
        checkNotClosed();
        return NativeApm.nativeProcessStream(handle, samples, offset);
    }

    /**
     * Process render audio (far-end / speaker output).
     * Provides the echo reference signal for AEC3.
     *
     * @param samples 16-bit PCM samples (mono, 16 kHz)
     * @param offset  starting index; must have 160 samples from offset
     * @return 0 on success
     */
    public int processRenderStream(short[] samples, int offset) {
        checkNotClosed();
        return NativeApm.nativeProcessReverseStream(handle, samples, offset);
    }

    /**
     * Feed render (far-end / speaker) audio of arbitrary length.
     * Internally buffers and slices into 160-sample frames,
     * calling {@link #processRenderStream} for each complete frame.
     *
     * Thread-safe relative to {@link #processCaptureStream} — WebRTC's
     * ProcessStream and ProcessReverseStream use internal locking.
     *
     * @param samples 16-bit PCM samples (mono, 16 kHz)
     * @param offset  starting index in the array
     * @param length  number of samples to process
     */
    public synchronized void feedRenderAudio(short[] samples, int offset, int length) {
        if (closed || handle == 0) return;

        // Prepend any leftover samples from the previous call
        int leftoverLen = renderLeftover.length;
        int totalLen = leftoverLen + length;
        short[] buf;
        int bufOffset;

        if (leftoverLen > 0) {
            // Reuse merge buffer if large enough, otherwise grow it
            if (renderMergeBuf == null || renderMergeBuf.length < totalLen) {
                renderMergeBuf = new short[totalLen];
            }
            System.arraycopy(renderLeftover, 0, renderMergeBuf, 0, leftoverLen);
            System.arraycopy(samples, offset, renderMergeBuf, leftoverLen, length);
            buf = renderMergeBuf;
            bufOffset = 0;
        } else {
            buf = samples;
            bufOffset = offset;
        }

        // Process complete 160-sample frames
        int pos = bufOffset;
        int end = bufOffset + totalLen;
        while (pos + FRAME_SIZE <= end) {
            NativeApm.nativeProcessReverseStream(handle, buf, pos);
            pos += FRAME_SIZE;
        }

        // Save any remaining samples for the next call
        int remaining = end - pos;
        if (remaining > 0) {
            if (renderLeftover.length != remaining) {
                renderLeftover = new short[remaining];
            }
            System.arraycopy(buf, pos, renderLeftover, 0, remaining);
        } else if (renderLeftover.length != 0) {
            renderLeftover = new short[0];
        }
    }

    /**
     * Convenience: feed render audio from a raw PCM byte array (little-endian).
     * Converts bytes to shorts and delegates to {@link #feedRenderAudio(short[], int, int)}.
     *
     * @param pcmBytes raw PCM 16-bit LE byte data
     */
    public void feedRenderAudioBytes(byte[] pcmBytes) {
        if (pcmBytes == null || pcmBytes.length < 2) return;
        int numSamples = pcmBytes.length / 2;
        // Reuse conversion buffer if large enough
        if (byteConvertBuf == null || byteConvertBuf.length < numSamples) {
            byteConvertBuf = new short[numSamples];
        }
        ByteBuffer.wrap(pcmBytes)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
                .get(byteConvertBuf, 0, numSamples);
        feedRenderAudio(byteConvertBuf, 0, numSamples);
    }

    // ── Delay & VAD ─────────────────────────────────────────────────────────

    /**
     * Set the estimated delay between render and capture streams in milliseconds.
     */
    public int setStreamDelay(int delayMs) {
        checkNotClosed();
        return NativeApm.nativeSetStreamDelay(handle, delayMs);
    }

    /**
     * Check if the last processed capture frame contained voice.
     * Only meaningful if {@link Config#vadEnabled} is true.
     */
    public boolean hasVoice() {
        checkNotClosed();
        return NativeApm.nativeHasVoice(handle);
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────

    @Override
    public void close() {
        if (!closed && handle != 0) {
            NativeApm.nativeDestroy(handle);
            handle = 0;
            closed = true;
            Log.d(TAG, "WebRtcApm closed");
        }
    }

    @Override
    protected void finalize() throws Throwable {
        super.finalize();
        close();
    }

    private void checkNotClosed() {
        if (closed) {
            throw new IllegalStateException("WebRtcApm is closed");
        }
    }
}
