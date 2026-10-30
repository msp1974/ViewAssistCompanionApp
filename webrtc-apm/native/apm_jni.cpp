/*
 * JNI bridge for the modern WebRTC Audio Processing Module.
 *
 * Uses webrtc::AudioProcessingBuilder with the Config struct API
 * (AEC3, AGC2, RNN-based NS, transient suppression).
 *
 * Registers native methods against com.viewassist.webrtc.NativeApm.
 *
 * Audio format: 16 kHz, 16-bit PCM, mono, 10 ms frames (160 samples).
 */

#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <cstring>
#include <algorithm>

#include "api/audio/audio_processing.h"
#include "api/audio/builtin_audio_processing_builder.h"
#include "api/environment/environment_factory.h"
#include "api/scoped_refptr.h"
#include "modules/audio_processing/include/audio_processing.h"

#define TAG "NativeApm"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static constexpr int kSampleRateHz = 16000;
static constexpr int kNumChannels  = 1;
static constexpr int kFrameSize    = 160;  // 10 ms at 16 kHz

// ---------------------------------------------------------------------------
// Per-instance context stored as a jlong handle on the Java side.
// ---------------------------------------------------------------------------
struct ApmContext {
    webrtc::scoped_refptr<webrtc::AudioProcessing> apm;
    webrtc::StreamConfig stream_config{kSampleRateHz, kNumChannels};
    bool vad_enabled  = false;
    bool last_vad     = false;
};

// ---------------------------------------------------------------------------
// Helper: map NS level ordinal (0-3) to the Config enum.
// ---------------------------------------------------------------------------
static webrtc::AudioProcessing::Config::NoiseSuppression::Level
MapNsLevel(jint level) {
    using NsLevel = webrtc::AudioProcessing::Config::NoiseSuppression::Level;
    switch (level) {
        case 0:  return NsLevel::kLow;
        case 1:  return NsLevel::kModerate;
        case 2:  return NsLevel::kHigh;
        case 3:  return NsLevel::kVeryHigh;
        default: return NsLevel::kHigh;
    }
}

// ---------------------------------------------------------------------------
// Helper: build a Config struct from the Java parameters.
// ---------------------------------------------------------------------------
static webrtc::AudioProcessing::Config
BuildConfig(jboolean aecEnabled,
            jboolean nsEnabled,
            jint     nsLevel,
            jboolean agc2Enabled,
            jboolean hpfEnabled,
            jboolean transientSuppressionEnabled,
            jboolean vadEnabled) {

    webrtc::AudioProcessing::Config config;

    config.high_pass_filter.enabled       = hpfEnabled;

    config.echo_canceller.enabled         = aecEnabled;

    config.noise_suppression.enabled      = nsEnabled;
    config.noise_suppression.level        = MapNsLevel(nsLevel);

    config.gain_controller2.enabled                  = agc2Enabled;
    config.gain_controller2.adaptive_digital.enabled  = agc2Enabled;

    config.transient_suppression.enabled  = transientSuppressionEnabled;

    return config;
}

// ===========================  Native Methods  ==============================

static jlong nativeCreate(JNIEnv* /*env*/, jclass /*clazz*/,
                           jboolean aecEnabled,
                           jboolean nsEnabled,
                           jint     nsLevel,
                           jboolean agc2Enabled,
                           jboolean hpfEnabled,
                           jboolean transientSuppressionEnabled,
                           jboolean vadEnabled) {

    auto config = BuildConfig(aecEnabled,
                              nsEnabled, nsLevel,
                              agc2Enabled, hpfEnabled,
                              transientSuppressionEnabled, vadEnabled);

    auto apm = webrtc::BuiltinAudioProcessingBuilder(config)
                   .Build(webrtc::CreateEnvironment());
    if (!apm) {
        LOGE("Failed to create AudioProcessing instance");
        return 0;
    }

    auto* ctx        = new ApmContext();
    ctx->apm         = std::move(apm);
    ctx->vad_enabled = vadEnabled;

    LOGI("APM created successfully (AEC=%d NS=%d/%d AGC2=%d HPF=%d TS=%d VAD=%d)",
         aecEnabled, nsEnabled, nsLevel,
         agc2Enabled, hpfEnabled, transientSuppressionEnabled, vadEnabled);

    return reinterpret_cast<jlong>(ctx);
}

