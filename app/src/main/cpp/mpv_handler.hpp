#ifndef MPV_HANDLER_HPP
#define MPV_HANDLER_HPP

#include <android/native_window_jni.h>

void init_mpv_engine();
void play_mpv_video(const char* path);
void set_mpv_surface(ANativeWindow* window);
void toggle_vulkan_fsr(bool enable);
double get_mpv_time();
double get_mpv_duration();
void seek_mpv_video(double seconds);
void pause_mpv_video(bool pause);
void command_mpv(const char** args);
void set_property_string_mpv(const char* name, const char* value);

#endif // MPV_HANDLER_HPP
