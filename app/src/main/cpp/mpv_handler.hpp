#ifndef MPV_HANDLER_HPP
#define MPV_HANDLER_HPP

#include <android/native_window_jni.h>
#include <stdint.h>

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

// ── New: property readers ──────────────────────────────────────
// Returns a heap-allocated string; caller must free() it.
// Returns nullptr on error.
char*       get_property_string_mpv(const char* name);

// Returns integer property value, or -1 on error.
int64_t     get_property_int_mpv(const char* name);

// Returns 0-100 cache fill % when buffering, 100 when full/not buffering.
int         get_cache_percent_mpv();

// Returns 1 if MPV is currently paused waiting for cache data, 0 otherwise.
int         is_paused_for_cache_mpv();

#endif
