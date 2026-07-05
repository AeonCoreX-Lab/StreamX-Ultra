#include "mpv_handler.hpp"
#include <mpv/client.h>
#include <android/log.h>
#include <locale.h>
#include <string>
#include <cstring>
#include <cstdint>
#include <mutex>
#include <atomic>
#include <thread>
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

// ── Dynamic decode-compatibility state ──────────────────────────
// Reset on every play_mpv_video() call (new file).
static std::string s_current_path;             // last loaded file path, for reload-on-switch
static bool        s_decode_check_done   = true;  // true = nothing to check (idle / already resolved)
static int         s_decode_check_ticks  = 0;     // poll ticks spent waiting for video-params
static bool        s_forced_sw_this_file = false; // true once we've auto-switched this file
static std::string s_switch_reason;               // "10bit" / "oversized" / "log-detected" / "manual" / ""
static const int   DECODE_CHECK_MAX_TICKS = 16;   // ~4s at 250ms polling — give up after this

// Extreme-resolution safety net. Most Android SoCs (even mid-range,
// Snapdragon 6xx+) HW-decode up to 4K (3840x2160) reliably; several
// flagship chips handle 8K. We only intervene at 7680px — well above
// any real movie/TV content — to catch malformed/exotic files without
// false-positiving on legitimate 4K/8K-capable devices playing normal
// high-res content.
static const int   OVERSIZED_DIMENSION_THRESHOLD = 7680;

// ── Black-frame sampling state (Stage 2 of decode-compatibility check) ──
// After pixel-format/resolution checks pass (Stage 1) with HW decode
// still active, we take a few REAL screenshots via mpv's "screenshot-raw"
// command and check if they're genuinely black — catching the residual
// class of broken decoders that produce ordinary-looking 8-bit output
// but still render as black (undetectable from format/resolution alone).
static bool  s_black_stage_active   = false; // true while Stage 2 is running
static int   s_black_samples_taken  = 0;
static int   s_black_samples_black  = 0;
static int   s_black_next_tick      = 0;     // tick count at which to take the next sample
static const int BLACK_CHECK_MAX_SAMPLES     = 3;
static const int BLACK_CHECK_SAMPLE_INTERVAL = 2;  // poll ticks between samples (~500ms)
// Luma (0-255) at or below this is treated as "black" for a sampled pixel.
// Conservative (near-absolute-black) to avoid false positives on dark —
// but not blank — scenes (moonlight, film grain, subtle gradients).
static const int BLACK_LUMA_THRESHOLD        = 10;
// Sparse sample grid across the frame (GRID x GRID interior points).
// A genuinely dark movie scene will almost always have SOME bright point
// among 49 samples (highlight, subtitle, sliver of sky) — requiring ALL
// samples to be black makes false positives on real dark content very
// unlikely, while still reliably catching a truly blank/corrupted frame.
static const int BLACK_CHECK_GRID            = 7;

// ── Periodic re-verification (Tier 1 #1) ─────────────────────────────────
// The checks above only run during the first few seconds after file load.
// A decoder that degrades MID-playback — thermal throttling, a GPU driver
// state bug triggered by a specific scene's complexity, VRAM pressure from
// other apps — would never be caught. Once the initial check concludes
// with HW decode confirmed fine (or inconclusive-but-active), we switch to
// low-frequency ongoing monitoring for the rest of the file.
static bool  s_periodic_enabled          = false; // true once initial check leaves HW decode active
static int   s_periodic_next_tick        = 0;
static bool  s_periodic_escalating       = false; // true while confirming a suspected failure
static int   s_periodic_escalation_taken = 0;
static int   s_periodic_escalation_black = 0;
// Routine interval: cheap, infrequent (~30s) — negligible overhead over a
// 90-120 min movie. On a POSITIVE sample (looks black), we escalate to a
// quick 3-sample burst (same spacing as the initial check) to distinguish
// a genuine failure from an ordinary dark scene / transition, rather than
// reacting to a single sample or waiting a full extra interval to confirm.
static const int PERIODIC_RECHECK_INTERVAL_TICKS = 120; // ~30s @ 250ms poll
static const int PERIODIC_ESCALATION_SAMPLES     = 3;   // ALL must be black to switch
static const int PERIODIC_ESCALATION_INTERVAL    = 2;   // ticks between escalation samples (~500ms)

// ── Post-switch SW verification (Tier 1 #2) ──────────────────────────────
// After force_sw_reload_locked() switches to software decode, we assumed
// SW would simply work. In the extremely rare case a file is genuinely
// corrupt or hits an unrelated FFmpeg SW-decode bug, the user would be
// left with a persistent black screen AND a falsely-reassuring "Software
// (FFmpeg — auto-switched)" label. This closes the loop by re-running the
// same pixel-perfect check against the NEW (software) decode output.
static bool  s_sw_verify_pending       = false;
static int   s_sw_verify_wait_ticks    = 0; // ticks spent waiting for re-negotiated video-params
static int   s_sw_verify_next_tick     = 0;
static int   s_sw_verify_samples_taken = 0;
static int   s_sw_verify_samples_black = 0;
static const int SW_VERIFY_MAX_SAMPLES    = 2;
static const int SW_VERIFY_SAMPLE_INTERVAL = 2;  // ticks between samples (~500ms)
static const int SW_VERIFY_MAX_WAIT_TICKS  = 16; // ~4s to wait for re-negotiated video-params

// ── Manual persistent override ───────────────────────────────────
// Set from Kotlin at app start (SharedPreferences-backed) and whenever
// the user flips "Force Software Decode" in Settings. Covers the
// residual class of broken-decoder devices that produce black frames
// even on ordinary 8-bit content — undetectable via pixel format or
// resolution, since the frame is technically valid from mpv's/GL's
// point of view. std::atomic because it's read from init/play (main
// thread via JNI) and from the log-watcher thread.
static std::atomic<bool> s_force_sw_decode{false};

