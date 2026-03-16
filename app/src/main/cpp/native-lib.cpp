#include <jni.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <string>
#include "torrent_system.hpp"
#include "mpv_handler.hpp"

#define TAG "StreamX_Native"

extern "C" {
    __attribute__((weak))
    ssize_t __sendto_chk(int fd, const void* buf, size_t len, size_t buflen, int flags, const struct sockaddr* addr, socklen_t addr_len) {
        return sendto(fd, buf, len, flags, addr, addr_len);
    }
}

static TorrentSystem* torrentEngine = nullptr;

// --- MPV JNI BRIDGES (Delegating to mpv_handler.cpp) ---

extern "C" JNIEXPORT void JNICALL
Java_com_aeoncorex_streamx_ui_movie_StreamXCore_initMpvEngine(JNIEnv* env, jclass clazz) {
    init_mpv_engine();
}

extern "C" JNIEXPORT void JNICALL
Java_com_aeoncorex_streamx_ui_movie_StreamXCore_playMpvVideo(JNIEnv* env, jclass clazz, jstring path) {
    const char* file_path = env->GetStringUTFChars(path, nullptr);
    play_mpv_video(file_path);
    env->ReleaseStringUTFChars(path, file_path);
}

extern "C" JNIEXPORT void JNICALL
Java_com_aeoncorex_streamx_ui_movie_StreamXCore_setMpvSurface(JNIEnv* env, jclass clazz, jobject surface) {
    ANativeWindow* window = nullptr;
    if (surface != nullptr) {
        window = ANativeWindow_fromSurface(env, surface);
    }
    set_mpv_surface(window);
    
    // FIX: Memory leak prevention. Always release ANativeWindow ref.
    if (window != nullptr) {
        ANativeWindow_release(window); 
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_aeoncorex_streamx_ui_movie_StreamXCore_toggleVulkanFSR(JNIEnv* env, jclass clazz, jboolean enable) {
    toggle_vulkan_fsr(enable);
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_aeoncorex_streamx_ui_movie_StreamXCore_getMpvTime(JNIEnv* env, jclass clazz) {
    return get_mpv_time();
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_aeoncorex_streamx_ui_movie_StreamXCore_getMpvDuration(JNIEnv* env, jclass clazz) {
    return get_mpv_duration();
}

extern "C" JNIEXPORT void JNICALL
Java_com_aeoncorex_streamx_ui_movie_StreamXCore_seekMpvVideo(JNIEnv* env, jclass clazz, jdouble seconds) {
    seek_mpv_video(seconds);
}

extern "C" JNIEXPORT void JNICALL
Java_com_aeoncorex_streamx_ui_movie_StreamXCore_pauseMpvVideo(JNIEnv* env, jclass clazz, jboolean pause) {
    pause_mpv_video(pause);
}

extern "C" JNIEXPORT void JNICALL
Java_com_aeoncorex_streamx_ui_movie_StreamXCore_commandNative(JNIEnv* env, jclass clazz, jobjectArray jarray) {
    int len = env->GetArrayLength(jarray);
    const char *arguments[128] = {0};
    
    for (int i = 0; i < len && i < 127; ++i) {
        jstring str = (jstring)env->GetObjectArrayElement(jarray, i);
        arguments[i] = env->GetStringUTFChars(str, NULL);
    }
    
    command_mpv(arguments);
    
    for (int i = 0; i < len && i < 127; ++i) {
        jstring str = (jstring)env->GetObjectArrayElement(jarray, i);
        env->ReleaseStringUTFChars(str, arguments[i]);
        env->DeleteLocalRef(str);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_aeoncorex_streamx_ui_movie_StreamXCore_setPropertyStringNative(JNIEnv* env, jclass clazz, jstring name, jstring value) {
    const char* prop_name = env->GetStringUTFChars(name, nullptr);
    const char* prop_value = env->GetStringUTFChars(value, nullptr);
    set_property_string_mpv(prop_name, prop_value);
    env->ReleaseStringUTFChars(name, prop_name);
    env->ReleaseStringUTFChars(value, prop_value);
}

// --- TORRENT ENGINE BRIDGES ---
extern "C" JNIEXPORT void JNICALL
Java_com_aeoncorex_streamx_ui_movie_TorrentEngine_initNative(JNIEnv* env, jobject thiz) {
    if (!torrentEngine) torrentEngine = new TorrentSystem();
}
extern "C" JNIEXPORT void JNICALL
Java_com_aeoncorex_streamx_ui_movie_TorrentEngine_startNative(JNIEnv* env, jobject thiz, jstring magnet, jstring path) {
    if (!torrentEngine) torrentEngine = new TorrentSystem();
    const char* m = env->GetStringUTFChars(magnet, nullptr);
    const char* p = env->GetStringUTFChars(path, nullptr);
    torrentEngine->start(m, p);
    env->ReleaseStringUTFChars(magnet, m);
    env->ReleaseStringUTFChars(path, p);
}
extern "C" JNIEXPORT void JNICALL
Java_com_aeoncorex_streamx_ui_movie_TorrentEngine_stopNative(JNIEnv* env, jobject thiz) {
    if (torrentEngine) { torrentEngine->stop(); delete torrentEngine; torrentEngine = nullptr; }
}
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_aeoncorex_streamx_ui_movie_TorrentEngine_getStatusNative(JNIEnv* env, jobject thiz) {
    if (!torrentEngine) return nullptr;
    EngineStatus s = torrentEngine->getStatus();
    jlongArray result = env->NewLongArray(5);
    jlong fill[5] = { (jlong)s.progress, s.speed, (jlong)s.seeds, (jlong)s.peers, (jlong)s.state };
    env->SetLongArrayRegion(result, 0, 5, fill);
    return result;
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_aeoncorex_streamx_ui_movie_TorrentEngine_getFilePathNative(JNIEnv* env, jobject thiz) {
    if (!torrentEngine) return env->NewStringUTF("");
    return env->NewStringUTF(torrentEngine->getFilePath().c_str());
}
