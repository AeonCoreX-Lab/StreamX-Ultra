#include <jni.h>
#include <android/log.h>
#include <string>
#include "torrent_system.hpp"

#define TAG "StreamX_JNI"

extern "C" {
    __attribute__((weak))
    ssize_t __sendto_chk(int fd, const void* buf, size_t len, size_t buflen, int flags, const struct sockaddr* addr, socklen_t addr_len) {
        return sendto(fd, buf, len, flags, addr, addr_len);
    }
}

static TorrentSystem* torrentEngine = nullptr;

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
        delete torrentEngine;
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