// ── Event-loop thread for log-based failure detection ────────────
// Defense-in-depth alongside the pixel-format/resolution heuristics:
// catches MediaCodec init failures that surface only as mpv log lines
// (e.g. "Failed to init hardware decoding", codec-specific negotiation
// errors) rather than as a specific pixel format we can pattern-match.
// Runs for the lifetime of the mpv_ctx (single instance for app life,
// matching this codebase's existing no-explicit-teardown design).
static std::thread s_event_thread;
static std::atomic<bool> s_event_thread_running{false};

// Forward declarations (defined near the bottom of this file, alongside
// the rest of the dynamic-decode-compatibility implementation).
static void start_mpv_event_thread();
static void force_sw_reload_locked(const char* reason);
static void check_periodic_black_frame(); // Tier 1 #1
static void check_sw_verify();            // Tier 1 #2
static void schedule_sw_verify();         // shared by force_sw_reload_locked + manual override

// ════════════════════════════════════════════════════════════════════
//  BLACK SCREEN — ROOT CAUSE + CURRENT FIX (dynamic, not codec-based)
//  ─────────────────────────────────────────────────────────────────
//
//  ROOT CAUSE:
//  On Android with vo=gpu + gpu-api=opengl, hwdec=mediacodec-copy decodes
//  via MediaCodec then copies pixel data into a GLES texture. This path
//  works for standard 8-bit 4:2:0 output (yuv420p / nv12).
//
//  When the SOURCE requires 10-bit output (HEVC Main10, H.264 Hi10P,
//  VP9 Profile 2, AV1 10-bit, etc.), MediaCodec emits P010 (semi-planar
//  10-bit). MPV uploads that via GL_R16/GL_RG16 textures. On a large
//  slice of real Android GPUs (Mali G76, Adreno 6xx, etc.) that texture
//  upload reports no GL error but the shader reads back zeros — pure
//  black video, while audio (a separate decode/output pipeline) plays
//  fine. This is a GPU-driver-level texture bug, not an MPV or codec bug,
//  and cannot be fixed by MPV config alone.
//
//  OLD FIX (removed): blanket-exclude "hevc" from hwdec-codecs.
//    Problem: many HEVC files ARE 8-bit and decode/render fine on HW —
//    excluding the whole codec wasted hardware decode capability AND
//    still missed the same bug in 10-bit H.264 (Hi10P) / 10-bit VP9,
//    which are NOT excluded by a codec-name blacklist.
//
//  CURRENT FIX (dynamic, format-based, self-healing for ANY codec):
//    After every file load, check_decode_compatibility() inspects the
//    ACTUAL negotiated pixel format (video-params/pixelformat) and the
//    ACTUAL active hwdec backend (hwdec-current) — not the codec name.
//    If HW decode is active AND the negotiated format is a 10-bit/
//    high-bit-depth variant (p010, yuv420p10le, yuv422p10le, etc.),
//    it transparently disables hwdec and reloads the same file from the
//    current position — all within the first ~250-750ms of playback,
//    imperceptible to the user. This covers every current AND future
//    codec/profile that hits the same GPU texture bug, with zero
//    hardcoded codec names.
//
//  Codec mapping after fix:
//    Format               HW path        Result
//    ──────────────────   ────────────   ──────────────────
//    H.264 8-bit           HW copy        video + audio ✓
//    HEVC 8-bit             HW copy        video + audio ✓ (now HW, was forced SW)
//    HEVC Main10 (10-bit)   auto→SW        video + audio ✓ (self-healed)
//    H.264 Hi10P (10-bit)   auto→SW        video + audio ✓ (previously undetected)
//    VP9 / AV1 8-bit        HW copy        video + audio ✓
//    VP9 Profile2 (10-bit)  auto→SW        video + audio ✓ (previously undetected)
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

    // ── Video Output — GPU context: let mpv decide, safely ──────────
    //
    //  Verified directly against mpv core source (video/out/gpu/context.c
    //  + video/out/vulkan/context_android.c + video/out/opengl/context_android.c):
    //
    //  Registered Android context names:
    //    "androidvk"  (type=vulkan, video/out/vulkan/context_android.c)
    //    "android"    (type=opengl, video/out/opengl/context_android.c)
    //
    //  gpu-context accepts an ORDERED comma-separated preference list.
    //  ra_ctx_create() (context.c) tries each name in turn via
    //  create_in_contexts() and uses the FIRST ONE WHOSE init() call
    //  actually succeeds — this is mpv's own built-in, upstream-sanctioned
    //  probing/fallback mechanism, not something we hand-roll:
    //
    //    1. "androidvk" tried first.
    //       - If this specific libmpv.so build didn't compile in Vulkan
    //         support (HAVE_VULKAN unset at build time), this name simply
    //         isn't in the compiled contexts[] array — skipped silently,
    //         zero risk, falls through immediately.
    //       - If Vulkan WAS compiled in but this device's driver fails to
    //         create a working VkSurfaceKHR/device/swapchain at runtime,
    //         android_init() returns failure — mpv moves to the next
    //         candidate. Also zero risk, self-correcting.
    //       - Only when Vulkan is both compiled in AND the device
    //         genuinely initializes it successfully does mpv actually use it.
    //    2. "android" (EGL/GLES) as the fallback — this is the exact path
    //       already proven working in production today.
    //
    //  We deliberately do NOT set "gpu-api" here (previously hardcoded to
    //  "opengl"). Setting gpu-api restricts create_in_contexts()'s type
    //  filter to that API only, which would silently exclude "androidvk"
    //  (type=vulkan) from the list above even when explicitly named —
    //  i.e. the old gpu-api=opengl hardcode is EXACTLY what was preventing
    //  the engine from ever trying Vulkan, regardless of device capability.
    //
    //  Our existing black-screen safety net (check_decode_compatibility's
    //  format/resolution/black-frame checks) is rendering-backend-agnostic:
    //  "screenshot-raw" reads the decoded video frame via
    //  vo_get_current_frame(), independent of whether the active RA
    //  context is OpenGL or Vulkan — so it continues to catch any
    //  black-screen class under Vulkan exactly as it does under OpenGL,
    //  with no changes needed there.
    //
    mpv_set_option_string(mpv_ctx, "vo",          "gpu");
    mpv_set_option_string(mpv_ctx, "gpu-context",  "androidvk,android");
    mpv_set_option_string(mpv_ctx, "opengl-es",   "yes"); // no-op under Vulkan, required when "android" is used

    // ── HDR tone-mapping quality (Tier 2 #5) ─────────────────────────
    //
    // Verified against mpv core source (video/out/gpu/video.c) — these
    // options are part of CLASSIC vo=gpu's option set (NOT exclusive to
    // vo=gpu-next/libplacebo, which we do not use — see the note on
    // "target-colorspace-hint" below).
    //
    // We deliberately do NOT query Android's Display.isHdr()/
    // getHdrCapabilities() from Kotlin to conditionally switch behavior.
    // Real-world reports (e.g. Kodi's own community, forum.kodi.tv
    // thread on Dolby Vision/HDR10 detection) show isHdr() can report
    // false on a genuinely HDR-capable display depending on the HDMI/
    // AVR passthrough chain — an unreliable signal to gate decisions on.
    // Kodi's own stated position: "we don't care if the display is HDR,
    // everything is handled by the hardware decoder [pipeline]." We
    // follow the same principle here: let mpv/libplacebo's own internal
    // capability negotiation decide (target-peak=auto, target-trc/
    // target-prim left at their auto defaults), and instead improve the
    // QUALITY of that negotiation universally — benefiting every device
    // regardless of what it self-reports.
    //
    // hdr-compute-peak=auto: dynamic per-scene peak-brightness detection
    // to refine tone-mapping when static HDR10 metadata is coarse/absent
    // — "auto" lets mpv decide based on GPU capability, never forced on
    // underpowered devices.
    mpv_set_option_string(mpv_ctx, "hdr-compute-peak", "auto");
    // tone-mapping=bt.2390: ITU-R BT.2390 is a widely-recommended,
    // perceptually well-balanced HDR->SDR curve (used as a broadcast/
    // streaming reference by many implementations) — a solid universal
    // default, applied only when tone-mapping is actually needed (i.e.
    // SDR display + HDR source; a no-op for HDR-capable displays where
    // libplacebo instead does gamut/peak adaptation, not full tone-map).
    mpv_set_option_string(mpv_ctx, "tone-mapping", "bt.2390");
    // NOTE: "target-colorspace-hint" (true HDR passthrough signaling)
    // only exists on vo=gpu-next (video/out/vo_gpu_next.c), which we do
    // not currently use (see Tier 2 #6 — a separate, larger migration).
    // Setting it here would be a no-op (or a "no such option" warning)
    // on our classic vo=gpu backend, so it is intentionally omitted.

    // ── Hardware Decode ───────────────────────────────────────────
    //
    //  hwdec = mediacodec-copy  (NOT "auto-safe" — see note above:
    //  auto-safe resolves to plain "mediacodec" in this libmpv build,
    //  which vo=gpu cannot read from → always black.)
    //
    //  hwdec-codecs now includes hevc: the old blanket exclusion is
    //  replaced by check_decode_compatibility(), which detects the
    //  actual problematic case (10-bit output / oversized frames) at
    //  runtime for ANY codec and falls back to SW only when needed.
    //
    //  s_force_sw_decode: user-controlled persistent override (see
    //  set_force_sw_decode() below) for devices whose HW decoder
    //  produces black frames even on ordinary 8-bit content — a class
    //  of bug that cannot be detected from pixel format or resolution
    //  alone. If set, we skip HW decode entirely from app start.
    //
    bool force_sw = s_force_sw_decode.load();
    mpv_set_option_string(mpv_ctx, "hwdec",        force_sw ? "no" : "mediacodec-copy");
    mpv_set_option_string(mpv_ctx, "hwdec-codecs", "h264,hevc,vp8,vp9,av1,mpeg2video,mpeg4");

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
    mpv_set_option_string(mpv_ctx, "stream-buffer-size",     "4MiB");

    // ── Network ───────────────────────────────────────────────────
    mpv_set_option_string(mpv_ctx, "network-timeout",       "30");
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

    // Request warn-level log messages so the event-loop thread can watch
    // for MediaCodec/hwdec init failures that don't manifest as a
    // specific pixel format (defense-in-depth alongside the pixel-format
    // and resolution heuristics in check_decode_compatibility()).
    mpv_request_log_messages(mpv_ctx, "warn");

    start_mpv_event_thread();

    LOGD("MPV OK — hwdec=%s codecs=h264,hevc,vp8,vp9,av1 dynamic-fallback=on force_sw=%d",
         force_sw ? "no" : "mediacodec-copy", (int)force_sw);
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

    // Reset dynamic decode-compatibility state for the new file.
    // check_decode_compatibility() (polled from Kotlin every ~250ms)
    // will inspect video-params once they're negotiated and auto-switch
    // to SW decode if a 10-bit/high-bit-depth format or oversized frame
    // is detected.
    s_current_path       = path ? path : "";
    s_decode_check_ticks = 0;
    s_switch_reason.clear();
    // Reset Stage 2 (black-frame sampling) state for the new file.
    s_black_stage_active  = false;
    s_black_samples_taken = 0;
    s_black_samples_black = 0;
    s_black_next_tick     = 0;
    // Reset periodic re-verification state (Tier 1 #1) for the new file.
    s_periodic_enabled          = false;
    s_periodic_next_tick        = 0;
    s_periodic_escalating       = false;
    s_periodic_escalation_taken = 0;
    s_periodic_escalation_black = 0;
    // Reset post-switch SW verification state (Tier 1 #2) for the new file.
    s_sw_verify_pending       = false;
    s_sw_verify_wait_ticks    = 0;
    s_sw_verify_next_tick     = 0;
    s_sw_verify_samples_taken = 0;
    s_sw_verify_samples_black = 0;

    if (s_force_sw_decode.load()) {
        // Persistent manual override — skip detection entirely, this
        // device is already known to need SW decode for everything.
        mpv_set_option_string(mpv_ctx, "hwdec", "no");
        s_decode_check_done   = true;
        s_forced_sw_this_file = true;
        s_switch_reason       = "manual";
        // Still verify SW actually produces a visible frame — even a
        // device known to need SW globally could hit a genuinely corrupt
        // file, and the user deserves an accurate diagnostic either way.
        schedule_sw_verify();
    } else {
        mpv_set_option_string(mpv_ctx, "hwdec", "mediacodec-copy");
        s_decode_check_done   = false;
        s_forced_sw_this_file = false;
    }

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

