#include "mpv_handler.hpp"
#include <mpv/client.h>
#include <android/log.h>
#include <locale.h>
#include <string>
#include <mutex>
#include <sstream>
#include <inttypes.h>

extern "C" {
    #include <libavcodec/jni.h>
}

#define TAG "StreamX_MPV"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static mpv_handle* mpv_ctx     = nullptr;
static int         s_surface_w = 0;
static int         s_surface_h = 0;
static std::mutex  mpv_mutex;

// ═══════════════════════════════════════════════════════════════
//  ROOT CAUSE OF ALL BLACK SCREEN ISSUES — CONFIRMED FROM SOURCE
//
//  From mpv-android/render.cpp (official repo):
//
//      surface = env->NewGlobalRef(surface_);          // Java Surface → GlobalRef
//      int64_t wid = reinterpret_cast<intptr_t>(surface); // GlobalRef cast to int64
//      mpv_set_option(g_mpv, "wid", MPV_FORMAT_INT64, &wid);
//
//  wid must be a Java Surface jobject (as a GlobalRef) cast to int64_t.
//  MPV's Android GPU VO uses av_jni_set_java_vm() to get the JVM,
//  then internally calls ANativeWindow_fromSurface(env, jobject).
//
//  ALL our previous code was passing ANativeWindow* as wid.
//  MPV treated that as a jobject, called JNI methods on a C pointer
//  → garbage → no video output → permanent black screen.
//  Audio was unaffected because it uses a completely separate path.
//
//  FIX:
//  1. av_jni_set_java_vm() during init (mpv-android/main.cpp pattern)
//  2. wid = (int64_t)env->NewGlobalRef(javaSurface)
//  3. No ANativeWindow_fromSurface anywhere in our code
// ═══════════════════════════════════════════════════════════════

void init_mpv_engine(JNIEnv* env, jobject appctx) {
    std::lock_guard<std::mutex> lk(mpv_mutex);
    setlocale(LC_NUMERIC, "C");
    if (mpv_ctx) return;

    // ── Give MPV's FFmpeg layer the JavaVM ────────────────────
    // Required so MPV can call ANativeWindow_fromSurface internally.
    JavaVM* vm = nullptr;
    if (env->GetJavaVM(&vm) == 0 && vm) {
        av_jni_set_java_vm(vm, nullptr);
        if (appctx) {
            jobject global_appctx = env->NewGlobalRef(appctx);
            if (global_appctx) av_jni_set_android_app_ctx(global_appctx, nullptr);
        }
    }

    mpv_ctx = mpv_create();
    if (!mpv_ctx) { LOGE("mpv_create() failed"); return; }

    mpv_set_option_string(mpv_ctx, "vo",        "gpu");
    mpv_set_option_string(mpv_ctx, "gpu-api",   "opengl");
    mpv_set_option_string(mpv_ctx, "opengl-es", "yes");
    mpv_set_option_string(mpv_ctx, "hwdec",     "no");
    mpv_set_option_string(mpv_ctx, "ao",        "audiotrack,opensles");

    mpv_set_option_string(mpv_ctx, "cache",                  "yes");
    mpv_set_option_string(mpv_ctx, "cache-pause",            "yes");
    mpv_set_option_string(mpv_ctx, "cache-pause-initial",    "no");
    mpv_set_option_string(mpv_ctx, "cache-pause-wait",       "10");
    mpv_set_option_string(mpv_ctx, "demuxer-max-bytes",      "256MiB");
    mpv_set_option_string(mpv_ctx, "demuxer-readahead-secs", "20");
    mpv_set_option_string(mpv_ctx, "demuxer-max-back-bytes", "256MiB");
    mpv_set_option_string(mpv_ctx, "stream-buffer-size",     "8MiB");

    mpv_set_option_string(mpv_ctx, "sub-auto",         "fuzzy");
    mpv_set_option_string(mpv_ctx, "sub-ass-override", "force");
    mpv_set_option_string(mpv_ctx, "sub-font-size",    "45");

    mpv_set_option_string(mpv_ctx, "keep-open",      "yes");
    mpv_set_option_string(mpv_ctx, "idle",           "yes");
    mpv_set_option_string(mpv_ctx, "force-seekable", "yes");

    if (mpv_initialize(mpv_ctx) < 0) {
        LOGE("mpv_initialize() failed");
        mpv_destroy(mpv_ctx); mpv_ctx = nullptr; return;
    }
    LOGD("MPV initialised — vo=gpu/opengl-es/hwdec=no, JavaVM registered");
}

void set_mpv_wid(int64_t wid) {
    std::lock_guard<std::mutex> lk(mpv_mutex);
    if (!mpv_ctx) return;
    // mpv_set_option works for wid even after mpv_initialize (mpv-android pattern)
    int r = mpv_set_option(mpv_ctx, "wid", MPV_FORMAT_INT64, &wid);
    if (r < 0) LOGE("set wid failed: %s", mpv_error_string(r));
    else LOGD("wid=%" PRId64, wid);
}

void set_mpv_surface_size(int w, int h) {
    std::lock_guard<std::mutex> lk(mpv_mutex);
    s_surface_w = w; s_surface_h = h;
    if (!mpv_ctx || w <= 0 || h <= 0) return;
    char buf[32];
    snprintf(buf, sizeof(buf), "%dx%d", w, h);
    mpv_set_property_string(mpv_ctx, "android-surface-size", buf);
    LOGD("android-surface-size: %s", buf);
}

