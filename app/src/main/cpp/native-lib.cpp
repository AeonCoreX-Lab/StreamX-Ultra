#include <jni.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <android/log.h>
#include <vector>
#include <string>
#include "torrent_system.hpp"
#include "ai_engine.hpp" 

#define TAG "StreamX_JNI"

extern "C" {
    __attribute__((weak))
    ssize_t __sendto_chk(int fd, const void* buf, size_t len, size_t buflen, int flags, const struct sockaddr* addr, socklen_t addr_len) {
        return sendto(fd, buf, len, flags, addr, addr_len);
    }
}

static TorrentSystem* torrentEngine = nullptr;
static AIEngine* aiEngine = nullptr;

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
    if (torrentEngine) {
        torrentEngine->stop();
        delete torrentEngine; // FIX: Completely destroy old engine instance
        torrentEngine = nullptr;
    }
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

extern "C" {
    bool initAINative_CPP(const char* path) {
        if (!aiEngine) aiEngine = new AIEngine();
        return aiEngine->init(path);
    }

    void pushAudioNative_CPP(const float* data, int size) {
        if (!aiEngine) return;
        std::vector<float> pcm(data, data + size);
        aiEngine->pushAudio(pcm);
    }

    const char* getSubtitleNative_CPP() {
        if (!aiEngine) return "";
        static std::string lastSub; 
        lastSub = aiEngine->getCurrentSubtitle();
        return lastSub.c_str();
    }

    void stopAINative_CPP() {
        if (aiEngine) {
            aiEngine->stop();
            delete aiEngine; // FIX: Completely destroy AI engine safely
            aiEngine = nullptr;
        }
    }
}