// ════════════════════════════════════════════════════════════════════
//  Dynamic HW/SW decode compatibility
//  ─────────────────────────────────────────────────────────────────
//  Called from Kotlin's existing 250ms poll loop (same one that already
//  polls getMpvTime/getMpvDuration) — see MoviePlayerScreen.kt time-sync
//  LaunchedEffect. No new thread, no new event loop; piggybacks on
//  polling that already runs.
// ════════════════════════════════════════════════════════════════════

// Returns true if `fmt` (an mpv pixelformat name, e.g. "yuv420p",
// "p010", "yuv420p10le") is a 10-bit / high-bit-depth variant known to
// trigger black-frame GLES texture bugs on Android when hardware
// decoded. Matched by substring so it covers every chroma layout
// (4:2:0 / 4:2:2 / 4:4:4) and both P010-style and planar 10-bit names
// without hardcoding a specific codec.
static bool is_problematic_hbd_format(const std::string& fmt) {
    if (fmt.empty()) return false;
    static const char* needles[] = {
        "p010", "p016",            // MediaCodec semi-planar 10/16-bit output
        "yuv420p10", "yuv422p10", "yuv444p10",  // planar 10-bit (SW-negotiated name, still signals HBD source)
        "yuv420p12", "yuv422p12", "yuv444p12",  // 12-bit variants (HDR sources)
        "nv12hdr", "y210"
    };
    for (const char* n : needles) {
        if (fmt.find(n) != std::string::npos) return true;
    }
    return false;
}

