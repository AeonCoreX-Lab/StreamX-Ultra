#include <jni.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <android/log.h>
#include <vector>
#include <string>
#include "torrent_system.hpp"
#include "ai_engine.hpp" 

#define TAG "StreamX_JNI"

// --- FIX FOR LINKER ERROR (Legacy Support) ---
extern "C" {
    __attribute__((weak))
    ssize_t __sendto_chk(int fd, const void* buf, size_t len, size_t buflen, int flags, const struct sockaddr* addr, socklen_t addr_len) {
        return sendto(fd, buf, len, flags, addr, addr_len);
    }
}

// গ্লোবাল ইনস্ট্যান্স (সিঙ্গেলটন)
static TorrentSystem* torrentEngine = nullptr;
static AIEngine* aiEngine = nullptr;

// =============================================================================================
// SECTION 1: TORRENT ENGINE JNI (Mapped to TorrentEngine.kt)
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

extern "C" JNIEXPORT void JNICALL
Java_com_aeoncorex_streamx_ui_movie_TorrentEngine_stopNative(JNIEnv* env, jobject) {
    if (torrentEngine) torrentEngine->stop();
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

// =============================================================================================
// SECTION 2: AI ENGINE JNI (Mapped to StreamXCore object in MoviePlayerScreen.kt)
// =============================================================================================

extern "C" JNIEXPORT jboolean JNICALL
Java_com_aeoncorex_streamx_ui_movie_StreamXCore_initAI(JNIEnv* env, jobject, jstring modelPath) {
    if (!aiEngine) aiEngine = new AIEngine();
    
    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    bool result = aiEngine->init(path);
    env->ReleaseStringUTFChars(modelPath, path);
    
    return (jboolean)result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_aeoncorex_streamx_ui_movie_StreamXCore_pushAudio(JNIEnv* env, jobject, jfloatArray data) {
    if (!aiEngine) return;
    
    jsize len = env->GetArrayLength(data);
    jfloat* body = env->GetFloatArrayElements(data, 0);
    
    // কনভার্ট টু C++ ভেক্টর
    std::vector<float> pcm(body, body + len);
    aiEngine->pushAudio(pcm);
    
    env->ReleaseFloatArrayElements(data, body, JNI_ABORT); // JNI_ABORT স্পিড বাড়ায় কারণ ডাটা কপি ব্যাক করে না
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_aeoncorex_streamx_ui_movie_StreamXCore_getSubtitle(JNIEnv* env, jobject) {
    if (!aiEngine) return env->NewStringUTF("");
    return env->NewStringUTF(aiEngine->getCurrentSubtitle().c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_aeoncorex_streamx_ui_movie_StreamXCore_stopAI(JNIEnv* env, jobject) {
    if (aiEngine) {
        aiEngine->stop();
        // মেমোরি ক্লিনআপ (অপশনাল, কিন্তু রিসোর্স বাঁচাতে ভালো)
        delete aiEngine;
        aiEngine = nullptr;
    }
}
