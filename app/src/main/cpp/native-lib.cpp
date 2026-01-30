#include <jni.h>
#include <sys/socket.h> // socket ফাংশন এবং টাইপের জন্য জরুরি
#include <sys/types.h>
#include "torrent_system.hpp"

// --- FIX FOR LINKER ERROR: undefined symbol: __sendto_chk ---
// Libtorrent এবং OpenSSL ফোর্টিফাইড মোডে কম্পাইল হওয়ার কারণে এই সিম্বলটি খুঁজছে।
// আমরা এটিকে ম্যানুয়ালি সাধারণ sendto() ফাংশনে রিডাইরেক্ট করে দিচ্ছি।
extern "C" {
    __attribute__((weak))
    ssize_t __sendto_chk(int fd, const void* buf, size_t len, size_t buflen, int flags, const struct sockaddr* addr, socklen_t addr_len) {
        // buflen (বাফার লেন্থ চেক) ইগনোর করে সরাসরি sendto কল করা হচ্ছে
        return sendto(fd, buf, len, flags, addr, addr_len);
    }
}
// -----------------------------------------------------------

static TorrentSystem* engine = nullptr;

extern "C" JNIEXPORT void JNICALL
Java_com_aeoncorex_streamx_ui_movie_TorrentEngine_initNative(JNIEnv* env, jobject) {
    if (!engine) engine = new TorrentSystem();
}

extern "C" JNIEXPORT void JNICALL
Java_com_aeoncorex_streamx_ui_movie_TorrentEngine_startNative(JNIEnv* env, jobject, jstring magnet, jstring path) {
    if (!engine) return; // সেফটি চেক
    const char* m = env->GetStringUTFChars(magnet, nullptr);
    const char* p = env->GetStringUTFChars(path, nullptr);
    engine->start(m, p);
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
    jlong data[5] = { (jlong)s.progress, s.speed, (jlong)s.seeds, (jlong)s.peers, (jlong)s.state };
    env->SetLongArrayRegion(res, 0, 5, data);
    return res;
}

extern "C" JNIEXPORT void JNICALL
Java_com_aeoncorex_streamx_ui_movie_TorrentEngine_stopNative(JNIEnv* env, jobject) {
    if (engine) engine->stop();
}
