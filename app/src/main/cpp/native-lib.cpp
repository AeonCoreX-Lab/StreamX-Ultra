#include <jni.h>
#include <android/log.h>
#include <string>
#include "mpv_handler.hpp"

#define TAG "StreamX_Native"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Global JNI reference to the Android Surface passed from Kotlin.
// Held so we can DeleteGlobalRef when the surface is replaced or cleared.
static jobject g_surface_ref = nullptr;


// ════════════════════════════════════════════════════════════
//  MPV JNI BRIDGES
// ════════════════════════════════════════════════════════════

extern "C" JNIEXPORT void JNICALL
Java_com_aeoncorex_streamx_ui_movie_StreamXCore_initMpvEngine(JNIEnv* env, jclass, jobject appctx) {
    init_mpv_engine(env, appctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_aeoncorex_streamx_ui_movie_StreamXCore_playMpvVideo(JNIEnv* env, jclass, jstring path) {
    const char* fp = env->GetStringUTFChars(path, nullptr);
    play_mpv_video(fp);
    env->ReleaseStringUTFChars(path, fp);
}

// wid = GlobalRef(Java Surface) cast to int64_t — NOT ANativeWindow*
extern "C" JNIEXPORT void JNICALL
Java_com_aeoncorex_streamx_ui_movie_StreamXCore_setMpvSurface(JNIEnv* env, jclass, jobject surface) {
    if (g_surface_ref) { env->DeleteGlobalRef(g_surface_ref); g_surface_ref = nullptr; }
    if (surface != nullptr) {
        g_surface_ref = env->NewGlobalRef(surface);
        set_mpv_wid(reinterpret_cast<intptr_t>(g_surface_ref));
    } else {
        set_mpv_wid(0);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_aeoncorex_streamx_ui_movie_StreamXCore_setMpvSurfaceSize(JNIEnv*, jclass, jint w, jint h) {
    set_mpv_surface_size(w, h);
}

extern "C" JNIEXPORT void JNICALL
Java_com_aeoncorex_streamx_ui_movie_StreamXCore_toggleVulkanFSR(JNIEnv*, jclass, jboolean enable) {
    toggle_vulkan_fsr(enable);
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_aeoncorex_streamx_ui_movie_StreamXCore_getMpvTime(JNIEnv*, jclass) { return get_mpv_time(); }

extern "C" JNIEXPORT jdouble JNICALL
Java_com_aeoncorex_streamx_ui_movie_StreamXCore_getMpvDuration(JNIEnv*, jclass) { return get_mpv_duration(); }

extern "C" JNIEXPORT void JNICALL
Java_com_aeoncorex_streamx_ui_movie_StreamXCore_seekMpvVideo(JNIEnv*, jclass, jdouble sec) {
    seek_mpv_video(sec);
}

extern "C" JNIEXPORT void JNICALL
Java_com_aeoncorex_streamx_ui_movie_StreamXCore_seekMpvAbsolute(JNIEnv*, jclass, jdouble pos) {
    seek_mpv_absolute(pos);
}

extern "C" JNIEXPORT void JNICALL
Java_com_aeoncorex_streamx_ui_movie_StreamXCore_pauseMpvVideo(JNIEnv*, jclass, jboolean pause) {
    pause_mpv_video(pause);
}

extern "C" JNIEXPORT void JNICALL
Java_com_aeoncorex_streamx_ui_movie_StreamXCore_commandNative(JNIEnv* env, jclass, jobjectArray arr) {
    int len = env->GetArrayLength(arr);
    const char* args[128] = {nullptr};
    for (int i = 0; i < len && i < 127; ++i) {
        auto s = (jstring)env->GetObjectArrayElement(arr, i);
        args[i] = env->GetStringUTFChars(s, nullptr);
        env->DeleteLocalRef(s);
    }
    command_mpv(args);
    for (int i = 0; i < len && i < 127; ++i) {
        auto s = (jstring)env->GetObjectArrayElement(arr, i);
        env->ReleaseStringUTFChars(s, args[i]);
        env->DeleteLocalRef(s);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_aeoncorex_streamx_ui_movie_StreamXCore_setPropertyStringNative(JNIEnv* env, jclass, jstring name, jstring value) {
    const char* n = env->GetStringUTFChars(name,  nullptr);
    const char* v = env->GetStringUTFChars(value, nullptr);
    set_property_string_mpv(n, v);
    env->ReleaseStringUTFChars(name, n); env->ReleaseStringUTFChars(value, v);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_aeoncorex_streamx_ui_movie_StreamXCore_getPropertyStringNative(JNIEnv* env, jclass, jstring name) {
    const char* n = env->GetStringUTFChars(name, nullptr);
    std::string val = get_property_string_mpv_safe(n);
    env->ReleaseStringUTFChars(name, n);
    return env->NewStringUTF(val.c_str());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_aeoncorex_streamx_ui_movie_StreamXCore_getPropertyIntNative(JNIEnv* env, jclass, jstring name) {
    const char* n = env->GetStringUTFChars(name, nullptr);
    int64_t val = get_property_int_mpv(n);
    env->ReleaseStringUTFChars(name, n);
    return (jlong)val;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_aeoncorex_streamx_ui_movie_StreamXCore_getMpvCachePercent(JNIEnv*, jclass) { return (jint)get_cache_percent_mpv(); }

extern "C" JNIEXPORT jboolean JNICALL
Java_com_aeoncorex_streamx_ui_movie_StreamXCore_isMpvPausedForCache(JNIEnv*, jclass) { return (jboolean)(is_paused_for_cache_mpv() != 0); }

extern "C" JNIEXPORT jstring JNICALL
Java_com_aeoncorex_streamx_ui_movie_StreamXCore_getTrackListNative(JNIEnv* env, jclass, jstring type) {
    const char* t = env->GetStringUTFChars(type, nullptr);
    std::string result = get_track_list_mpv(t);
    env->ReleaseStringUTFChars(type, t);
    return env->NewStringUTF(result.c_str());
}

// ════════════════════════════════════════════════════════════
//  DYNAMIC HW/SW DECODE COMPATIBILITY BRIDGES
// ════════════════════════════════════════════════════════════

// Called every ~250ms from Kotlin's existing time-sync poll loop.
// Cheap no-op after the first file-load check resolves (see
// check_decode_compatibility() in mpv_handler.cpp for details).
extern "C" JNIEXPORT void JNICALL
Java_com_aeoncorex_streamx_ui_movie_StreamXCore_checkDecodeCompat(JNIEnv*, jclass) {
    check_decode_compatibility();
}

// Human-readable current decode mode for the settings UI,
// e.g. "Hardware (mediacodec-copy)" / "Software (FFmpeg)".
extern "C" JNIEXPORT jstring JNICALL
Java_com_aeoncorex_streamx_ui_movie_StreamXCore_getDecodeModeLabel(JNIEnv* env, jclass) {
    std::string label = get_decode_mode_label();
    return env->NewStringUTF(label.c_str());
}

// Diagnostic string "<codec>|<pixelformat>|<hwdec-current>|<auto_switched>|<reason>"
// for the detailed decode-info settings page.
extern "C" JNIEXPORT jstring JNICALL
Java_com_aeoncorex_streamx_ui_movie_StreamXCore_getDecodeDiagInfo(JNIEnv* env, jclass) {
    std::string diag = get_decode_diag_info();
    return env->NewStringUTF(diag.c_str());
}

// Persistent manual override for devices with undetectable broken HW
// decoders (black frame on ordinary 8-bit content). Kotlin persists
// this in SharedPreferences and calls it once at app start plus
// whenever the user toggles it in Settings.
extern "C" JNIEXPORT void JNICALL
Java_com_aeoncorex_streamx_ui_movie_StreamXCore_setForceSwDecode(JNIEnv*, jclass, jboolean force) {
    set_force_sw_decode((bool)force);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_aeoncorex_streamx_ui_movie_StreamXCore_getForceSwDecode(JNIEnv*, jclass) {
    return (jboolean)get_force_sw_decode();
}

// Human-readable active GPU rendering backend, e.g. "Vulkan (androidvk)"
// or "OpenGL ES (android)" — reflects mpv's own gpu-context probe result.
extern "C" JNIEXPORT jstring JNICALL
Java_com_aeoncorex_streamx_ui_movie_StreamXCore_getActiveGpuContext(JNIEnv* env, jclass) {
    std::string ctx = get_active_gpu_context();
    return env->NewStringUTF(ctx.c_str());
}

// ════════════════════════════════════════════════════════════
//  PLAYBACK-ERROR BRIDGES (DIRECT-URL retry support)
// ════════════════════════════════════════════════════════════

// Empty string = no pending error. Non-empty = mpv_error_string() text
// from the most recent MPV_END_FILE_REASON_ERROR event.
extern "C" JNIEXPORT jstring JNICALL
Java_com_aeoncorex_streamx_ui_movie_StreamXCore_getMpvLastError(JNIEnv* env, jclass) {
    std::string err = get_last_playback_error();
    return env->NewStringUTF(err.c_str());
}

// Monotonically increasing counter — bumped on every new playback
// error. Kotlin compares against the last generation it reacted to,
// so it can tell "same error still pending" apart from "a new one
// just happened" without racing on the message string itself.
extern "C" JNIEXPORT jint JNICALL
Java_com_aeoncorex_streamx_ui_movie_StreamXCore_getMpvErrorGeneration(JNIEnv*, jclass) {
    return (jint)get_playback_error_generation();
}

extern "C" JNIEXPORT void JNICALL
Java_com_aeoncorex_streamx_ui_movie_StreamXCore_clearMpvLastError(JNIEnv*, jclass) {
    clear_last_playback_error();
}