// ---------------------------------------------------------------------------
static void nativeDestroy(JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    auto* ctx = reinterpret_cast<ApmContext*>(handle);
    if (ctx) {
        LOGI("APM destroyed");
        delete ctx;
    }
}

// ---------------------------------------------------------------------------
// Process captured (near-end / microphone) audio.
// Modifies the buffer in-place.
// ---------------------------------------------------------------------------
static jint nativeProcessStream(JNIEnv* env, jclass /*clazz*/,
                                 jlong handle,
                                 jshortArray nearEnd,
                                 jint offset) {
    auto* ctx = reinterpret_cast<ApmContext*>(handle);
    if (!ctx) return -1;

    jsize arrayLen = env->GetArrayLength(nearEnd);
    if (offset < 0 || offset + kFrameSize > arrayLen) {
        LOGE("ProcessStream: offset %d out of bounds (array length %d, need %d)",
             offset, arrayLen, offset + kFrameSize);
        return -1;
    }

    jshort* samples = env->GetShortArrayElements(nearEnd, nullptr);
    if (!samples) return -1;

    // Convert int16 → float [-1.0, 1.0]
    float float_buf[kFrameSize];
    for (int i = 0; i < kFrameSize; ++i) {
        float_buf[i] = samples[offset + i] / 32768.0f;
    }

    float* channel_ptrs[1] = {float_buf};

    int ret = ctx->apm->ProcessStream(
        channel_ptrs,
        ctx->stream_config,   // input config
        ctx->stream_config,   // output config
        channel_ptrs);

    if (ret != 0) {
        LOGW("ProcessStream returned %d", ret);
    }

    // Convert float → int16 (with clamping)
    for (int i = 0; i < kFrameSize; ++i) {
        float val = float_buf[i] * 32768.0f;
        val = std::max(-32768.0f, std::min(32767.0f, val));
        samples[offset + i] = static_cast<jshort>(val);
    }

    // Query VAD after processing
    if (ctx->vad_enabled) {
        auto stats = ctx->apm->GetStatistics();
        ctx->last_vad = stats.voice_detected.value_or(false);
    }

    env->ReleaseShortArrayElements(nearEnd, samples, 0);  // 0 = copy back
    return ret;
}

// ---------------------------------------------------------------------------
// Process render (far-end / speaker) audio.
// This provides the echo reference signal to AEC3.
// ---------------------------------------------------------------------------
static jint nativeProcessReverseStream(JNIEnv* env, jclass /*clazz*/,
                                        jlong handle,
                                        jshortArray farEnd,
                                        jint offset) {
    auto* ctx = reinterpret_cast<ApmContext*>(handle);
    if (!ctx) return -1;

    jsize arrayLen = env->GetArrayLength(farEnd);
    if (offset < 0 || offset + kFrameSize > arrayLen) {
        LOGE("ProcessReverseStream: offset %d out of bounds (array length %d, need %d)",
             offset, arrayLen, offset + kFrameSize);
        return -1;
    }

    jshort* samples = env->GetShortArrayElements(farEnd, nullptr);
    if (!samples) return -1;

    float float_buf[kFrameSize];
    for (int i = 0; i < kFrameSize; ++i) {
        float_buf[i] = samples[offset + i] / 32768.0f;
    }

    float* channel_ptrs[1] = {float_buf};

    int ret = ctx->apm->ProcessReverseStream(
        channel_ptrs,
        ctx->stream_config,   // input config
        ctx->stream_config,   // output config
        channel_ptrs);

    if (ret != 0) {
        LOGW("ProcessReverseStream returned %d", ret);
    }

    // Release without copying back — we don't modify the render buffer
    env->ReleaseShortArrayElements(farEnd, samples, JNI_ABORT);
    return ret;
}

