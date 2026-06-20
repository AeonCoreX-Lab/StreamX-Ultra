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

// ════════════════════════════════════════════════════════════════════
//  BLACK SCREEN POSTMORTEM — COMPLETE ROOT CAUSE ANALYSIS
//  ─────────────────────────────────────────────────────────────────
//
//  SYMPTOM A (Original):
//    YTS movies → video + audio ✓
//    EZTV / 1337x movies → audio only, black screen ✗
//
//  SYMPTOM B (After changing hwdec to auto-safe):
//    YTS movies → audio only, black screen ✗  ← regression
//    EZTV / 1337x movies → still black screen ✗
//
//  ── ROOT CAUSE OF SYMPTOM B (auto-safe broke YTS) ───────────────
//
//  Stack: vo=gpu + gpu-api=opengl + opengl-es=yes
//
//  On Android, exactly ONE hardware decoder mode is compatible with
//  vo=gpu: "mediacodec-copy".
//
//  "copy" = MediaCodec decodes frame → copies pixel data from
//  the codec output buffer back to CPU memory → MPV uploads it
//  to an OpenGL ES texture via glTexImage2D.
//
//  Plain "mediacodec" (non-copy) decodes into a MediaCodec Surface
//  (a separate EGL Surface owned by the codec). vo=gpu has no path
//  to read from that Surface → every frame is black.
//
//  "auto-safe" is supposed to pick only VO-compatible HW decoders.
//  In the libmpv build used by this project, auto-safe resolves to
//  plain "mediacodec" (non-copy) instead of "mediacodec-copy".
//  This makes every codec — including H.264 — produce black frames,
//  breaking YTS which previously worked with mediacodec-copy.
//
//  FIX: Specify "mediacodec-copy" explicitly. Never rely on
//  auto-safe in a custom Android libmpv build.
//
//  ── ROOT CAUSE OF SYMPTOM A (EZTV black with mediacodec-copy) ───
//
//  EZTV and 1337x frequently use x265 (HEVC) with "Main 10" profile
//  (10-bit colour depth).
//
//  When mediacodec-copy decodes 10-bit HEVC, Android's MediaCodec
//  outputs frames in P010 format (semi-planar 10-bit YUV). MPV must
//  upload P010 via GL_R16 / GL_RG16 textures in OpenGL ES.
//
//  On many Android GPUs (Mali G76, Adreno 612, etc.), GL_R16 texture
//  uploads appear to succeed (no GL error) but the GLSL shader reads
//  zero values → pure black output. Audio is unaffected because the
//  audio decoder runs independently of the GL pipeline.
//
//  This is device-specific and cannot be fixed in MPV config alone
//  without changing the libmpv GL shader code.
//
//  FIX: Remove "hevc" from hwdec-codecs.
//  When HEVC is not hardware decoded, FFmpeg software decode outputs
//  standard YUV420p (8-bit). Every GLES 2.0+ device handles YUV420p
//  correctly → video always appears.
//
//  Codec mapping after fix:
//    Codec    Typical source   Decode    Result
//    ──────   ──────────────   ──────    ──────────────────
//    H.264    YTS              HW copy   video + audio ✓
//    H.264    EZTV             HW copy   video + audio ✓
//    HEVC     EZTV / 1337x     SW        video + audio ✓ (was black)
//    VP9      any              HW copy   video + audio ✓
//    AV1      any              HW copy   video + audio ✓
// ════════════════════════════════════════════════════════════════════

