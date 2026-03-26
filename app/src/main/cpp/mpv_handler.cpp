#include "mpv_handler.hpp"
#include <mpv/client.h>
#include <android/log.h>
#include <android/native_window.h>
#include <locale.h>
#include <string>
#include <inttypes.h>

#define TAG "StreamX_MPV"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static mpv_handle*    mpv_ctx         = nullptr;
static ANativeWindow* s_current_window = nullptr;

// ─────────────────────────────────────────────────────────────
void init_mpv_engine() {
    setlocale(LC_NUMERIC, "C");
    if (mpv_ctx) return;

    mpv_ctx = mpv_create();
    if (!mpv_ctx) { LOGE("mpv_create() failed"); return; }

    // ── Video output ──────────────────────────────────────────
    // gpu-next = current VO for mpv-android 2026-03-22
    // opengl-es yes = REQUIRED on Android (no desktop GL)
    // hwdec mediacodec-copy = hardware decode + software surface copy
    mpv_set_option_string(mpv_ctx, "vo",          "gpu-next");
    mpv_set_option_string(mpv_ctx, "gpu-api",     "opengl");
    mpv_set_option_string(mpv_ctx, "opengl-es",   "yes");
    mpv_set_option_string(mpv_ctx, "hwdec",       "mediacodec-copy");
    mpv_set_option_string(mpv_ctx, "hwdec-codecs","h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1");

    // ── Audio output ─────────────────────────────────────────
    // audiotrack = modern Android audio API, opensles = fallback
    mpv_set_option_string(mpv_ctx, "ao", "audiotrack,opensles");

    // ── Torrent streaming cache ───────────────────────────────
    // cache-pause-wait=5: resume after 5 s of buffered data
    // demuxer-max-bytes=128MiB: keep up to 128 MB ahead in demuxer
    mpv_set_option_string(mpv_ctx, "cache",                  "yes");
    mpv_set_option_string(mpv_ctx, "cache-pause",            "yes");
    mpv_set_option_string(mpv_ctx, "cache-pause-initial",    "yes");
    mpv_set_option_string(mpv_ctx, "cache-pause-wait",       "5");
    mpv_set_option_string(mpv_ctx, "demuxer-max-bytes",      "128MiB");
    mpv_set_option_string(mpv_ctx, "demuxer-readahead-secs", "60");
    mpv_set_option_string(mpv_ctx, "demuxer-max-back-bytes", "64MiB");
    mpv_set_option_string(mpv_ctx, "stream-buffer-size",     "4MiB");

    // ── Subtitles ─────────────────────────────────────────────
    mpv_set_option_string(mpv_ctx, "sub-auto",         "fuzzy");
    mpv_set_option_string(mpv_ctx, "sub-ass-override", "force");
    mpv_set_option_string(mpv_ctx, "sub-font-size",    "45");

    // ── Playback ─────────────────────────────────────────────
    // keep-open=yes: don't quit when playback ends
    // idle=yes:      accept loadfile commands even when idle
    mpv_set_option_string(mpv_ctx, "keep-open", "yes");
    mpv_set_option_string(mpv_ctx, "idle",      "yes");

    if (mpv_initialize(mpv_ctx) < 0) {
        LOGE("mpv_initialize() failed");
        mpv_destroy(mpv_ctx);
        mpv_ctx = nullptr;
        return;
    }
    LOGD("MPV initialised (gpu-next/opengl-es/mediacodec-copy/audiotrack)");
}

// ─────────────────────────────────────────────────────────────
void play_mpv_video(const char* path) {
    if (!mpv_ctx) { LOGE("play_mpv_video: mpv_ctx null"); return; }
    LOGD("loadfile: %s", path);
    const char* cmd[] = {"loadfile", path, nullptr};
    int r = mpv_command(mpv_ctx, cmd);
    if (r < 0) LOGE("loadfile failed: %s", mpv_error_string(r));
}

// ─────────────────────────────────────────────────────────────
//  set_mpv_surface — ANativeWindow ownership is transferred here.
//  Caller must NOT call ANativeWindow_release() after this call.
// ─────────────────────────────────────────────────────────────
void set_mpv_surface(ANativeWindow* new_window) {
    if (!mpv_ctx) { init_mpv_engine(); if (!mpv_ctx) return; }

    // Release previous window reference
    if (s_current_window) {
        ANativeWindow_release(s_current_window);
        s_current_window = nullptr;
    }
    s_current_window = new_window;   // take ownership (do NOT release)

    int64_t wid = (int64_t)new_window;
    int r = mpv_set_property(mpv_ctx, "wid", MPV_FORMAT_INT64, &wid);
    if (r < 0) LOGE("set wid failed: %s", mpv_error_string(r));
    else LOGD("wid set: %" PRId64, wid);
}

// ─────────────────────────────────────────────────────────────
void set_mpv_surface_size(int width, int height) {
    if (!mpv_ctx) return;
    char buf[32];
    snprintf(buf, sizeof(buf), "%dx%d", width, height);
    mpv_set_property_string(mpv_ctx, "android-surface-size", buf);
    LOGD("android-surface-size: %s", buf);
}

// ─────────────────────────────────────────────────────────────
void toggle_vulkan_fsr(bool enable) {
    if (!mpv_ctx) return;
    mpv_set_option_string(mpv_ctx, "scale",  enable ? "ewa_lanczossharp" : "bilinear");
    mpv_set_option_string(mpv_ctx, "cscale", enable ? "ewa_lanczossharp" : "bilinear");
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