// ---------------------------------------------------------------------------
static jint nativeSetStreamDelay(JNIEnv* /*env*/, jclass /*clazz*/,
                                  jlong handle, jint delayMs) {
    auto* ctx = reinterpret_cast<ApmContext*>(handle);
    if (!ctx) return -1;
    return ctx->apm->set_stream_delay_ms(delayMs);
}

// ---------------------------------------------------------------------------
static jboolean nativeHasVoice(JNIEnv* /*env*/, jclass /*clazz*/,
                                jlong handle) {
    auto* ctx = reinterpret_cast<ApmContext*>(handle);
    if (!ctx) return JNI_FALSE;
    return ctx->last_vad ? JNI_TRUE : JNI_FALSE;
}

// ---------------------------------------------------------------------------
// Reconfigure the APM at runtime without destroying / recreating.
// ---------------------------------------------------------------------------
static jint nativeReconfigure(JNIEnv* /*env*/, jclass /*clazz*/,
                               jlong handle,
                               jboolean aecEnabled,
                               jboolean nsEnabled,
                               jint     nsLevel,
                               jboolean agc2Enabled,
                               jboolean hpfEnabled,
                               jboolean transientSuppressionEnabled,
                               jboolean vadEnabled) {

    auto* ctx = reinterpret_cast<ApmContext*>(handle);
    if (!ctx) return -1;

    auto config = BuildConfig(aecEnabled,
                              nsEnabled, nsLevel,
                              agc2Enabled, hpfEnabled,
                              transientSuppressionEnabled, vadEnabled);

    ctx->apm->ApplyConfig(config);
    ctx->vad_enabled = vadEnabled;

    LOGI("APM reconfigured (AEC=%d NS=%d/%d AGC2=%d HPF=%d TS=%d VAD=%d)",
         aecEnabled, nsEnabled, nsLevel,
         agc2Enabled, hpfEnabled, transientSuppressionEnabled, vadEnabled);

    return 0;
}

// ==========================  JNI Registration  =============================

static const JNINativeMethod sMethods[] = {
    // name                         signature                       function pointer
    {"nativeCreate",                "(ZZIZZZZ)J",                   reinterpret_cast<void*>(nativeCreate)},
    {"nativeDestroy",               "(J)V",                         reinterpret_cast<void*>(nativeDestroy)},
    {"nativeProcessStream",         "(J[SI)I",                      reinterpret_cast<void*>(nativeProcessStream)},
    {"nativeProcessReverseStream",  "(J[SI)I",                      reinterpret_cast<void*>(nativeProcessReverseStream)},
    {"nativeSetStreamDelay",        "(JI)I",                        reinterpret_cast<void*>(nativeSetStreamDelay)},
    {"nativeHasVoice",              "(J)Z",                         reinterpret_cast<void*>(nativeHasVoice)},
    {"nativeReconfigure",           "(JZZIZZZZ)I",                  reinterpret_cast<void*>(nativeReconfigure)},
};

extern "C" jint JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        LOGE("JNI_OnLoad: GetEnv failed");
        return JNI_ERR;
    }

    jclass clazz = env->FindClass("com/viewassist/webrtc/NativeApm");
    if (!clazz) {
        LOGE("JNI_OnLoad: FindClass com/viewassist/webrtc/NativeApm failed");
        return JNI_ERR;
    }

    if (env->RegisterNatives(clazz, sMethods,
                             sizeof(sMethods) / sizeof(sMethods[0])) < 0) {
        LOGE("JNI_OnLoad: RegisterNatives failed");
        return JNI_ERR;
    }

    LOGI("JNI_OnLoad: registered %zu native methods for NativeApm",
         sizeof(sMethods) / sizeof(sMethods[0]));

    return JNI_VERSION_1_6;
}
