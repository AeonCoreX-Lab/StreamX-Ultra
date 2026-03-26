#include "mpv_handler.hpp"
#include <mpv/client.h>
#include <android/log.h>
#include <android/native_window.h>
#include <locale.h>
#include <string>
#include <inttypes.h>
#include <stdlib.h>
#include <string.h>

#define TAG "StreamX_MPV"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static mpv_handle*    mpv_ctx          = nullptr;
static ANativeWindow* s_current_window = nullptr;

// ─────────────────────────────────────────────────────────────
void init_mpv_engine() {
    setlocale(LC_NUMERIC, "C");
    if (mpv_ctx) return;

    mpv_ctx = mpv_create();
    if (!mpv_ctx) { LOGE("mpv_create() failed"); return; }

    // ── Video output ──────────────────────────────────────────
    mpv_set_option_string(mpv_ctx, "vo",        "gpu-next");
    mpv_set_option_string(mpv_ctx, "gpu-api",   "opengl");
    mpv_set_option_string(mpv_ctx, "opengl-es", "yes");

    // hwdec: mediacodec-copy but with software fallback.
    // BUG WAS: mediacodec-copy alone silently returns BLACK FRAMES
    // when the bitstream has gaps (sparse file).  With vd-lavc-fallback
    // MPV falls back to software decode on any HW decode error → correct
    // error frames instead of silent black.
    mpv_set_option_string(mpv_ctx, "hwdec",          "mediacodec-copy");
    mpv_set_option_string(mpv_ctx, "hwdec-codecs",   "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1");
    mpv_set_option_string(mpv_ctx, "vd-lavc-fallback", "yes");  // ← KEY FIX for black video

    // ── Audio output ──────────────────────────────────────────
    mpv_set_option_string(mpv_ctx, "ao", "audiotrack,opensles");

    // ── Torrent streaming cache ───────────────────────────────
    //
    // BUGS THAT WERE HERE:
    //
    // 1. cache-pause-initial=yes: Made MPV pause on open even after
    //    sufficient data was confirmed by torrent engine → extra black
    //    screen delay at start. Set to "no"; torrent engine already
    //    gates on 5% downloaded before calling loadfile.
    //
    // 2. demuxer-readahead-secs=60: Caused demuxer to aggressively
    //    read 60 seconds ahead → hit undownloaded sparse regions →
    //    video decoder got zeros → BLACK FRAMES (audio OK because
    //    audio codec is more resilient to corrupted data).
    //    Lowered to 20 seconds — enough for smooth play, safe from
    //    sparse gaps at 536 KB/s (sequential download is ahead ~5%).
    //
    // 3. demuxer-max-back-bytes=64MiB: Too small for backward seek.
    //    At 8Mbps (1080p), 64MiB ≈ 64 seconds of back-buffer.
    //    Raised to 256MiB ≈ 256 seconds — covers most backward seeks.
    //    When user seeks backward into this window, data comes from
    //    RAM (demuxer back-cache), not disk → no sparse gap issue.
    //
    // 4. cache-pause-wait=5: Too short. If download speed dips, MPV
    //    resumes with only 5 s of data and hits sparse gap again.
    //    Raised to 10 s to ensure enough sequential data before resume.

    mpv_set_option_string(mpv_ctx, "cache",                  "yes");
    mpv_set_option_string(mpv_ctx, "cache-pause",            "yes");
    mpv_set_option_string(mpv_ctx, "cache-pause-initial",    "no");   // ← FIX 1
    mpv_set_option_string(mpv_ctx, "cache-pause-wait",       "10");   // ← FIX 4
    mpv_set_option_string(mpv_ctx, "demuxer-max-bytes",      "256MiB");
    mpv_set_option_string(mpv_ctx, "demuxer-readahead-secs", "20");   // ← FIX 2
    mpv_set_option_string(mpv_ctx, "demuxer-max-back-bytes", "256MiB"); // ← FIX 3
    mpv_set_option_string(mpv_ctx, "stream-buffer-size",     "8MiB");

    // ── Subtitles ─────────────────────────────────────────────
    mpv_set_option_string(mpv_ctx, "sub-auto",         "fuzzy");
    mpv_set_option_string(mpv_ctx, "sub-ass-override", "force");
    mpv_set_option_string(mpv_ctx, "sub-font-size",    "45");

    // ── Playback ──────────────────────────────────────────────
    mpv_set_option_string(mpv_ctx, "keep-open",     "yes");
    mpv_set_option_string(mpv_ctx, "idle",          "yes");
    // force-seekable: allow seeks even in streaming mode.
    // The back-cache (256MiB) handles backward seeks safely.
    mpv_set_option_string(mpv_ctx, "force-seekable", "yes");

    if (mpv_initialize(mpv_ctx) < 0) {
        LOGE("mpv_initialize() failed");
        mpv_destroy(mpv_ctx);
        mpv_ctx = nullptr;
        return;
    }
    LOGD("MPV initialised (gpu-next / opengl-es / mediacodec-copy+fallback)");
}

// ─────────────────────────────────────────────────────────────
void play_mpv_video(const char* path) {
    if (!mpv_ctx) { LOGE("play_mpv_video: null ctx"); return; }
    LOGD("loadfile: %s", path);
    const char* cmd[] = {"loadfile", path, nullptr};
    int r = mpv_command(mpv_ctx, cmd);
    if (r < 0) LOGE("loadfile failed: %s", mpv_error_string(r));
}

// ─────────────────────────────────────────────────────────────
void set_mpv_surface(ANativeWindow* new_window) {
    if (!mpv_ctx) { init_mpv_engine(); if (!mpv_ctx) return; }
    if (s_current_window) { ANativeWindow_release(s_current_window); s_current_window = nullptr; }
    s_current_window = new_window;   // ownership transferred; do NOT release
    int64_t wid = (int64_t)new_window;
    int r = mpv_set_property(mpv_ctx, "wid", MPV_FORMAT_INT64, &wid);
    if (r < 0) LOGE("set wid failed: %s", mpv_error_string(r));
    else LOGD("wid=%" PRId64, wid);
}

void set_mpv_surface_size(int w, int h) {
    if (!mpv_ctx) return;
    char buf[32];
    snprintf(buf, sizeof(buf), "%dx%d", w, h);
    mpv_set_property_string(mpv_ctx, "android-surface-size", buf);
}

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

// ── New property getters ──────────────────────────────────────

char* get_property_string_mpv(const char* name) {
    if (!mpv_ctx) return nullptr;
    // mpv_get_property_string returns malloc'd string; caller must free()
    return mpv_get_property_string(mpv_ctx, name);
}

int64_t get_property_int_mpv(const char* name) {
    if (!mpv_ctx) return -1;
    int64_t val = -1;
    mpv_get_property(mpv_ctx, name, MPV_FORMAT_INT64, &val);
    return val;
}

int get_cache_percent_mpv() {
    if (!mpv_ctx) return 100;
    // cache-buffering-state: 0 = no data, 100 = full
    // When not buffering it returns -1; treat as 100 (full)
    int64_t val = -1;
    mpv_get_property(mpv_ctx, "cache-buffering-state", MPV_FORMAT_INT64, &val);
    if (val < 0) return 100;
    if (val > 100) return 100;
    return (int)val;
}

int is_paused_for_cache_mpv() {
    if (!mpv_ctx) return 0;
    int v = 0;
    mpv_get_property(mpv_ctx, "paused-for-cache", MPV_FORMAT_FLAG, &v);
    return v;
}
