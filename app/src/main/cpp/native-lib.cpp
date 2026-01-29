#include <jni.h>
#include "torrent_system.hpp"

static TorrentSystem* engine = nullptr;

extern "C" JNIEXPORT void JNICALL
Java_com_aeoncorex_streamx_ui_movie_TorrentEngine_initNative(JNIEnv* env, jobject) {
    if (!engine) engine = new TorrentSystem();
}

extern "C" JNIEXPORT void JNICALL
Java_com_aeoncorex_streamx_ui_movie_TorrentEngine_startNative(JNIEnv* env, jobject, jstring magnet, jstring path) {
    const char* m = env->GetStringUTFChars(magnet, nullptr);
    const char* p = env->GetStringUTFChars(path, nullptr);
    if (engine) engine->start(m, p);
    env->ReleaseStringUTFChars(magnet, m);
    env->ReleaseStringUTFChars(path, p);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_aeoncorex_streamx_ui_movie_TorrentEngine_getFilePathNative(JNIEnv* env, jobject) {
    return env->NewStringUTF(engine ? engine->getFilePath().c_str() : "");
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_aeoncorex_streamx_ui_movie_TorrentEngine_getStatusNative(JNIEnv* env, jobject) {
    if (!engine) return nullptr;
    EngineStatus s = engine->getStatus();
    jlongArray res = env->NewLongArray(5);
    jlong data[5] = {s.progress, s.speed, (jlong)s.seeds, (jlong)s.peers, (jlong)s.state};
    env->SetLongArrayRegion(res, 0, 5, data);
    return res;
}

extern "C" JNIEXPORT void JNICALL
Java_com_aeoncorex_streamx_ui_movie_TorrentEngine_stopNative(JNIEnv* env, jobject) {
    if (engine) engine->stop();
}
