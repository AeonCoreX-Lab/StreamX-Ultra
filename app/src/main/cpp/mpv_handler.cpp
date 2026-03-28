#include "mpv_handler.hpp"
#include <mpv/client.h>
#include <android/log.h>
#include <android/native_window.h>
#include <locale.h>
#include <string>
#include <mutex>
#include <sstream>
#include <inttypes.h>

#define TAG "StreamX_MPV"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ─────────────────────────────────────────────────────────────
//  Module state
// ─────────────────────────────────────────────────────────────
static mpv_handle*    mpv_ctx          = nullptr;
static ANativeWindow* s_current_window = nullptr;
static int            s_surface_w      = 0;
static int            s_surface_h      = 0;
static std::mutex     mpv_mutex;

// ─────────────────────────────────────────────────────────────
//  WHY THE EVENT THREAD WAS REMOVED:
//
//  The previous version had an event_loop thread that listened for
//  MPV_EVENT_VIDEO_RECONFIG and responded by calling:
//      mpv_set_property(mpv_ctx, "wid", ...)
//
//  THIS CAUSED AN INFINITE LOOP:
//
//  1. loadfile → MPV creates VO → VIDEO_RECONFIG fires
//  2. event_loop catches VIDEO_RECONFIG → re-sets wid
//  3. Re-setting wid → MPV recreates VO → VIDEO_RECONFIG fires again
//  4. event_loop catches VIDEO_RECONFIG → re-sets wid
//  5. ... infinite loop ...
//
//  During this loop the VO was constantly being torn down and rebuilt,
//  so NO frame was EVER rendered → perpetual black screen.
//  Audio continued because it has its own independent decoder thread.
//
//  FIX: Remove the event thread entirely.
//  Set wid ONCE when the surface is created (before loadfile).
//  After loadfile, only re-send android-surface-size (safe — does NOT
//  trigger a full VO teardown/recreate cycle).
//  The Kotlin side handles the one-time surface setup correctly.
// ─────────────────────────────────────────────────────────────

void init_mpv_engine() {
    std::lock_guard<std::mutex> lk(mpv_mutex);
    setlocale(LC_NUMERIC, "C");
    if (mpv_ctx) return;

    mpv_ctx = mpv_create();
    if (!mpv_ctx) { LOGE("mpv_create() failed"); return; }

    // ── Video output ──────────────────────────────────────────
    // vo=gpu: the older but Android-proven VO that works reliably
    // with wid=ANativeWindow*.  vo=gpu-next (libplacebo) had
    // different VIDEO_RECONFIG behaviour that triggered the loop.
    mpv_set_option_string(mpv_ctx, "vo",        "gpu");
    mpv_set_option_string(mpv_ctx, "gpu-api",   "opengl");
    mpv_set_option_string(mpv_ctx, "opengl-es", "yes");

    // hwdec=no: pure FFmpeg software decode.
    // On Redmi/MIUI, mediacodec-copy returns silent black frames
    // when the bitstream has any gap (sparse torrent file regions).
    // Software decode always produces visible frames.
    mpv_set_option_string(mpv_ctx, "hwdec",     "no");

    // ── Audio ─────────────────────────────────────────────────
    mpv_set_option_string(mpv_ctx, "ao", "audiotrack,opensles");

    // ── Streaming / torrent cache ─────────────────────────────
    mpv_set_option_string(mpv_ctx, "cache",                  "yes");
    mpv_set_option_string(mpv_ctx, "cache-pause",            "yes");
    mpv_set_option_string(mpv_ctx, "cache-pause-initial",    "no");
    mpv_set_option_string(mpv_ctx, "cache-pause-wait",       "10");
    mpv_set_option_string(mpv_ctx, "demuxer-max-bytes",      "256MiB");
    mpv_set_option_string(mpv_ctx, "demuxer-readahead-secs", "20");
    mpv_set_option_string(mpv_ctx, "demuxer-max-back-bytes", "256MiB");
    mpv_set_option_string(mpv_ctx, "stream-buffer-size",     "8MiB");

    // ── Subtitles ─────────────────────────────────────────────
    mpv_set_option_string(mpv_ctx, "sub-auto",         "fuzzy");
    mpv_set_option_string(mpv_ctx, "sub-ass-override", "force");
    mpv_set_option_string(mpv_ctx, "sub-font-size",    "45");

    // ── Playback ──────────────────────────────────────────────
    mpv_set_option_string(mpv_ctx, "keep-open",      "yes");
    mpv_set_option_string(mpv_ctx, "idle",           "yes");
    mpv_set_option_string(mpv_ctx, "force-seekable", "yes");

    if (mpv_initialize(mpv_ctx) < 0) {
        LOGE("mpv_initialize() failed");
        mpv_destroy(mpv_ctx);
        mpv_ctx = nullptr;
        return;
    }

    // No event thread — see comment at top of file.
    LOGD("MPV initialised (vo=gpu / opengl-es / software-decode)");
}

