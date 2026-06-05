// app/src/main/cpp/native-lib.cpp
// ══════════════════════════════════════════════════════════════════════
//  StreamX Native — MPV JNI Bridge
//
//  v3 CHANGES:
//    REMOVED: ALL torrent JNI functions
//             (initNative, startNative, stopNative, getStatusNative,
//              getFilePathNative, clearCacheNative)
//             These are now implemented in Rust lib.rs.
//
//  KEPT: All MPV functions — unchanged.
//        The mpv_handle pointer, OpenGL render, stream callback, etc.
// ══════════════════════════════════════════════════════════════════════

#include <jni.h>
#include <android/log.h>
#include <string>
#include <stdexcept>
#include "mpv/client.h"
#include "mpv/render_gl.h"
#include "mpv/stream_cb.h"
#include "mpv_handler.hpp"

#define LOG_TAG "SX-MPV"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)

// ── MPV handle ────────────────────────────────────────────────────────
static mpv_handle*        mpv        = nullptr;
static mpv_render_context* mpvRender = nullptr;
static JavaVM*            javaVm     = nullptr;
static jobject            mpvEventCallback = nullptr;

// ── JNI_OnLoad ────────────────────────────────────────────────────────
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    javaVm = vm;
    return JNI_VERSION_1_6;
}

// ═════════════════════════════════════════════════════════════════════
//  MPV JNI — all functions unchanged from v2
// ═════════════════════════════════════════════════════════════════════

