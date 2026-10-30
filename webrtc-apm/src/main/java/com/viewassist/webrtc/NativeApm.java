package com.viewassist.webrtc;

/**
 * Low-level JNI bridge to the modern WebRTC AudioProcessing module.
 *
 * All configuration is passed at creation time via {@link #nativeCreate}
 * to match the modern {@code AudioProcessingBuilder} pattern.
 * Runtime reconfiguration is available via {@link #nativeReconfigure}.
 *
 * Audio format: 16 kHz, 16-bit PCM, mono, 10 ms frames (160 samples).
 *
 * Package-private — use {@link WebRtcApm} as the public API.
 */
class NativeApm {

    static {
        System.loadLibrary("webrtc_apms");
    }

    private NativeApm() {}

    /**
     * Create a new APM instance with the given configuration.
     *
     * @param aecEnabled                   enable AEC3 echo cancellation
     * @param nsEnabled                    enable RNN-based noise suppression
     * @param nsLevel                      noise suppression level (0=Low, 1=Moderate, 2=High, 3=VeryHigh)
     * @param agc2Enabled                  enable AGC2 adaptive digital gain
     * @param hpfEnabled                   enable high-pass filter (DC offset removal)
     * @param transientSuppressionEnabled  enable transient suppressor (keyboard clicks, taps)
     * @param vadEnabled                   enable voice activity detection
     * @return opaque native handle, or 0 on failure
     */
    static native long nativeCreate(
            boolean aecEnabled,
            boolean nsEnabled,
            int nsLevel,
            boolean agc2Enabled,
            boolean hpfEnabled,
            boolean transientSuppressionEnabled,
            boolean vadEnabled
    );

    static native void nativeDestroy(long handle);

    /**
     * Process captured (near-end) audio in-place.
     *
     * @param nearEnd 16-bit PCM samples array
     * @param offset  starting index; must have 160 samples from offset
     * @return 0 on success
     */
    static native int nativeProcessStream(long handle, short[] nearEnd, int offset);

    /**
     * Process render (far-end / speaker) audio.
     * Provides the echo reference signal for AEC3.
     *
     * @param farEnd 16-bit PCM samples array
     * @param offset starting index; must have 160 samples from offset
     * @return 0 on success
     */
    static native int nativeProcessReverseStream(long handle, short[] farEnd, int offset);

    static native int nativeSetStreamDelay(long handle, int delayMs);

    static native boolean nativeHasVoice(long handle);

    /**
     * Reconfigure the APM at runtime without destroying and recreating it.
     * Same parameter semantics as {@link #nativeCreate}.
     *
     * @return 0 on success
     */
    static native int nativeReconfigure(
            long handle,
            boolean aecEnabled,
            boolean nsEnabled,
            int nsLevel,
            boolean agc2Enabled,
            boolean hpfEnabled,
            boolean transientSuppressionEnabled,
            boolean vadEnabled
    );
}