// Extreme-resolution guard. See OVERSIZED_DIMENSION_THRESHOLD comment
// at the top of this file for the reasoning (7680px — well above any
// real movie content, avoids false-positiving on legitimate 4K/8K
// HW-capable devices).
static bool is_oversized_resolution(int64_t w, int64_t h) {
    return w > OVERSIZED_DIMENSION_THRESHOLD || h > OVERSIZED_DIMENSION_THRESHOLD;
}

// ── Pixel-perfect black-frame detection ──────────────────────────
// ASSUMES CALLER ALREADY HOLDS mpv_mutex.
//
// Uses mpv's own "screenshot-raw" command (mpv_command_node) to grab the
// ACTUAL currently-decoded video frame — no GLSurfaceView/EGL/FBO/render-
// API migration needed. Verified directly against mpv's player/screenshot.c:
//   - "video" flag skips OSD/subtitle/scaling — pure decoder output.
//   - vo_get_current_frame() returns a hardware frame when hwdec is active;
//     mpv automatically calls mp_image_hw_download() to copy it to normal
//     CPU memory before returning it to us — this is the SAME download
//     path that would need to succeed for the frame to display correctly
//     at all, so this check exercises the real GPU→CPU/GPU→texture data
//     path our black-screen bug lives in, not a synthetic substitute.
//   - Default format is "bgr0" (confirmed in player/command.c OPTDEF_INT(0))
//     = FFmpeg AV_PIX_FMT_BGR0 byte order: byte0=B, byte1=G, byte2=R, byte3=0.
//   - Returned mpv_byte_array: { void* data; size_t size; } (client.h).
//   - screenshot-raw is NOT spawn_thread — it runs synchronously on mpv's
//     core thread, so we deliberately call this sparingly (a few samples,
//     spaced ~500ms apart), never on every poll tick.
//
// Returns: 1 = confirmed black frame, 0 = confirmed NOT black,
//          -1 = capture failed / inconclusive (don't count as a sample).
static int capture_and_check_black_frame() {
    if (!mpv_ctx) return -1;

    mpv_node args[2];
    args[0].format = MPV_FORMAT_STRING;
    args[0].u.string = const_cast<char*>("screenshot-raw");
    args[1].format = MPV_FORMAT_STRING;
    args[1].u.string = const_cast<char*>("video");   // decoder output only, no OSD/subs

    mpv_node_list arr{};
    arr.num = 2;
    arr.values = args;

    mpv_node cmd{};
    cmd.format = MPV_FORMAT_NODE_ARRAY;
    cmd.u.list = &arr;

    mpv_node result{};
    if (mpv_command_node(mpv_ctx, &cmd, &result) < 0) return -1;

    int64_t w = 0, h = 0, stride = 0;
    bool format_ok = false;
    struct mpv_byte_array* ba = nullptr;

    if (result.format == MPV_FORMAT_NODE_MAP && result.u.list) {
        for (int i = 0; i < result.u.list->num; i++) {
            const char* key = result.u.list->keys[i];
            mpv_node*   val = &result.u.list->values[i];
            if (!key || !val) continue;
            if (!strcmp(key, "w") && val->format == MPV_FORMAT_INT64) {
                w = val->u.int64;
            } else if (!strcmp(key, "h") && val->format == MPV_FORMAT_INT64) {
                h = val->u.int64;
            } else if (!strcmp(key, "stride") && val->format == MPV_FORMAT_INT64) {
                stride = val->u.int64;
            } else if (!strcmp(key, "format") && val->format == MPV_FORMAT_STRING) {
                format_ok = val->u.string && !strcmp(val->u.string, "bgr0");
            } else if (!strcmp(key, "data") && val->format == MPV_FORMAT_BYTE_ARRAY) {
                ba = val->u.ba;
            }
        }
    }

    if (w <= 0 || h <= 0 || stride <= 0 || !format_ok || !ba || !ba->data ||
        ba->size < (size_t)(stride * h)) {
        LOGD("decode-compat: black-frame capture inconclusive (w=%" PRId64 " h=%" PRId64
             " format_ok=%d ba=%p)", w, h, (int)format_ok, (void*)ba);
        mpv_free_node_contents(&result);
        return -1;
    }

    const uint8_t* base = reinterpret_cast<const uint8_t*>(ba->data);
    int sample_count = 0, black_count = 0;
    for (int gy = 1; gy <= BLACK_CHECK_GRID; gy++) {
        for (int gx = 1; gx <= BLACK_CHECK_GRID; gx++) {
            int64_t x = (int64_t)gx * w  / (BLACK_CHECK_GRID + 1);
            int64_t y = (int64_t)gy * h  / (BLACK_CHECK_GRID + 1);
            if (x < 0 || x >= w || y < 0 || y >= h) continue;
            const uint8_t* px = base + (size_t)y * (size_t)stride + (size_t)x * 4;
            // BGR0 byte order (confirmed against FFmpeg AV_PIX_FMT_BGR0 /
            // mpv IMGFMT_BGR0): byte0=B, byte1=G, byte2=R, byte3=padding.
            int b = px[0], g = px[1], r = px[2];
            int luma = (r + g + b) / 3;
            sample_count++;
            if (luma <= BLACK_LUMA_THRESHOLD) black_count++;
        }
    }
    mpv_free_node_contents(&result);

    if (sample_count == 0) return -1;
    bool is_black = (black_count == sample_count);
    LOGD("decode-compat: black-frame sample %d/%d points black (%s)",
         black_count, sample_count, is_black ? "BLACK" : "ok");
    return is_black ? 1 : 0;
}