// ─────────────────────────────────────────────────────────────
void play_mpv_video(const char* path) {
    std::lock_guard<std::mutex> lk(mpv_mutex);
    if (!mpv_ctx) { LOGE("play_mpv_video: null ctx"); return; }
    LOGD("loadfile: %s", path);
    const char* cmd[] = {"loadfile", path, nullptr};
    int r = mpv_command(mpv_ctx, cmd);
    if (r < 0) LOGE("loadfile failed: %s", mpv_error_string(r));
}

// ─────────────────────────────────────────────────────────────
//  set_mpv_surface — called ONCE when the surface is created,
//  and again with nullptr when the surface is destroyed.
//
//  Ownership of the ANativeWindow is transferred here.
//  Caller must NOT call ANativeWindow_release() after this.
// ─────────────────────────────────────────────────────────────
void set_mpv_surface(ANativeWindow* new_window) {
    std::lock_guard<std::mutex> lk(mpv_mutex);
    if (!mpv_ctx) return;   // init_mpv_engine() must be called first

    if (s_current_window) {
        ANativeWindow_release(s_current_window);
        s_current_window = nullptr;
    }
    s_current_window = new_window;  // take ownership

    int64_t wid = (int64_t)new_window;
    int r = mpv_set_property(mpv_ctx, "wid", MPV_FORMAT_INT64, &wid);
    if (r < 0) LOGE("wid set failed: %s", mpv_error_string(r));
    else LOGD("wid=%" PRId64, wid);
}

// ─────────────────────────────────────────────────────────────
//  set_mpv_surface_size — called from surfaceChanged and once
//  after loadfile to ensure the VO has the correct dimensions.
//
//  SAFE to call multiple times — does NOT trigger VO teardown/
//  recreate (unlike re-setting wid, which does).
// ─────────────────────────────────────────────────────────────
void set_mpv_surface_size(int w, int h) {
    std::lock_guard<std::mutex> lk(mpv_mutex);
    s_surface_w = w;
    s_surface_h = h;
    if (!mpv_ctx || w <= 0 || h <= 0) return;
    char buf[32];
    snprintf(buf, sizeof(buf), "%dx%d", w, h);
    mpv_set_property_string(mpv_ctx, "android-surface-size", buf);
    LOGD("android-surface-size: %s", buf);
}

// ─────────────────────────────────────────────────────────────
void toggle_vulkan_fsr(bool enable) {
    std::lock_guard<std::mutex> lk(mpv_mutex);
    if (!mpv_ctx) return;
    mpv_set_option_string(mpv_ctx, "scale",  enable ? "ewa_lanczossharp" : "bilinear");
    mpv_set_option_string(mpv_ctx, "cscale", enable ? "ewa_lanczossharp" : "bilinear");
}

double get_mpv_time() {
    std::lock_guard<std::mutex> lk(mpv_mutex);
    if (!mpv_ctx) return 0.0;
    double t = 0.0;
    mpv_get_property(mpv_ctx, "time-pos", MPV_FORMAT_DOUBLE, &t);
    return t;
}

double get_mpv_duration() {
    std::lock_guard<std::mutex> lk(mpv_mutex);
    if (!mpv_ctx) return 0.0;
    double d = 0.0;
    mpv_get_property(mpv_ctx, "duration", MPV_FORMAT_DOUBLE, &d);
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
    std::string result(raw);
    mpv_free(raw);
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
    int v = 0;
    mpv_get_property(mpv_ctx, "paused-for-cache", MPV_FORMAT_FLAG, &v);
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

        std::string key_id = "track-list/" + std::to_string(i) + "/id";
        int64_t id = 0;
        mpv_get_property(mpv_ctx, key_id.c_str(), MPV_FORMAT_INT64, &id);

        std::string key_title = "track-list/" + std::to_string(i) + "/title";
        char* title = mpv_get_property_string(mpv_ctx, key_title.c_str());
        std::string title_str = title ? title : "";
        if (title) mpv_free(title);

        if (title_str.empty()) {
            std::string key_lang = "track-list/" + std::to_string(i) + "/lang";
            char* lang = mpv_get_property_string(mpv_ctx, key_lang.c_str());
            if (lang) { title_str = lang; mpv_free(lang); }
        }
        if (title_str.empty()) title_str = "Track " + std::to_string(id);

        std::string key_sel = "track-list/" + std::to_string(i) + "/selected";
        char* sel = mpv_get_property_string(mpv_ctx, key_sel.c_str());
        bool selected = (sel && std::string(sel) == "yes");
        if (sel) mpv_free(sel);

        if (!first) out << ";";
        out << id << "|" << title_str << "|" << (selected ? "1" : "0");
        first = false;
    }
    return out.str();
}