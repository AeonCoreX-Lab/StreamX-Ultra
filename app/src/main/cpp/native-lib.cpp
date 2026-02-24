#include <jni.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <string>
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
    if (!mpv_ctx) {
        mpv_ctx = mpv_create();
        if (mpv_ctx) {
            mpv_set_option_string(mpv_ctx, "vo", "gpu");
            mpv_set_option_string(mpv_ctx, "gpu-api", "vulkan"); // Hardware Vulkan Output
            mpv_set_option_string(mpv_ctx, "hwdec", "auto");
            mpv_initialize(mpv_ctx);
            __android_log_print(ANDROID_LOG_DEBUG, TAG, "MPV Engine Initialized with Vulkan");
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

extern "C" JNIEXPORT void JNICALL
Java_com_aeoncorex_streamx_ui_movie_StreamXCore_switchMpvAudio(JNIEnv* env, jobject thiz, jstring lang) {
    if (!mpv_ctx) return;
    const char* l = env->GetStringUTFChars(lang, nullptr);
    mpv_set_property_string(mpv_ctx, "alang", l);
    env->ReleaseStringUTFChars(lang, l);
}

extern "C" JNIEXPORT void JNICALL
Java_com_aeoncorex_streamx_ui_movie_StreamXCore_destroyMpv(JNIEnv* env, jobject thiz) {
    if (mpv_ctx) {
        mpv_terminate_destroy(mpv_ctx);
        mpv_ctx = nullptr;
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