extern "C" JNIEXPORT void JNICALL
Java_com_aeoncorex_streamx_mpv_MPVLib_create(JNIEnv* env, jclass, jstring jFilesPath) {
    if (mpv) {
        mpv_terminate_destroy(mpv);
        mpv = nullptr;
    }

    mpv = mpv_create();
    if (!mpv) {
        LOGE("mpv_create failed");
        return;
    }

    const char* filesPath = env->GetStringUTFChars(jFilesPath, nullptr);

    // Core MPV options (same as before)
    mpv_set_option_string(mpv, "config",        "yes");
    mpv_set_option_string(mpv, "config-dir",    filesPath);
    mpv_set_option_string(mpv, "hwdec",         "mediacodec-copy");
    mpv_set_option_string(mpv, "hwdec-codecs",  "h264,hevc,vp8,vp9,av1");
    mpv_set_option_string(mpv, "ao",            "opensles");
    mpv_set_option_string(mpv, "tls-verify",    "no");
    mpv_set_option_string(mpv, "network-timeout", "30");
    mpv_set_option_string(mpv, "sub-auto",      "fuzzy");
    mpv_set_option_string(mpv, "cache",         "yes");
    mpv_set_option_string(mpv, "cache-secs",    "60");
    mpv_set_option_string(mpv, "demuxer-max-bytes", "50MiB");
    mpv_set_option_string(mpv, "demuxer-readahead-secs", "20");

    env->ReleaseStringUTFChars(jFilesPath, filesPath);

    if (mpv_initialize(mpv) < 0) {
        LOGE("mpv_initialize failed");
        mpv_terminate_destroy(mpv);
        mpv = nullptr;
    } else {
        LOGI("MPV initialised");
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_aeoncorex_streamx_mpv_MPVLib_destroy(JNIEnv*, jclass) {
    if (mpvRender) {
        mpv_render_context_free(mpvRender);
        mpvRender = nullptr;
    }
    if (mpv) {
        mpv_terminate_destroy(mpv);
        mpv = nullptr;
    }
    LOGI("MPV destroyed");
}

extern "C" JNIEXPORT void JNICALL
Java_com_aeoncorex_streamx_mpv_MPVLib_command(JNIEnv* env, jclass, jobjectArray jArgs) {
    if (!mpv) return;
    int count = env->GetArrayLength(jArgs);
    std::vector<const char*> args(count + 1, nullptr);
    std::vector<std::string> strs(count);
    for (int i = 0; i < count; i++) {
        auto js = (jstring)env->GetObjectArrayElement(jArgs, i);
        strs[i] = env->GetStringUTFChars(js, nullptr);
        args[i] = strs[i].c_str();
        env->DeleteLocalRef(js);
    }
    mpv_command(mpv, args.data());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_aeoncorex_streamx_mpv_MPVLib_setOptionString(JNIEnv* env, jclass,
                                                       jstring jName, jstring jValue) {
    if (!mpv) return -1;
    const char* name  = env->GetStringUTFChars(jName,  nullptr);
    const char* value = env->GetStringUTFChars(jValue, nullptr);
    int r = mpv_set_option_string(mpv, name, value);
    env->ReleaseStringUTFChars(jName,  name);
    env->ReleaseStringUTFChars(jValue, value);
    return r;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_aeoncorex_streamx_mpv_MPVLib_getPropertyString(JNIEnv* env, jclass, jstring jName) {
    if (!mpv) return env->NewStringUTF("");
    const char* name = env->GetStringUTFChars(jName, nullptr);
    char* value = mpv_get_property_string(mpv, name);
    env->ReleaseStringUTFChars(jName, name);
    if (!value) return env->NewStringUTF("");
    jstring result = env->NewStringUTF(value);
    mpv_free(value);
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_aeoncorex_streamx_mpv_MPVLib_setPropertyString(JNIEnv* env, jclass,
                                                         jstring jName, jstring jValue) {
    if (!mpv) return -1;
    const char* name  = env->GetStringUTFChars(jName,  nullptr);
    const char* value = env->GetStringUTFChars(jValue, nullptr);
    int r = mpv_set_property_string(mpv, name, value);
    env->ReleaseStringUTFChars(jName,  name);
    env->ReleaseStringUTFChars(jValue, value);
    return r;
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_aeoncorex_streamx_mpv_MPVLib_getPropertyDouble(JNIEnv* env, jclass, jstring jName) {
    if (!mpv) return 0.0;
    const char* name = env->GetStringUTFChars(jName, nullptr);
    double value = 0.0;
    mpv_get_property(mpv, name, MPV_FORMAT_DOUBLE, &value);
    env->ReleaseStringUTFChars(jName, name);
    return value;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_aeoncorex_streamx_mpv_MPVLib_getPropertyLong(JNIEnv* env, jclass, jstring jName) {
    if (!mpv) return 0L;
    const char* name = env->GetStringUTFChars(jName, nullptr);
    int64_t value = 0;
    mpv_get_property(mpv, name, MPV_FORMAT_INT64, &value);
    env->ReleaseStringUTFChars(jName, name);
    return (jlong)value;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_aeoncorex_streamx_mpv_MPVLib_getPropertyBoolean(JNIEnv* env, jclass, jstring jName) {
    if (!mpv) return JNI_FALSE;
    const char* name = env->GetStringUTFChars(jName, nullptr);
    int value = 0;
    mpv_get_property(mpv, name, MPV_FORMAT_FLAG, &value);
    env->ReleaseStringUTFChars(jName, name);
    return value ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_aeoncorex_streamx_mpv_MPVLib_observeProperty(JNIEnv* env, jclass, jstring jName, jint format) {
    if (!mpv) return;
    const char* name = env->GetStringUTFChars(jName, nullptr);
    static uint64_t reply_id = 0;
    mpv_observe_property(mpv, reply_id++, name, (mpv_format)format);
    env->ReleaseStringUTFChars(jName, name);
}

// ── Render context ────────────────────────────────────────────────────
extern "C" JNIEXPORT jlong JNICALL
Java_com_aeoncorex_streamx_mpv_MPVLib_initGL(JNIEnv*, jclass) {
    if (!mpv || mpvRender) return 0L;
    mpv_opengl_init_params glParams = {};
    glParams.get_proc_address = [](void*, const char* name) -> void* {
        return (void*)eglGetProcAddress(name);
    };
    mpv_render_param params[] = {
        {MPV_RENDER_PARAM_API_TYPE,       (void*)MPV_RENDER_API_TYPE_OPENGL},
        {MPV_RENDER_PARAM_OPENGL_INIT_PARAMS, &glParams},
        {MPV_RENDER_PARAM_INVALID, nullptr}
    };
    if (mpv_render_context_create(&mpvRender, mpv, params) < 0) return 0L;
    return (jlong)(intptr_t)mpvRender;
}

extern "C" JNIEXPORT void JNICALL
Java_com_aeoncorex_streamx_mpv_MPVLib_destroyGL(JNIEnv*, jclass) {
    if (mpvRender) { mpv_render_context_free(mpvRender); mpvRender = nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_com_aeoncorex_streamx_mpv_MPVLib_renderFrame(JNIEnv*, jclass, jint fbo, jint w, jint h) {
    if (!mpvRender) return;
    mpv_opengl_fbo ofbo  = {fbo, w, h, 0};
    int flip             = 1;
    mpv_render_param rp[] = {
        {MPV_RENDER_PARAM_OPENGL_FBO,     &ofbo},
        {MPV_RENDER_PARAM_FLIP_Y,         &flip},
        {MPV_RENDER_PARAM_INVALID, nullptr}
    };
    mpv_render_context_render(mpvRender, rp);
}

// ── Event loop ────────────────────────────────────────────────────────
extern "C" JNIEXPORT jobject JNICALL
Java_com_aeoncorex_streamx_mpv_MPVLib_waitEvent(JNIEnv* env, jclass, jdouble timeout) {
    if (!mpv) return nullptr;
    mpv_event* event = mpv_wait_event(mpv, timeout);
    if (!event || event->event_id == MPV_EVENT_NONE) return nullptr;

    jclass    cls = env->FindClass("com/aeoncorex/streamx/mpv/MPVEvent");
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(II)V");

    int error_id = 0;
    if (event->error != 0) error_id = event->error;
    return env->NewObject(cls, ctor, (jint)event->event_id, error_id);
}