void play_mpv_video(const char* path) {
    std::lock_guard<std::mutex> lk(mpv_mutex);
    if (!mpv_ctx) { LOGE("play_mpv_video: null ctx"); return; }
    const char* cmd[] = {"loadfile", path, nullptr};
    int r = mpv_command(mpv_ctx, cmd);
    if (r < 0) LOGE("loadfile failed: %s", mpv_error_string(r));
    else LOGD("loadfile: %s", path);
}

void toggle_vulkan_fsr(bool enable) {
    std::lock_guard<std::mutex> lk(mpv_mutex);
    if (!mpv_ctx) return;
    mpv_set_option_string(mpv_ctx, "scale",  enable ? "ewa_lanczossharp" : "bilinear");
    mpv_set_option_string(mpv_ctx, "cscale", enable ? "ewa_lanczossharp" : "bilinear");
}

double get_mpv_time() {
    std::lock_guard<std::mutex> lk(mpv_mutex);
    if (!mpv_ctx) return 0.0;
    double t = 0.0; mpv_get_property(mpv_ctx, "time-pos", MPV_FORMAT_DOUBLE, &t);
    return t;
}

double get_mpv_duration() {
    std::lock_guard<std::mutex> lk(mpv_mutex);
    if (!mpv_ctx) return 0.0;
    double d = 0.0; mpv_get_property(mpv_ctx, "duration", MPV_FORMAT_DOUBLE, &d);
    return d;
}

void seek_mpv_video(double seconds) {
    std::lock_guard<std::mutex> lk(mpv_mutex);
    if (!mpv_ctx) return;
    std::string s = std::to_string(seconds);
    const char* cmd[] = {"seek", s.c_str(), "relative", nullptr};
    mpv_command(mpv_ctx, cmd);
}

void pause_mpv_video(bool pause) {
    std::lock_guard<std::mutex> lk(mpv_mutex);
    if (!mpv_ctx) return;
    int v = pause ? 1 : 0;
    mpv_set_property(mpv_ctx, "pause", MPV_FORMAT_FLAG, &v);
}

void command_mpv(const char** args) {
    std::lock_guard<std::mutex> lk(mpv_mutex);
    if (!mpv_ctx) return;
    mpv_command(mpv_ctx, args);
}

void set_property_string_mpv(const char* name, const char* value) {
    std::lock_guard<std::mutex> lk(mpv_mutex);
    if (!mpv_ctx) return;
    mpv_set_property_string(mpv_ctx, name, value);
}

std::string get_property_string_mpv_safe(const char* name) {
    std::lock_guard<std::mutex> lk(mpv_mutex);
    if (!mpv_ctx) return "";
    char* raw = mpv_get_property_string(mpv_ctx, name);
    if (!raw) return "";
    std::string result(raw); mpv_free(raw);
    return result;
}

int64_t get_property_int_mpv(const char* name) {
    std::lock_guard<std::mutex> lk(mpv_mutex);
    if (!mpv_ctx) return -1;
    int64_t val = -1;
    mpv_get_property(mpv_ctx, name, MPV_FORMAT_INT64, &val);
    return val;
}

int get_cache_percent_mpv() {
    std::lock_guard<std::mutex> lk(mpv_mutex);
    if (!mpv_ctx) return 100;
    int64_t val = -1;
    mpv_get_property(mpv_ctx, "cache-buffering-state", MPV_FORMAT_INT64, &val);
    if (val < 0) return 100;
    return (val > 100) ? 100 : (int)val;
}

int is_paused_for_cache_mpv() {
    std::lock_guard<std::mutex> lk(mpv_mutex);
    if (!mpv_ctx) return 0;
    int v = 0; mpv_get_property(mpv_ctx, "paused-for-cache", MPV_FORMAT_FLAG, &v);
    return v;
}

std::string get_track_list_mpv(const char* type) {
    std::lock_guard<std::mutex> lk(mpv_mutex);
    if (!mpv_ctx) return "";
    int64_t count = 0;
    mpv_get_property(mpv_ctx, "track-list/count", MPV_FORMAT_INT64, &count);
    if (count <= 0) return "";

    std::ostringstream out;
    bool first = true;
    for (int64_t i = 0; i < count; ++i) {
        std::string key_type = "track-list/" + std::to_string(i) + "/type";
        char* ttype = mpv_get_property_string(mpv_ctx, key_type.c_str());
        if (!ttype) continue;
        bool match = (std::string(ttype) == std::string(type));
        mpv_free(ttype);
        if (!match) continue;

        int64_t id = 0;
        mpv_get_property(mpv_ctx, ("track-list/" + std::to_string(i) + "/id").c_str(), MPV_FORMAT_INT64, &id);

        char* title = mpv_get_property_string(mpv_ctx, ("track-list/" + std::to_string(i) + "/title").c_str());
        std::string title_str = title ? title : "";
        if (title) mpv_free(title);

        if (title_str.empty()) {
            char* lang = mpv_get_property_string(mpv_ctx, ("track-list/" + std::to_string(i) + "/lang").c_str());
            if (lang) { title_str = lang; mpv_free(lang); }
        }
        if (title_str.empty()) title_str = "Track " + std::to_string(id);

        char* sel = mpv_get_property_string(mpv_ctx, ("track-list/" + std::to_string(i) + "/selected").c_str());
        bool selected = (sel && std::string(sel) == "yes");
        if (sel) mpv_free(sel);

        if (!first) out << ";";
        out << id << "|" << title_str << "|" << (selected ? "1" : "0");
        first = false;
    }
    return out.str();
}
