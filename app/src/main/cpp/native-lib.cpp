#include <jni.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <android/log.h>
#include "torrent_system.hpp"
#include "ai_engine.hpp" // Ensure you have created this file as per previous instructions

#define TAG "StreamX_JNI"

// --- FIX FOR LINKER ERROR: undefined symbol: __sendto_chk ---
extern "C" {
    __attribute__((weak))
    ssize_t __sendto_chk(int fd, const void* buf, size_t len, size_t buflen, int flags, const struct sockaddr* addr, socklen_t addr_len) {
        return sendto(fd, buf, len, flags, addr, addr_len);
    }
}
// -----------------------------------------------------------

// Global Instances
static TorrentSystem* torrentEngine = nullptr;
static AIEngine* aiEngine = nullptr;

// =============================================================================================
// SECTION 1: TORRENT ENGINE JNI
// =============================================================================================

extern "C" JNIEXPORT void JNICALL
Java_com_aeoncorex_streamx_ui_movie_TorrentEngine_initNative(JNIEnv* env, jobject) {
    if (!torrentEngine) torrentEngine = new TorrentSystem();
}

extern "C" JNIEXPORT void JNICALL
Java_com_aeoncorex_streamx_ui_movie_TorrentEngine_startNative(JNIEnv* env, jobject, jstring magnet, jstring path) {
    if (!torrentEngine) return;
    const char* m = env->GetStringUTFChars(magnet, nullptr);
    const char* p = env->GetStringUTFChars(path, nullptr);
    torrentEngine->start(m, p);
    env->ReleaseStringUTFChars(magnet, m);
    env->ReleaseStringUTFChars(path, p);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_aeoncorex_streamx_ui_movie_TorrentEngine_getFilePathNative(JNIEnv* env, jobject) {
    return env->NewStringUTF(torrentEngine ? torrentEngine->getFilePath().c_str() : "");
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_aeoncorex_streamx_ui_movie_TorrentEngine_getStatusNative(JNIEnv* env, jobject) {
    if (!torrentEngine) return nullptr;
    EngineStatus s = torrentEngine->getStatus();
    jlongArray res = env->NewLongArray(5);
    jlong data[5] = { (jlong)s.progress, s.speed, (jlong)s.seeds, (jlong)s.peers, (jlong)s.state };
    env->SetLongArrayRegion(res, 0, 5, data);
    return res;
}

extern "C" JNIEXPORT void JNICALL
Java_com_aeoncorex_streamx_ui_movie_TorrentEngine_stopNative(JNIEnv* env, jobject) {
    if (torrentEngine) torrentEngine->stop();
}

// =============================================================================================
[span_0](start_span)// SECTION 2: AI ENGINE JNI (Whisper)[span_0](end_span)
// =============================================================================================

extern "C" JNIEXPORT jboolean JNICALL
Java_com_aeoncorex_streamx_ui_movie_MoviePlayerScreenKt_initAINative(JNIEnv* env, jclass, jstring modelPath) {
    if (!aiEngine) aiEngine = new AIEngine();
    
    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    bool result = aiEngine->init(path);
    env->ReleaseStringUTFChars(modelPath, path);
    
    if (result) {
        __android_log_print(ANDROID_LOG_INFO, TAG, "AI Engine Initialized Successfully");
    } else {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Failed to Initialize AI Engine");
    }
    
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_aeoncorex_streamx_ui_movie_MoviePlayerScreenKt_pushAudioNative(JNIEnv* env, jclass, jfloatArray data, jint size) {
    if (!aiEngine) return;
    
    // Convert Java float array to C++ vector
    jfloat* body = env->GetFloatArrayElements(data, 0);
    std::vector<float> pcm(body, body + size);
    
    aiEngine->pushAudio(pcm);
    
    env->ReleaseFloatArrayElements(data, body, 0);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_aeoncorex_streamx_ui_movie_MoviePlayerScreenKt_getSubtitleNative(JNIEnv* env, jclass) {
    if (!aiEngine) return env->NewStringUTF("");
    return env->NewStringUTF(aiEngine->getCurrentSubtitle().c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_aeoncorex_streamx_ui_movie_MoviePlayerScreenKt_stopAINative(JNIEnv* env, jclass) {
    if (aiEngine) {
        aiEngine->stop();
        delete aiEngine;
        aiEngine = nullptr;
        __android_log_print(ANDROID_LOG_INFO, TAG, "AI Engine Stopped");
    }
}
