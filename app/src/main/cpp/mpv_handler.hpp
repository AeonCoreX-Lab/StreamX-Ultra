#ifndef MPV_HANDLER_HPP
#define MPV_HANDLER_HPP

#include <android/native_window_jni.h>
#include <stdint.h>
#include <string>

void        init_mpv_engine();
void        play_mpv_video(const char* path);
void        set_mpv_surface(ANativeWindow* window);
void        set_mpv_surface_size(int width, int height);
void        toggle_vulkan_fsr(bool enable);
double      get_mpv_time();
double      get_mpv_duration();
void        seek_mpv_video(double seconds);
void        pause_mpv_video(bool pause);
void        command_mpv(const char** args);
void        set_property_string_mpv(const char* name, const char* value);

// ── Safe property readers (no raw char* leaks across compilation units) ────
// All MPV allocation/deallocation happens inside mpv_handler.cpp where
// mpv_free() is accessible. Callers never see a raw MPV-allocated pointer.

// Returns property value as std::string (empty string on error, never crashes)
std::string get_property_string_mpv_safe(const char* name);

// Returns integer property, -1 on error
int64_t     get_property_int_mpv(const char* name);

// Returns 0-100 cache fill %, or 100 when not buffering
int         get_cache_percent_mpv();

// Returns 1 if MPV is paused waiting for cache data
int         is_paused_for_cache_mpv();

// ── Track list ────────────────────────────────────────────────────────────
// Returns ALL tracks of the given type as a single pipe-delimited string:
//   "id|title|selected;id|title|selected;..."
// This avoids calling property APIs in a loop from Kotlin/JNI.
std::string get_track_list_mpv(const char* type);

#endif
