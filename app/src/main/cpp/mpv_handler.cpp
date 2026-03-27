#include "mpv_handler.hpp"
#include <mpv/client.h>
#include <android/log.h>
#include <android/native_window.h>
#include <locale.h>
#include <string>
#include <mutex>
#include <thread>
#include <atomic>
#include <sstream>
#include <inttypes.h>

#define TAG "StreamX_MPV"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ─────────────────────────────────────────────────────────────
//  Module-level state
// ─────────────────────────────────────────────────────────────
static mpv_handle*    mpv_ctx          = nullptr;
static ANativeWindow* s_current_window = nullptr;
static int            s_surface_w      = 0;
static int            s_surface_h      = 0;

// ── Thread safety ──────────────────────────────────────────────
// ALL mpv_* API calls must hold this mutex.
//
// CRASH ROOT CAUSE:
//   getTrackList() in Kotlin called getPropertyStringNative() in a tight
//   loop from the UI thread.  Simultaneously, the time-sync coroutine was
//   calling getMpvTime() / getMpvDuration() / isMpvPausedForCache() from
//   another thread.  Concurrent mpv_get_property_string() calls corrupted
//   MPV's internal heap → Scudo reported "corrupted chunk header / double free".
//
// FIX: single static mutex guards every mpv_* call.
static std::mutex mpv_mutex;

// ── MPV event thread ───────────────────────────────────────────
static std::thread       event_thread;
static std::atomic<bool> event_thread_running{false};

// ─────────────────────────────────────────────────────────────
//  Event thread: watches for VIDEO_RECONFIG and re-sets the
//  surface size so the VO re-attaches after loadfile.
//
//  BLACK SCREEN ROOT CAUSE:
//   After playMpvVideo() (loadfile), MPV internally tears down and
//   re-creates its video output.  During this VO reset, the
//   android-surface-size property is cleared.  MPV waits for a new
//   surface-size notification before it renders any frame.
//   If we never send it, the VO stays in limbo: timer advances,
//   audio plays, but the screen stays black forever.
//
//  FIX: the event thread catches MPV_EVENT_VIDEO_RECONFIG and
//  immediately re-sends the stored surface + size.
// ─────────────────────────────────────────────────────────────
static void event_loop() {
    while (event_thread_running.load()) {
        // mpv_wait_event with 1 second timeout
        mpv_handle* ctx = nullptr;
        {
            std::lock_guard<std::mutex> lk(mpv_mutex);
            ctx = mpv_ctx;
        }
        if (!ctx) { std::this_thread::sleep_for(std::chrono::milliseconds(200)); continue; }

        mpv_event* ev = mpv_wait_event(ctx, 0.5);
        if (!ev) continue;

        if (ev->event_id == MPV_EVENT_SHUTDOWN) break;

        if (ev->event_id == MPV_EVENT_VIDEO_RECONFIG) {
            // VO was re-created → re-attach surface + size
            std::lock_guard<std::mutex> lk(mpv_mutex);
            if (!mpv_ctx) continue;

            // Re-set wid
            int64_t wid = (int64_t)s_current_window;
            mpv_set_property(mpv_ctx, "wid", MPV_FORMAT_INT64, &wid);

            // Re-set surface size
            if (s_surface_w > 0 && s_surface_h > 0) {
                char buf[32];
                snprintf(buf, sizeof(buf), "%dx%d", s_surface_w, s_surface_h);
                mpv_set_property_string(mpv_ctx, "android-surface-size", buf);
            }
            LOGD("VIDEO_RECONFIG: re-attached wid=%" PRId64 " size=%dx%d",
                 wid, s_surface_w, s_surface_h);
        }
    }
}

