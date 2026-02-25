#include <jni.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <string>
#include <locale.h>
#include <mpv/client.h>
#include "torrent_system.hpp"

#define TAG "StreamX_Native"

extern "C" {
    __attribute__((weak))
    ssize_t __sendto_chk(int fd, const void* buf, size_t len, size_t buflen, int flags, const struct sockaddr* addr, socklen_t addr_len) {
        return sendto(fd, buf, len, flags, addr, addr_len);
    }
}

static TorrentSystem* torrentEngine = nullptr;
static mpv_handle* mpv_ctx = nullptr;

// --- MPV & Vulkan Setup ---
extern "C" JNIEXPORT void JNICALL
Java_com_aeoncorex_streamx_ui_movie_StreamXCore_initMpvEngine(JNIEnv* env, jobject thiz) {
    // FIX: Locale C setup for proper subtitle parsing and timing
    setlocale(LC_NUMERIC, "C");

    if (!mpv_ctx) {
        mpv_ctx = mpv_create();
        if (mpv_ctx) {
            mpv_set_option_string(mpv_ctx, "vo", "gpu");
            mpv_set_option_string(mpv_ctx, "gpu-api", "vulkan"); // Hardware Vulkan Output
            mpv_set_option_string(mpv_ctx, "hwdec", "auto");
            
            // Subtitle Engine Setup (mpv-android defaults)
            mpv_set_option_string(mpv_ctx, "sub-auto", "fuzzy"); 
            mpv_set_option_string(mpv_ctx, "sub-ass-override", "force"); // Better styling
            mpv_set_option_string(mpv_ctx, "sub-font-size", "45");
            
            mpv_initialize(mpv_ctx);
            __android_log_print(ANDROID_LOG_DEBUG, TAG, "MPV Engine Initialized");
        }
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_aeoncorex_streamx_ui_movie_StreamXCore_playMpvVideo(JNIEnv* env, jobject thiz, jstring path) {
    if (!mpv_ctx) return;
    const char* file_path = env->GetStringUTFChars(path, nullptr);
    const char* cmd[] = {"loadfile", file_path, NULL};
    mpv_command(mpv_ctx, cmd);
    env->ReleaseStringUTFChars(path, file_path);
}

extern "C" JNIEXPORT void JNICALL
Java_com_aeoncorex_streamx_ui_movie_StreamXCore_setMpvSurface(JNIEnv* env, jobject thiz, jobject surface) {
    if (!mpv_ctx) return;
    if (surface != nullptr) {
        ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
        int64_t wid = (int64_t)window;
        mpv_set_property(mpv_ctx, "wid", MPV_FORMAT_INT64, &wid);
    } else {
        int64_t wid = 0;
        mpv_set_property(mpv_ctx, "wid", MPV_FORMAT_INT64, &wid);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_aeoncorex_streamx_ui_movie_StreamXCore_toggleVulkanFSR(JNIEnv* env, jobject thiz, jboolean enable) {
    if (!mpv_ctx) return;
    if (enable) {
        mpv_set_option_string(mpv_ctx, "scale", "ewa_lanczossharp");
        mpv_set_option_string(mpv_ctx, "cscale", "ewa_lanczossharp");
    } else {
        mpv_set_option_string(mpv_ctx, "scale", "bilinear");
        mpv_set_option_string(mpv_ctx, "cscale", "bilinear");
    }
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_aeoncorex_streamx_ui_movie_StreamXCore_getMpvTime(JNIEnv* env, jobject thiz) {
    if (!mpv_ctx) return 0.0;
    double time_pos = 0.0;
    mpv_get_property(mpv_ctx, "time-pos", MPV_FORMAT_DOUBLE, &time_pos);
    return time_pos;
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_aeoncorex_streamx_ui_movie_StreamXCore_getMpvDuration(JNIEnv* env, jobject thiz) {
    if (!mpv_ctx) return 0.0;
    double duration = 0.0;
    mpv_get_property(mpv_ctx, "duration", MPV_FORMAT_DOUBLE, &duration);
    return duration;
}

extern "C" JNIEXPORT void JNICALL
Java_com_aeoncorex_streamx_ui_movie_StreamXCore_seekMpvVideo(JNIEnv* env, jobject thiz, jdouble seconds) {
    if (!mpv_ctx) return;
    std::string sec_str = std::to_string(seconds);
    const char* cmd[] = {"seek", sec_str.c_str(), "relative", NULL};
    mpv_command(mpv_ctx, cmd);
}

extern "C" JNIEXPORT void JNICALL
Java_com_aeoncorex_streamx_ui_movie_StreamXCore_pauseMpvVideo(JNIEnv* env, jobject thiz, jboolean pause) {
    if (!mpv_ctx) return;
    int pause_val = pause ? 1 : 0;
    mpv_set_property(mpv_ctx, "pause", MPV_FORMAT_FLAG, &pause_val);
}

// --- NEW GENERIC BRIDGES (from mpv-android) ---

extern "C" JNIEXPORT void JNICALL
Java_com_aeoncorex_streamx_ui_movie_StreamXCore_commandNative(JNIEnv* env, jobject thiz, jobjectArray jarray) {
    if (!mpv_ctx) return;
    int len = env->GetArrayLength(jarray);
    const char *arguments[128] = {0};
    
    for (int i = 0; i < len && i < 127; ++i) {
        jstring str = (jstring)env->GetObjectArrayElement(jarray, i);
        arguments[i] = env->GetStringUTFChars(str, NULL);
    }
    
    mpv_command(mpv_ctx, arguments);
    
    for (int i = 0; i < len && i < 127; ++i) {
        jstring str = (jstring)env->GetObjectArrayElement(jarray, i);
        env->ReleaseStringUTFChars(str, arguments[i]);
        env->DeleteLocalRef(str);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_aeoncorex_streamx_ui_movie_StreamXCore_setPropertyStringNative(JNIEnv* env, jobject thiz, jstring name, jstring value) {
    if (mpv_ctx) {
        const char* prop_name = env->GetStringUTFChars(name, nullptr);
        const char* prop_value = env->GetStringUTFChars(value, nullptr);
        mpv_set_property_string(mpv_ctx, prop_name, prop_value);
        env->ReleaseStringUTFChars(name, prop_name);
        env->ReleaseStringUTFChars(value, prop_value);
    }
}

// --- Torrent Engine Logic ---
extern "C" JNIEXPORT void JNICALL
Java_com_aeoncorex_streamx_ui_movie_TorrentEngine_initNative(JNIEnv* env, jobject) {
    if (!torrentEngine) torrentEngine = new TorrentSystem();
}
extern "C" JNIEXPORT void JNICALL
Java_com_aeoncorex_streamx_ui_movie_TorrentEngine_startNative(JNIEnv* env, jobject, jstring magnet, jstring path) {
    if (!torrentEngine) torrentEngine = new TorrentSystem();
    const char* m = env->GetStringUTFChars(magnet, nullptr);
    const char* p = env->GetStringUTFChars(path, nullptr);
    torrentEngine->start(m, p);
    env->ReleaseStringUTFChars(magnet, m);
    env->ReleaseStringUTFChars(path, p);
}
extern "C" JNIEXPORT void JNICALL
Java_com_aeoncorex_streamx_ui_movie_TorrentEngine_stopNative(JNIEnv* env, jobject) {
    if (torrentEngine) { torrentEngine->stop(); delete torrentEngine; torrentEngine = nullptr; }
}
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_aeoncorex_streamx_ui_movie_TorrentEngine_getStatusNative(JNIEnv* env, jobject) {
    if (!torrentEngine) return nullptr;
    EngineStatus s = torrentEngine->getStatus();
    jlongArray result = env->NewLongArray(5);
    jlong fill[5] = { (jlong)s.progress, s.speed, (jlong)s.seeds, (jlong)s.peers, (jlong)s.state };
    env->SetLongArrayRegion(result, 0, 5, fill);
    return result;
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_aeoncorex_streamx_ui_movie_TorrentEngine_getFilePathNative(JNIEnv* env, jobject) {
    if (!torrentEngine) return env->NewStringUTF("");
    return env->NewStringUTF(torrentEngine->getFilePath().c_str());
}