void init_mpv_engine(JNIEnv* env, jobject appctx) {
    std::lock_guard<std::mutex> lk(mpv_mutex);
    setlocale(LC_NUMERIC, "C");
    if (mpv_ctx) return;

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

    // ── Video Output ──────────────────────────────────────────────
    mpv_set_option_string(mpv_ctx, "vo",        "gpu");
    mpv_set_option_string(mpv_ctx, "gpu-api",   "opengl");
    mpv_set_option_string(mpv_ctx, "opengl-es", "yes");

    // ── Hardware Decode ───────────────────────────────────────────
    //
    //  hwdec = mediacodec-copy  (NOT "auto-safe" — see postmortem above)
    //
    //  hwdec-codecs excludes "hevc":
    //    HEVC Main10 via mediacodec-copy outputs P010 (10-bit YUV).
    //    GLES GL_R16 texture handling is broken on many Android GPUs.
    //    Excluding hevc forces FFmpeg SW decode → YUV420p 8-bit →
    //    universally compatible with every GLES 2.0+ device.
    //
    mpv_set_option_string(mpv_ctx, "hwdec",        "mediacodec-copy");
    mpv_set_option_string(mpv_ctx, "hwdec-codecs", "h264,vp9,av1,vp8");

    // ── Audio ─────────────────────────────────────────────────────
    mpv_set_option_string(mpv_ctx, "ao", "audiotrack,opensles");

    // ── Video Sync & Frame Drop ───────────────────────────────────
    mpv_set_option_string(mpv_ctx, "video-sync",     "audio");
    mpv_set_option_string(mpv_ctx, "framedrop",       "vo");
    mpv_set_option_string(mpv_ctx, "vd-lavc-threads", "0");

    // ── Software fallback for unexpected HW decode failures ───────
    //
    //  After 1 consecutive HW decode failure, MPV switches to SW for
    //  that codec. Default is 3. Setting 1 means any codec outside
    //  our whitelist that somehow gets hardware-attempted will
    //  immediately fall back to software — no prolonged black frames.
    //
    mpv_set_option_string(mpv_ctx, "vd-lavc-software-fallback", "1");

    // ── Cache ─────────────────────────────────────────────────────
    //
    //  cache-pause-initial=yes + cache-pause-wait=3:
    //    MPV pauses at start until 3 seconds of data are buffered.
    //    Kotlin sees paused-for-cache=true → shows buffering overlay.
    //    After 3s MPV resumes automatically — no manual trick needed.
    //
    mpv_set_option_string(mpv_ctx, "cache",                  "yes");
    mpv_set_option_string(mpv_ctx, "cache-pause",            "yes");
    mpv_set_option_string(mpv_ctx, "cache-pause-initial",    "yes");
    mpv_set_option_string(mpv_ctx, "cache-pause-wait",       "3");
    mpv_set_option_string(mpv_ctx, "demuxer-max-bytes",      "128MiB");
    mpv_set_option_string(mpv_ctx, "demuxer-readahead-secs", "8");
    mpv_set_option_string(mpv_ctx, "demuxer-max-back-bytes", "32MiB");
    // FIX (00:00): Use MPV's in-memory cache for backward seeks instead of
    // issuing new HTTP Range requests.  Without this, seeks (including MPV's
    // internal moov-atom probe) that land within already-buffered data still
    // open a new TCP connection — wasting bandwidth and potentially blocked
    // when those bytes aren't downloaded yet, producing a 503 stall.
    mpv_set_option_string(mpv_ctx, "demuxer-seekable-cache", "yes");
    mpv_set_option_string(mpv_ctx, "stream-buffer-size",     "4MiB");

    // ── Network ───────────────────────────────────────────────────
    // Raised from 30→120 s: FileStream long-polls until pieces download.
    // MPV must not timeout during that wait or the moov/cues seek fails.
    mpv_set_option_string(mpv_ctx, "network-timeout",       "120");
    mpv_set_option_string(mpv_ctx, "network-timeout-delay", "3");

    // ── Subtitles ─────────────────────────────────────────────────
    mpv_set_option_string(mpv_ctx, "sub-auto",         "fuzzy");
    mpv_set_option_string(mpv_ctx, "sub-ass-override", "yes");
    mpv_set_option_string(mpv_ctx, "sub-font-size",    "45");

    // ── Playback ──────────────────────────────────────────────────
    mpv_set_option_string(mpv_ctx, "keep-open",      "yes");
    mpv_set_option_string(mpv_ctx, "idle",           "yes");
    mpv_set_option_string(mpv_ctx, "force-seekable", "yes");

    if (mpv_initialize(mpv_ctx) < 0) {
        LOGE("mpv_initialize() failed");
        mpv_destroy(mpv_ctx); mpv_ctx = nullptr; return;
    }
    LOGD("MPV OK — hwdec=mediacodec-copy codecs=h264,vp9,av1,vp8 hevc=SW fallback=1");
}

void set_mpv_wid(int64_t wid) {
    std::lock_guard<std::mutex> lk(mpv_mutex);
    if (!mpv_ctx) return;
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

void seek_mpv_absolute(double position) {
    std::lock_guard<std::mutex> lk(mpv_mutex);
    if (!mpv_ctx) return;
    std::string s = std::to_string(position);
    const char* cmd[] = {"seek", s.c_str(), "absolute", nullptr};
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
        char* ttype = mpv_get_property_string(mpv_ctx,
            ("track-list/" + std::to_string(i) + "/type").c_str());
        if (!ttype) continue;
        bool match = (std::string(ttype) == std::string(type));
        mpv_free(ttype);
        if (!match) continue;

        int64_t id = 0;
        mpv_get_property(mpv_ctx,
            ("track-list/" + std::to_string(i) + "/id").c_str(),
            MPV_FORMAT_INT64, &id);

        char* title = mpv_get_property_string(mpv_ctx,
            ("track-list/" + std::to_string(i) + "/title").c_str());
        std::string title_str = title ? title : "";
        if (title) mpv_free(title);

        if (title_str.empty()) {
            char* lang = mpv_get_property_string(mpv_ctx,
                ("track-list/" + std::to_string(i) + "/lang").c_str());
            if (lang) { title_str = lang; mpv_free(lang); }
        }
        if (title_str.empty()) title_str = "Track " + std::to_string(id);

        char* sel = mpv_get_property_string(mpv_ctx,
            ("track-list/" + std::to_string(i) + "/selected").c_str());
        bool selected = (sel && std::string(sel) == "yes");
        if (sel) mpv_free(sel);

        if (!first) out << ";";
        out << id << "|" << title_str << "|" << (selected ? "1" : "0");
        first = false;
    }
    return out.str();
}
