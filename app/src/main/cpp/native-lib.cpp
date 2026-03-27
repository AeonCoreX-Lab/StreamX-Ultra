#include <jni.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <string>
#include "torrent_system.hpp"
#include "mpv_handler.hpp"

#define TAG "StreamX_Native"

extern "C" {
    __attribute__((weak))
    ssize_t __sendto_chk(int fd, const void* buf, size_t len, size_t buflen,
                         int flags, const struct sockaddr* addr, socklen_t addr_len) {
        return sendto(fd, buf, len, flags, addr, addr_len);
    }
}

static TorrentSystem* torrentEngine = nullptr;

// ════════════════════════════════════════════════════════════
//  MPV JNI BRIDGES
// ════════════════════════════════════════════════════════════

extern "C" JNIEXPORT void JNICALL
Java_com_aeoncorex_streamx_ui_movie_StreamXCore_initMpvEngine(JNIEnv*, jclass) {
    init_mpv_engine();
}

extern "C" JNIEXPORT void JNICALL
Java_com_aeoncorex_streamx_ui_movie_StreamXCore_playMpvVideo(JNIEnv* env, jclass, jstring path) {
    const char* fp = env->GetStringUTFChars(path, nullptr);
    play_mpv_video(fp);
    env->ReleaseStringUTFChars(path, fp);
}

// CRITICAL: ANativeWindow ownership transferred to set_mpv_surface() — do NOT release here
extern "C" JNIEXPORT void JNICALL
Java_com_aeoncorex_streamx_ui_movie_StreamXCore_setMpvSurface(JNIEnv* env, jclass, jobject surface) {
    ANativeWindow* w = nullptr;
    if (surface != nullptr) w = ANativeWindow_fromSurface(env, surface);
    set_mpv_surface(w);
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
Java_com_aeoncorex_streamx_ui_movie_StreamXCore_getMpvTime(JNIEnv*, jclass) {
    return get_mpv_time();
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_aeoncorex_streamx_ui_movie_StreamXCore_getMpvDuration(JNIEnv*, jclass) {
    return get_mpv_duration();
}

extern "C" JNIEXPORT void JNICALL
Java_com_aeoncorex_streamx_ui_movie_StreamXCore_seekMpvVideo(JNIEnv*, jclass, jdouble sec) {
    seek_mpv_video(sec);
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
Java_com_aeoncorex_streamx_ui_movie_StreamXCore_setPropertyStringNative(
        JNIEnv* env, jclass, jstring name, jstring value) {
    const char* n = env->GetStringUTFChars(name,  nullptr);
    const char* v = env->GetStringUTFChars(value, nullptr);
    set_property_string_mpv(n, v);
    env->ReleaseStringUTFChars(name,  n);
    env->ReleaseStringUTFChars(value, v);
}

// ─────────────────────────────────────────────────────────────
//  getPropertyStringNative — SAFE version
//
//  CRASH WAS HERE:
//    Old code returned raw char* from mpv_get_property_string and called
//    free() on it in this file.  MPV uses its own allocator (mpv_free),
//    NOT libc free().  Calling free() on an mpv-allocated pointer →
//    Scudo "corrupted chunk header / double free" → crash.
//
//  FIX: get_property_string_mpv_safe() copies to std::string and calls
//  mpv_free internally.  This function only sees std::string — no raw
//  MPV pointer, no manual free needed.
// ─────────────────────────────────────────────────────────────
extern "C" JNIEXPORT jstring JNICALL
Java_com_aeoncorex_streamx_ui_movie_StreamXCore_getPropertyStringNative(JNIEnv* env, jclass, jstring name) {
    const char* n = env->GetStringUTFChars(name, nullptr);
    std::string val = get_property_string_mpv_safe(n);  // safe: no raw pointer returned
    env->ReleaseStringUTFChars(name, n);
    return env->NewStringUTF(val.c_str());
    // ← NO free() needed. std::string destructs automatically. ✓
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_aeoncorex_streamx_ui_movie_StreamXCore_getPropertyIntNative(JNIEnv* env, jclass, jstring name) {
    const char* n = env->GetStringUTFChars(name, nullptr);
    int64_t val   = get_property_int_mpv(n);
    env->ReleaseStringUTFChars(name, n);
    return (jlong)val;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_aeoncorex_streamx_ui_movie_StreamXCore_getMpvCachePercent(JNIEnv*, jclass) {
    return (jint)get_cache_percent_mpv();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_aeoncorex_streamx_ui_movie_StreamXCore_isMpvPausedForCache(JNIEnv*, jclass) {
    return (jboolean)(is_paused_for_cache_mpv() != 0);
}

// ─────────────────────────────────────────────────────────────
//  getTrackListNative — single-call track list (no JNI loop)
//
//  Returns pipe-separated track data: "id|title|selected;id|title|..."
//  All MPV property reads happen inside one C++ function under one
//  mutex lock. Kotlin parses the string — no loop JNI calls, no crash.
// ─────────────────────────────────────────────────────────────
extern "C" JNIEXPORT jstring JNICALL
Java_com_aeoncorex_streamx_ui_movie_StreamXCore_getTrackListNative(JNIEnv* env, jclass, jstring type) {
    const char* t = env->GetStringUTFChars(type, nullptr);
    std::string result = get_track_list_mpv(t);
    env->ReleaseStringUTFChars(type, t);
    return env->NewStringUTF(result.c_str());
}

// ════════════════════════════════════════════════════════════
//  TORRENT ENGINE JNI BRIDGES
// ════════════════════════════════════════════════════════════

extern "C" JNIEXPORT void JNICALL
Java_com_aeoncorex_streamx_ui_movie_TorrentEngine_initNative(JNIEnv*, jobject) {
    if (!torrentEngine) torrentEngine = new TorrentSystem();
}

extern "C" JNIEXPORT void JNICALL
Java_com_aeoncorex_streamx_ui_movie_TorrentEngine_startNative(JNIEnv* env, jobject, jstring magnet, jstring path) {
    if (!torrentEngine) torrentEngine = new TorrentSystem();
    const char* m = env->GetStringUTFChars(magnet, nullptr);
    const char* p = env->GetStringUTFChars(path,   nullptr);
    torrentEngine->start(m, p);
    env->ReleaseStringUTFChars(magnet, m);
    env->ReleaseStringUTFChars(path,   p);
}

extern "C" JNIEXPORT void JNICALL
Java_com_aeoncorex_streamx_ui_movie_TorrentEngine_stopNative(JNIEnv*, jobject) {
    if (torrentEngine) { torrentEngine->stop(); delete torrentEngine; torrentEngine = nullptr; }
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_aeoncorex_streamx_ui_movie_TorrentEngine_getStatusNative(JNIEnv* env, jobject) {
    if (!torrentEngine) return nullptr;
    EngineStatus s = torrentEngine->getStatus();
    jlongArray r = env->NewLongArray(5);
    jlong fill[5] = {(jlong)s.progress, (jlong)s.speed, (jlong)s.seeds, (jlong)s.peers, (jlong)s.state};
    env->SetLongArrayRegion(r, 0, 5, fill);
    return r;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_aeoncorex_streamx_ui_movie_TorrentEngine_getFilePathNative(JNIEnv* env, jobject) {
    if (!torrentEngine) return env->NewStringUTF("");
    return env->NewStringUTF(torrentEngine->getFilePath().c_str());
}
