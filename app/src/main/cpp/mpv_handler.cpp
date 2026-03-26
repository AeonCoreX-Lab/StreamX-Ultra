#include "mpv_handler.hpp"
#include <mpv/client.h>
#include <android/log.h>
#include <android/native_window.h>
#include <locale.h>
#include <string>

#define TAG "StreamX_MPV"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR,  TAG, __VA_ARGS__)

static mpv_handle*   mpv_ctx         = nullptr;

// ─────────────────────────────────────────────────────────────
//  BUG THAT WAS HERE (native-lib.cpp called ANativeWindow_release
//  immediately after passing the window to set_mpv_surface).
//
//  ANativeWindow_fromSurface() returns a window with refcount = 1.
//  MPV stores the raw pointer internally but does NOT call
//  ANativeWindow_acquire().  If we release right away, the refcount
//  drops to 0, the window is freed, and MPV later writes to freed
//  memory → black screen / crash.
//
//  FIX: keep one reference alive in s_current_window until the
//  surface changes or the engine shuts down.
// ─────────────────────────────────────────────────────────────
static ANativeWindow* s_current_window = nullptr;

// ─────────────────────────────────────────────────────────────
void init_mpv_engine() {
    setlocale(LC_NUMERIC, "C");
    if (mpv_ctx) return;   // already initialised

    mpv_ctx = mpv_create();
    if (!mpv_ctx) {
        LOGE("mpv_create() failed");
        return;
    }

    // Video output — use gpu-next (the renderer used by mpv-android 2026-03-22;
    // the older "gpu" VO is being phased out upstream).
    mpv_set_option_string(mpv_ctx, "vo",      "gpu-next");
    mpv_set_option_string(mpv_ctx, "gpu-api", "opengl");
    mpv_set_option_string(mpv_ctx, "hwdec",   "mediacodec-copy");

    // ── Streaming / torrent cache ──────────────────────────────
    // Allow MPV to start playing as soon as it has the first few
    // seconds in its demuxer buffer (do not wait for a large chunk).
    mpv_set_option_string(mpv_ctx, "cache",                  "yes");
    mpv_set_option_string(mpv_ctx, "cache-pause",            "yes");
    mpv_set_option_string(mpv_ctx, "cache-pause-initial",    "yes");
    mpv_set_option_string(mpv_ctx, "cache-pause-wait",       "3");    // resume after 3 s of cached data
    mpv_set_option_string(mpv_ctx, "demuxer-max-bytes",      "128MiB");
    mpv_set_option_string(mpv_ctx, "demuxer-readahead-secs", "30");
    mpv_set_option_string(mpv_ctx, "demuxer-max-back-bytes", "64MiB");

    // Network / file reading options — helps with partially-downloaded files
    mpv_set_option_string(mpv_ctx, "stream-buffer-size",     "4MiB");

    // ── Subtitles ─────────────────────────────────────────────
    mpv_set_option_string(mpv_ctx, "sub-auto",         "fuzzy");
    mpv_set_option_string(mpv_ctx, "sub-ass-override", "force");
    mpv_set_option_string(mpv_ctx, "sub-font-size",    "45");

    // ── Playback behaviour ────────────────────────────────────
    mpv_set_option_string(mpv_ctx, "keep-open", "yes");

    if (mpv_initialize(mpv_ctx) < 0) {
        LOGE("mpv_initialize() failed");
        mpv_destroy(mpv_ctx);
        mpv_ctx = nullptr;
        return;
    }

    LOGD("MPV Engine initialised (gpu-next / opengl / mediacodec-copy)");
}

// ─────────────────────────────────────────────────────────────
void play_mpv_video(const char* path) {
    if (!mpv_ctx) {
        LOGE("play_mpv_video called but mpv_ctx is null");
        return;
    }
    LOGD("Loading: %s", path);
    const char* cmd[] = {"loadfile", path, nullptr};
    mpv_command(mpv_ctx, cmd);
}

// ─────────────────────────────────────────────────────────────
//  set_mpv_surface — safe ANativeWindow lifecycle management.
//
//  Caller (native-lib.cpp JNI bridge) must NOT call
//  ANativeWindow_release() after calling this function.
//  Ownership is transferred here; we release the previous window
//  and store the new one.
// ─────────────────────────────────────────────────────────────
void set_mpv_surface(ANativeWindow* new_window) {
    // ── Auto-init if MPV was not ready when surfaceCreated fired ──
    // (race condition: surfaceCreated can fire before LaunchedEffect
    //  calls initMpvEngine() on the Kotlin side)
    if (!mpv_ctx) {
        LOGD("set_mpv_surface: mpv_ctx null — auto-initialising");
        init_mpv_engine();
        if (!mpv_ctx) return;
    }

    // ── Release previous window ────────────────────────────────
    if (s_current_window != nullptr) {
        ANativeWindow_release(s_current_window);
        s_current_window = nullptr;
    }

    // ── Store new window (we own the reference; do NOT release) ─
    s_current_window = new_window;

    int64_t wid = (int64_t)(new_window);   // null → 0 detaches surface
    mpv_set_property(mpv_ctx, "wid", MPV_FORMAT_INT64, &wid);

    LOGD("set_mpv_surface: wid=%" PRId64, wid);
}

// ─────────────────────────────────────────────────────────────
void toggle_vulkan_fsr(bool enable) {
    if (!mpv_ctx) return;
    if (enable) {
        mpv_set_option_string(mpv_ctx, "scale",  "ewa_lanczossharp");
        mpv_set_option_string(mpv_ctx, "cscale", "ewa_lanczossharp");
    } else {
        mpv_set_option_string(mpv_ctx, "scale",  "bilinear");
        mpv_set_option_string(mpv_ctx, "cscale", "bilinear");
    }
}

double get_mpv_time() {
    if (!mpv_ctx) return 0.0;
    double t = 0.0;
    mpv_get_property(mpv_ctx, "time-pos", MPV_FORMAT_DOUBLE, &t);
    return t;
}

double get_mpv_duration() {
    if (!mpv_ctx) return 0.0;
    double d = 0.0;
    mpv_get_property(mpv_ctx, "duration", MPV_FORMAT_DOUBLE, &d);
    return d;
}

void seek_mpv_video(double seconds) {
    if (!mpv_ctx) return;
    std::string s = std::to_string(seconds);
    const char* cmd[] = {"seek", s.c_str(), "relative", nullptr};
    mpv_command(mpv_ctx, cmd);
}

void pause_mpv_video(bool pause) {
    if (!mpv_ctx) return;
    int v = pause ? 1 : 0;
    mpv_set_property(mpv_ctx, "pause", MPV_FORMAT_FLAG, &v);
}

void command_mpv(const char** args) {
    if (!mpv_ctx) return;
    mpv_command(mpv_ctx, args);
}

void set_property_string_mpv(const char* name, const char* value) {
    if (!mpv_ctx) return;
    mpv_set_property_string(mpv_ctx, name, value);
}