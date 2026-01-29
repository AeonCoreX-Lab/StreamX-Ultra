#include <jni.h>
#include <string>
#include "torrent_system.hpp"

// গ্লোবাল ইঞ্জিন পয়েন্টার
static TorrentSystem* engine = nullptr;

extern "C" JNIEXPORT void JNICALL
Java_com_aeoncorex_streamx_ui_movie_TorrentEngine_initNative(JNIEnv* env, jobject /* this */) {
    if (engine == nullptr) {
        engine = new TorrentSystem();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_aeoncorex_streamx_ui_movie_TorrentEngine_startNative(
        JNIEnv* env,
        jobject /* this */,
        jstring magnet,
        jstring savePath) {
    
    if (engine == nullptr) return;

    const char* magnetChars = env->GetStringUTFChars(magnet, nullptr);
    const char* pathChars = env->GetStringUTFChars(savePath, nullptr);

    engine->start(std::string(magnetChars), std::string(pathChars));

    env->ReleaseStringUTFChars(magnet, magnetChars);
    env->ReleaseStringUTFChars(savePath, pathChars);
}

extern "C" JNIEXPORT void JNICALL
Java_com_aeoncorex_streamx_ui_movie_TorrentEngine_stopNative(JNIEnv* env, jobject /* this */) {
    if (engine != nullptr) {
        engine->stop();
    }
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_aeoncorex_streamx_ui_movie_TorrentEngine_getStatusNative(JNIEnv* env, jobject /* this */) {
    if (engine == nullptr) return nullptr;

    EngineStatus status = engine->getStatus();
    
    jlongArray result = env->NewLongArray(5);
    jlong temp[5];
    
    temp[0] = (jlong)status.progress;
    temp[1] = (jlong)status.speed;
    temp[2] = (jlong)status.seeds;
    temp[3] = (jlong)status.peers;
    temp[4] = (jlong)status.state;
    
    env->SetLongArrayRegion(result, 0, 5, temp);
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_aeoncorex_streamx_ui_movie_TorrentEngine_getFilePathNative(JNIEnv* env, jobject /* this */) {
    if (engine == nullptr) return env->NewStringUTF("");
    
    EngineStatus status = engine->getStatus();
    return env->NewStringUTF(status.videoPath);
}