// ── Shared reload-as-SW implementation ──────────────────────────
// ASSUMES CALLER ALREADY HOLDS mpv_mutex. Used by both
// check_decode_compatibility() (main poll-driven path) and the
// event-loop thread's log-based failure detector (secondary,
// defense-in-depth path). `reason` is stored for the settings-page
// diagnostics ("10bit" / "oversized" / "log-detected" / "manual").
static void force_sw_reload_locked(const char* reason) {
    if (!mpv_ctx || s_current_path.empty() || s_forced_sw_this_file) return;

    double time_pos = 0.0;
    mpv_get_property(mpv_ctx, "time-pos", MPV_FORMAT_DOUBLE, &time_pos);
    if (time_pos < 0.0) time_pos = 0.0;

    LOGD("decode-compat: switching to SW decode (%s), resume at %.2fs, file=%s",
         reason, time_pos, s_current_path.c_str());

    mpv_set_option_string(mpv_ctx, "hwdec", "no");

    std::string start_opt = "start=" + std::to_string(time_pos);
    const char* cmd[] = {"loadfile", s_current_path.c_str(), "replace", start_opt.c_str(), nullptr};
    int r = mpv_command(mpv_ctx, cmd);
    if (r < 0) LOGE("decode-compat: reload-as-SW failed: %s", mpv_error_string(r));

    // Restore hwdec option to mediacodec-copy for the NEXT file — "no"
    // was only meant for this one (unless s_force_sw_decode is globally
    // set, in which case play_mpv_video() will set "no" again anyway).
    mpv_set_option_string(mpv_ctx, "hwdec", "mediacodec-copy");

    s_forced_sw_this_file = true;
    s_switch_reason       = reason;
    s_decode_check_done   = true;
    // Black-frame sampling (if it was running) is no longer relevant —
    // we've already switched to SW decode for this file.
    s_black_stage_active  = false;
    // Also cancel periodic monitoring — we're on SW now, that mechanism
    // exists to catch HW-decode degradation and no longer applies.
    s_periodic_enabled    = false;
    s_periodic_escalating = false;

    // Verify the switch actually worked (Tier 1 #2) — see state comment
    // near s_sw_verify_pending's declaration for why this matters.
    schedule_sw_verify();
}

static void schedule_sw_verify() {
    s_sw_verify_pending       = true;
    s_sw_verify_wait_ticks    = 0;
    s_sw_verify_next_tick     = 0;
    s_sw_verify_samples_taken = 0;
    s_sw_verify_samples_black = 0;
}

