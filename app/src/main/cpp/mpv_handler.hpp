#ifndef MPV_HANDLER_HPP
#define MPV_HANDLER_HPP

#include <android/native_window_jni.h>
#include <stdint.h>
#include <string>

void        init_mpv_engine();
void        play_mpv_video(const char* path);
void        set_mpv_surface(ANativeWindow* window);   // set ONCE on surfaceCreated
void        set_mpv_surface_size(int width, int height); // safe to call anytime
void        toggle_vulkan_fsr(bool enable);
double      get_mpv_time();
double      get_mpv_duration();
void        seek_mpv_video(double seconds);
void        pause_mpv_video(bool pause);
void        command_mpv(const char** args);
void        set_property_string_mpv(const char* name, const char* value);
std::string get_property_string_mpv_safe(const char* name);
int64_t     get_property_int_mpv(const char* name);
int         get_cache_percent_mpv();
int         is_paused_for_cache_mpv();
std::string get_track_list_mpv(const char* type);

#endif