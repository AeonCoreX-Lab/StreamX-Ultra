#ifndef MPV_HANDLER_HPP
#define MPV_HANDLER_HPP

#include <jni.h>
#include <stdint.h>
#include <string>

void        init_mpv_engine(JNIEnv* env, jobject appctx);
void        set_mpv_wid(int64_t wid);
void        set_mpv_surface_size(int w, int h);
void        play_mpv_video(const char* path);
void        toggle_vulkan_fsr(bool enable);
double      get_mpv_time();
double      get_mpv_duration();
void        seek_mpv_video(double seconds);       // relative seek
void        seek_mpv_absolute(double position);   // absolute seek (seconds)
void        pause_mpv_video(bool pause);
void        command_mpv(const char** args);
void        set_property_string_mpv(const char* name, const char* value);
std::string get_property_string_mpv_safe(const char* name);
int64_t     get_property_int_mpv(const char* name);
int         get_cache_percent_mpv();
int         is_paused_for_cache_mpv();
std::string get_track_list_mpv(const char* type);

// ── Playback-error accessors ─────────────────────────────────────
// Surfaces genuine MPV_END_FILE_REASON_ERROR failures (network errors,
// expired signed URLs, HTTP 403/404, connection resets) so Kotlin can
// detect and react to them — used by MOVIEBOX/DIRECT playback modes,
// which have no torrent-engine ERROR state to poll instead.
// get_playback_error_generation() increments on every new error;
// compare against the last-seen value to detect a fresh failure
// without re-acting to one already handled.
std::string get_last_playback_error();
int         get_playback_error_generation();
void        clear_last_playback_error();


// ── Dynamic HW/SW decode compatibility ─────────────────────────
// Called periodically (every ~250ms poll tick) right after file load.
// Detects HW-decode formats known to render black on Android GLES
// (e.g. 10-bit P010 output) and transparently reloads the same file
// with hwdec disabled — self-healing, no codec whitelist needed.
void        check_decode_compatibility();
// Human-readable current mode for settings UI, e.g.
// "Hardware (mediacodec-copy)" / "Software (FFmpeg)" / "Detecting…"
std::string get_decode_mode_label();
// Diagnostic string for the detail settings page:
// "<codec>|<pixelformat>|<hwdec-current>|<auto_switched 0/1>|<reason>"
std::string get_decode_diag_info();

// ── Manual persistent override (for undetectable broken-decoder cases) ──
// Some devices have HW decoders that produce a black frame on 8-bit
// content too (rare, broken chipset firmware) — this cannot be detected
// from pixel format or resolution alone without GPU pixel readback,
// which this rendering architecture (wid-embedded, not render-API) does
// not support. For that residual case, the user can force SW decode
// permanently for their device via Settings; Kotlin persists this in
// SharedPreferences and calls set_force_sw_decode() on every app start.
//
// FIX: set_force_sw_decode() now applies IMMEDIATELY and UNCONDITIONALLY
// to whatever file is currently playing, in EITHER direction (SW<->HW),
// via apply_decode_mode_now_locked() — not gated on whether an automatic
// switch already happened this file. Previously, toggling this after an
// automatic negotiation-timeout/black-frame switch had already run was a
// silent no-op (the automatic path's own re-entry guard blocked it too),
// which was exactly the situation a user reaches for this toggle in.
void set_force_sw_decode(bool force);
bool get_force_sw_decode();

// ── GPU rendering backend visibility ─────────────────────────────
// Human-readable label for which GPU context actually activated,
// e.g. "Vulkan (androidvk)" / "OpenGL ES (android)" — reflects mpv's
// own "current-gpu-context" property (the real outcome of the
// gpu-context="androidvk,android" probing/fallback list set at init).
std::string get_active_gpu_context();

#endif