// ── Tier 1 #2: verify the SW switch actually worked ──────────────
// ASSUMES CALLER ALREADY HOLDS mpv_mutex.
static void check_sw_verify() {
    // Wait for the reloaded file's video-params to re-negotiate — confirms
    // the reload actually completed and a fresh frame pipeline is active,
    // so we don't accidentally sample a stale pre-reload frame.
    char* fmt_raw = mpv_get_property_string(mpv_ctx, "video-params/pixelformat");
    std::string pixfmt = fmt_raw ? fmt_raw : "";
    if (fmt_raw) mpv_free(fmt_raw);

    if (pixfmt.empty()) {
        if (++s_sw_verify_wait_ticks >= SW_VERIFY_MAX_WAIT_TICKS) {
            LOGD("decode-compat: SW-verify gave up waiting for video-params after reload");
            s_sw_verify_pending = false;
        }
        return;
    }

    s_decode_check_ticks++;
    if (s_decode_check_ticks < s_sw_verify_next_tick) return;

    int result = capture_and_check_black_frame();
    s_sw_verify_next_tick = s_decode_check_ticks + SW_VERIFY_SAMPLE_INTERVAL;

    if (result == 0) {
        LOGD("decode-compat: SW-verify confirmed non-black — software decode working correctly");
        s_sw_verify_pending = false;
        return;
    }
    if (result == 1) s_sw_verify_samples_black++;
    s_sw_verify_samples_taken++;

    if (s_sw_verify_samples_taken >= SW_VERIFY_MAX_SAMPLES) {
        s_sw_verify_pending = false;
        if (s_sw_verify_samples_black >= SW_VERIFY_MAX_SAMPLES) {
            // Software decode ALSO produced black frames. We've exhausted
            // every automatic remedy — update the diagnostic reason so the
            // settings page tells the user honestly that even the fallback
            // didn't help, rather than showing a falsely-reassuring
            // "Software (FFmpeg — auto-switched)" label.
            LOGD("decode-compat: SW-verify FAILED — software decode also black. "
                 "File may be corrupt or use an unsupported feature.");
            s_switch_reason = "sw-also-black";
        }
        // else: inconclusive (capture failures mixed in) — leave as-is,
        // already on SW, nothing further we can automatically try.
    }
}

// ── Tier 1 #1: ongoing monitoring for the rest of playback ───────
// ASSUMES CALLER ALREADY HOLDS mpv_mutex.
static void check_periodic_black_frame() {
    s_decode_check_ticks++;

    if (s_periodic_escalating) {
        if (s_decode_check_ticks < s_periodic_next_tick) return;

        int result = capture_and_check_black_frame();
        s_periodic_next_tick = s_decode_check_ticks + PERIODIC_ESCALATION_INTERVAL;

        if (result == 0) {
            // False alarm — likely a scene transition, not a real failure.
            LOGD("decode-compat: periodic escalation cleared (scene transition, not a failure)");
            s_periodic_escalating = false;
            s_periodic_next_tick  = s_decode_check_ticks + PERIODIC_RECHECK_INTERVAL_TICKS;
            return;
        }
        if (result == 1) s_periodic_escalation_black++;
        s_periodic_escalation_taken++;

        if (s_periodic_escalation_taken >= PERIODIC_ESCALATION_SAMPLES) {
            s_periodic_escalating = false;
            if (s_periodic_escalation_black >= PERIODIC_ESCALATION_SAMPLES) {
                LOGD("decode-compat: periodic re-check confirmed sustained black frame "
                     "mid-playback (%d/%d samples) — switching to SW",
                     s_periodic_escalation_black, PERIODIC_ESCALATION_SAMPLES);
                force_sw_reload_locked("black-frame-periodic");
            } else {
                // Inconclusive (capture failures mixed in) — resume
                // normal-interval monitoring rather than treating this as
                // confirmed either way.
                s_periodic_next_tick = s_decode_check_ticks + PERIODIC_RECHECK_INTERVAL_TICKS;
            }
        }
        return;
    }

    // Normal routine interval — cheap, infrequent.
    if (s_decode_check_ticks < s_periodic_next_tick) return;

    int result = capture_and_check_black_frame();
    if (result == 1) {
        // Possible real failure — escalate to a quick 3-sample burst to
        // confirm within a few seconds, rather than waiting the full
        // ~30s interval three times over (which would mean up to 90s of
        // a genuinely black screen before reacting).
        LOGD("decode-compat: periodic sample came back black — escalating to confirm");
        s_periodic_escalating       = true;
        s_periodic_escalation_taken = 1;
        s_periodic_escalation_black = 1;
        s_periodic_next_tick        = s_decode_check_ticks + PERIODIC_ESCALATION_INTERVAL;
    } else {
        // Not black (or inconclusive capture) — all fine, schedule the
        // next routine check.
        s_periodic_next_tick = s_decode_check_ticks + PERIODIC_RECHECK_INTERVAL_TICKS;
    }
}