// ─────────────────────────────────────────────────────────────
void init_mpv_engine() {
    std::lock_guard<std::mutex> lk(mpv_mutex);
    setlocale(LC_NUMERIC, "C");
    if (mpv_ctx) return;

    mpv_ctx = mpv_create();
    if (!mpv_ctx) { LOGE("mpv_create() failed"); return; }

    // ── Video output ──────────────────────────────────────────
    mpv_set_option_string(mpv_ctx, "vo",        "gpu-next");
    mpv_set_option_string(mpv_ctx, "gpu-api",   "opengl");
    mpv_set_option_string(mpv_ctx, "opengl-es", "yes");

    // hwdec=no: pure software decode.
    //
    // WHY NOT mediacodec-copy?
    //   On Redmi/MIUI (Redmi Note series), MediaCodec surfaces can return
    //   silent black frames when the bitstream has any gap or error.
    //   Audio is decoded in software → works fine.  Video uses hardware
    //   → black frames, no error reported.  This matches the observed
    //   symptom: timer advances, sound works, screen is black.
    //
    //   Software decode (FFmpeg/lavc) properly handles gaps and returns
    //   visible error macroblocks instead of silent black → always works.
    //   CPU usage is higher but acceptable for streaming on modern Redmi phones.
    mpv_set_option_string(mpv_ctx, "hwdec",     "no");

    // ── Audio output ──────────────────────────────────────────
    mpv_set_option_string(mpv_ctx, "ao", "audiotrack,opensles");

    // ── Streaming cache ───────────────────────────────────────
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

    // Start event thread (no mutex needed here, ctx is set)
    event_thread_running = true;
    event_thread = std::thread(event_loop);

    LOGD("MPV initialised (gpu-next / opengl-es / software-decode)");
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
//  set_mpv_surface: transfer ownership of ANativeWindow.
//  Caller must NOT call ANativeWindow_release() after this.
// ─────────────────────────────────────────────────────────────
void set_mpv_surface(ANativeWindow* new_window) {
    std::lock_guard<std::mutex> lk(mpv_mutex);

    if (!mpv_ctx) {
        // Auto-init (surfaceCreated can fire before LaunchedEffect)
        mpv_mutex.unlock();
        init_mpv_engine();
        mpv_mutex.lock();
        if (!mpv_ctx) {
            if (new_window) ANativeWindow_release(new_window);
            return;
        }
    }

    if (s_current_window) { ANativeWindow_release(s_current_window); s_current_window = nullptr; }
    s_current_window = new_window;

    int64_t wid = (int64_t)new_window;
    int r = mpv_set_property(mpv_ctx, "wid", MPV_FORMAT_INT64, &wid);
    if (r < 0) LOGE("set wid failed: %s", mpv_error_string(r));
    else LOGD("wid=%" PRId64, wid);
}

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

// ─────────────────────────────────────────────────────────────
//  get_property_string_mpv_safe
//
//  CRASH ROOT CAUSE (previous version):
//    get_property_string_mpv() returned a raw char* from
//    mpv_get_property_string().  native-lib.cpp called free() on it.
//    MPV uses its own allocator (mpv_free), NOT the system free().
//    On Android with Scudo allocator, free()-ing an mpv_malloc'd pointer
//    gives "corrupted chunk header" → immediate abort.
//
//  FIX: copy into std::string INSIDE this function (where mpv_free
//  is accessible), call mpv_free on the original, and return the copy.
//  native-lib.cpp only ever sees a std::string — no raw pointer, no free.
// ─────────────────────────────────────────────────────────────
std::string get_property_string_mpv_safe(const char* name) {
    std::lock_guard<std::mutex> lk(mpv_mutex);
    if (!mpv_ctx) return "";
    char* raw = mpv_get_property_string(mpv_ctx, name);
    if (!raw) return "";
    std::string result(raw);
    mpv_free(raw);   // ← correct free, here where mpv_free is in scope
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

// ─────────────────────────────────────────────────────────────
//  get_track_list_mpv
//
//  Returns a single string with ALL tracks of the given type.
//  Format:  "id|title|selected;id|title|selected;..."
//
//  WHY A SINGLE FUNCTION instead of individual property calls?
//    The old getTrackList() in Kotlin called getPropertyStringNative()
//    in a loop — each iteration locked JNI + called mpv_get_property_string
//    + called free().  Multiple concurrent loop calls from UI thread and
//    coroutine threads caused data races → crash.
//
//    This function does ALL reads in one C++ function under ONE mutex lock.
//    Kotlin receives a single string and parses it — zero JNI loop overhead,
//    zero race condition risk.
// ─────────────────────────────────────────────────────────────
std::string get_track_list_mpv(const char* type) {
    std::lock_guard<std::mutex> lk(mpv_mutex);
    if (!mpv_ctx) return "";

    int64_t count = 0;
    mpv_get_property(mpv_ctx, "track-list/count", MPV_FORMAT_INT64, &count);
    if (count <= 0) return "";

    std::ostringstream out;
    bool first = true;

    for (int64_t i = 0; i < count; ++i) {
        // Read track type
        std::string key_type = "track-list/" + std::to_string(i) + "/type";
        char* ttype = mpv_get_property_string(mpv_ctx, key_type.c_str());
        if (!ttype) continue;
        bool match = (std::string(ttype) == std::string(type));
        mpv_free(ttype);
        if (!match) continue;

        // Read id
        std::string key_id = "track-list/" + std::to_string(i) + "/id";
        int64_t id = 0;
        mpv_get_property(mpv_ctx, key_id.c_str(), MPV_FORMAT_INT64, &id);

        // Read title
        std::string key_title = "track-list/" + std::to_string(i) + "/title";
        char* title = mpv_get_property_string(mpv_ctx, key_title.c_str());
        std::string title_str = title ? title : "";
        if (title) mpv_free(title);

        // Read lang
        if (title_str.empty()) {
            std::string key_lang = "track-list/" + std::to_string(i) + "/lang";
            char* lang = mpv_get_property_string(mpv_ctx, key_lang.c_str());
            if (lang) { title_str = lang; mpv_free(lang); }
        }
        if (title_str.empty()) title_str = "Track " + std::to_string(id);

        // Read selected
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