void check_decode_compatibility() {
    std::lock_guard<std::mutex> lk(mpv_mutex);
    if (!mpv_ctx) return;

    // ── Tier 1 #2: post-switch SW verification ────────────────────────
    // Takes priority over everything else — runs right after a reload to
    // SW decode, to confirm the switch actually fixed the problem.
    if (s_sw_verify_pending) {
        check_sw_verify();
        return;
    }

    // ── Tier 1 #1: ongoing periodic monitoring ────────────────────────
    // Runs for the rest of playback once the initial Stage 1+2 checks
    // below have concluded with HW decode confirmed fine (or left active
    // as inconclusive-but-not-a-confirmed-problem).
    if (s_decode_check_done && s_periodic_enabled && !s_forced_sw_this_file) {
        check_periodic_black_frame();
        return;
    }

    // ── Stage 2: pixel-perfect black-frame sampling ──────────────────
    // Runs AFTER Stage 1 has resolved with format/resolution looking fine
    // but HW decode still active. This is the check that catches the
    // fully-undetectable residual class: a decoder that produces ordinary
    // 8-bit output (so Stage 1 sees nothing wrong) but still renders
    // black due to a device-specific bug. Unlike Stage 1 (which infers
    // risk from format/resolution), this stage looks at ACTUAL rendered
    // pixel content — proof, not inference.
    if (s_black_stage_active) {
        if (++s_decode_check_ticks < s_black_next_tick) return; // not time yet

        int result = capture_and_check_black_frame();
        s_black_next_tick = s_decode_check_ticks + BLACK_CHECK_SAMPLE_INTERVAL;

        if (result == 0) {
            // Confirmed NOT black — device is fine, stop sampling immediately.
            LOGD("decode-compat: black-frame check passed — HW decode confirmed OK");
            s_black_stage_active = false;
            // Enable ongoing periodic monitoring (Tier 1 #1) for the rest
            // of this file's playback, now that HW decode is confirmed
            // genuinely working at this point in time.
            s_periodic_enabled   = true;
            s_periodic_next_tick = s_decode_check_ticks + PERIODIC_RECHECK_INTERVAL_TICKS;
            return;
        }
        if (result == 1) {
            s_black_samples_taken++;
            s_black_samples_black++;
        } else {
            // Capture failed/inconclusive — don't count it as a sample,
            // but also don't retry indefinitely; treat repeated failures
            // as a capped sample so this stage still terminates.
            s_black_samples_taken++;
        }

        if (s_black_samples_taken >= BLACK_CHECK_MAX_SAMPLES) {
            s_black_stage_active = false;
            if (s_black_samples_black >= BLACK_CHECK_MAX_SAMPLES) {
                // Every single sample across ~1.5s was pure black while
                // HW decode was active — this is the real thing, not a
                // dark opening scene (which would show at least some
                // bright sample point across 3 different frames).
                LOGD("decode-compat: %d/%d samples confirmed black — "
                     "switching to SW (undetectable-by-format case)",
                     s_black_samples_black, BLACK_CHECK_MAX_SAMPLES);
                force_sw_reload_locked("black-frame");
            } else {
                LOGD("decode-compat: black-frame sampling inconclusive "
                     "(%d/%d black, capture may have failed) — leaving HW decode active",
                     s_black_samples_black, BLACK_CHECK_MAX_SAMPLES);
                // Leaving HW decode active without a confirmed problem —
                // still enable periodic monitoring (Tier 1 #1) as an
                // ongoing safety net for the rest of playback.
                s_periodic_enabled   = true;
                s_periodic_next_tick = s_decode_check_ticks + PERIODIC_RECHECK_INTERVAL_TICKS;
            }
        }
        return;
    }

    if (s_decode_check_done) return;

    // ── Stage 1: pixel-format / resolution check (fast, no frame capture) ──
    char* fmt_raw = mpv_get_property_string(mpv_ctx, "video-params/pixelformat");
    std::string pixfmt = fmt_raw ? fmt_raw : "";
    if (fmt_raw) mpv_free(fmt_raw);

    if (pixfmt.empty()) {
        // video-params not negotiated yet — wait for the next poll tick,
        // but give up after DECODE_CHECK_MAX_TICKS so we never check forever
        // (e.g. audio-only files, or a file that fails to open at all).
        if (++s_decode_check_ticks >= DECODE_CHECK_MAX_TICKS) {
            s_decode_check_done = true;
            LOGD("decode-compat: gave up waiting for video-params (no video track?)");
        }
        return;
    }

    char* hw_raw = mpv_get_property_string(mpv_ctx, "hwdec-current");
    std::string hwdec_current = hw_raw ? hw_raw : "";
    if (hw_raw) mpv_free(hw_raw);

    int64_t w = 0, h = 0;
    mpv_get_property(mpv_ctx, "video-params/w", MPV_FORMAT_INT64, &w);
    mpv_get_property(mpv_ctx, "video-params/h", MPV_FORMAT_INT64, &h);

    bool is_hw_active  = !hwdec_current.empty();
    bool is_hbd        = is_problematic_hbd_format(pixfmt);
    bool is_oversized  = is_oversized_resolution(w, h);

    LOGD("decode-compat check: pixfmt=%s hwdec-current=%s %" PRId64 "x%" PRId64
         " hbd=%d oversized=%d",
         pixfmt.c_str(), hwdec_current.c_str(), w, h, (int)is_hbd, (int)is_oversized);

    s_decode_check_done = true; // Stage 1 resolved either way

    if (is_hw_active && !s_current_path.empty()) {
        if (is_hbd) {
            force_sw_reload_locked("10bit");
            return;
        }
        if (is_oversized) {
            force_sw_reload_locked("oversized");
            return;
        }
        // Format/resolution look fine but HW decode is active — hand off
        // to Stage 2 for proof-based confirmation via real frame sampling.
        s_black_stage_active  = true;
        s_black_samples_taken = 0;
        s_black_samples_black = 0;
        s_black_next_tick     = s_decode_check_ticks + BLACK_CHECK_SAMPLE_INTERVAL;
        LOGD("decode-compat: format/resolution OK, starting black-frame "
             "sampling (Stage 2) as final confirmation");
    }
    // If HW decode isn't even active (already SW, e.g. forced globally),
    // there's nothing further to check.
}

// ── Event-loop thread: log-based failure detection ──────────────
// Secondary safety net alongside the pixel-format/resolution checks
// above. Catches MediaCodec init/negotiation failures that surface
// only as mpv log lines (e.g. "Failed to init hardware decoding")
// rather than as a specific pixel format — covers cases the
// proactive checks can't predict in advance.
static void mpv_event_loop() {
    LOGD("decode-compat: event-loop thread started");
    while (s_event_thread_running.load()) {
        // 500ms wait keeps this thread responsive without busy-looping.
        // mpv_wait_event() is designed to be called repeatedly from one
        // dedicated thread — safe alongside other threads calling other
        // mpv_* functions concurrently.
        mpv_event* ev = mpv_wait_event(mpv_ctx, 0.5);
        if (!ev || ev->event_id == MPV_EVENT_NONE) continue;

        if (ev->event_id == MPV_EVENT_SHUTDOWN) break;

        if (ev->event_id == MPV_EVENT_LOG_MESSAGE) {
            auto* msg = static_cast<mpv_event_log_message*>(ev->data);
            if (!msg || !msg->text) continue;
            std::string line(msg->text);

            // Pattern-match known hwdec/MediaCodec failure phrasing from
            // mpv's own vd_lavc.c / hwdec backends. Deliberately broad
            // substrings (case-sensitive, matching mpv's actual log
            // wording) rather than a strict allowlist, since exact
            // wording varies slightly across mpv versions.
            bool looks_like_hwdec_failure =
                (line.find("Failed to init") != std::string::npos &&
                 line.find("hwdec") != std::string::npos) ||
                (line.find("mediacodec") != std::string::npos &&
                 (line.find("fail") != std::string::npos ||
                  line.find("error") != std::string::npos ||
                  line.find("Error") != std::string::npos)) ||
                line.find("Error creating MediaCodec") != std::string::npos ||
                line.find("codec did not output") != std::string::npos;

            if (looks_like_hwdec_failure) {
                LOGD("decode-compat: log-detected hwdec failure: %s", line.c_str());
                std::lock_guard<std::mutex> lk(mpv_mutex);
                if (mpv_ctx && !s_forced_sw_this_file && !s_current_path.empty()) {
                    force_sw_reload_locked("log-detected");
                }
            }
        }
    }
    LOGD("decode-compat: event-loop thread exiting");
}

static void start_mpv_event_thread() {
    // ASSUMES CALLER ALREADY HOLDS mpv_mutex (called from init_mpv_engine).
    if (s_event_thread_running.load()) return;
    s_event_thread_running.store(true);
    s_event_thread = std::thread(mpv_event_loop);
    s_event_thread.detach();
}

std::string get_decode_mode_label() {
    std::lock_guard<std::mutex> lk(mpv_mutex);
    if (!mpv_ctx) return "\u2014";

    char* fmt_raw = mpv_get_property_string(mpv_ctx, "video-params/pixelformat");
    std::string pixfmt = fmt_raw ? fmt_raw : "";
    if (fmt_raw) mpv_free(fmt_raw);
    if (pixfmt.empty()) return "Detecting\u2026";

    char* hw_raw = mpv_get_property_string(mpv_ctx, "hwdec-current");
    std::string hwdec_current = hw_raw ? hw_raw : "";
    if (hw_raw) mpv_free(hw_raw);

    if (!hwdec_current.empty()) {
        return "Hardware (" + hwdec_current + ")";
    }
    if (s_forced_sw_this_file) {
        return "Software (FFmpeg \u2014 auto-switched)";
    }
    return "Software (FFmpeg)";
}

std::string get_decode_diag_info() {
    std::lock_guard<std::mutex> lk(mpv_mutex);
    if (!mpv_ctx) return "||0|0|";

    char* codec_raw = mpv_get_property_string(mpv_ctx, "video-codec");
    std::string codec = codec_raw ? codec_raw : "";
    if (codec_raw) mpv_free(codec_raw);

    char* fmt_raw = mpv_get_property_string(mpv_ctx, "video-params/pixelformat");
    std::string pixfmt = fmt_raw ? fmt_raw : "";
    if (fmt_raw) mpv_free(fmt_raw);

    char* hw_raw = mpv_get_property_string(mpv_ctx, "hwdec-current");
    std::string hwdec_current = hw_raw ? hw_raw : "";
    if (hw_raw) mpv_free(hw_raw);

    // "<codec>|<pixelformat>|<hwdec-current>|<auto_switched>|<reason>"
    std::ostringstream out;
    out << codec << "|" << pixfmt << "|" << hwdec_current << "|"
        << (s_forced_sw_this_file ? "1" : "0") << "|" << s_switch_reason;
    return out.str();
}

void set_force_sw_decode(bool force) {
    std::lock_guard<std::mutex> lk(mpv_mutex);
    s_force_sw_decode.store(force);
    LOGD("decode-compat: force_sw_decode set to %d (persisted by Kotlin)", (int)force);
    // If a file is already loaded and this was just turned ON, apply it
    // immediately rather than waiting for the next file.
    if (force && mpv_ctx && !s_current_path.empty() && !s_forced_sw_this_file) {
        force_sw_reload_locked("manual");
    }
}

bool get_force_sw_decode() {
    return s_force_sw_decode.load();
}

std::string get_active_gpu_context() {
    std::lock_guard<std::mutex> lk(mpv_mutex);
    if (!mpv_ctx) return "\u2014";

    char* raw = mpv_get_property_string(mpv_ctx, "current-gpu-context");
    std::string ctx = raw ? raw : "";
    if (raw) mpv_free(raw);

    if (ctx.empty()) return "Detecting\u2026";
    // Registered names confirmed against mpv source (context_android.c):
    //   "androidvk" (type=vulkan)  "android" (type=opengl, EGL/GLES)
    if (ctx == "androidvk") return "Vulkan (androidvk)";
    if (ctx == "android")   return "OpenGL ES (android)";
    return ctx; // fallback: show raw name for any future/unexpected context
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
