#include "mpv_handler.hpp"
#include <mpv/client.h>
#include <android/log.h>
#include <locale.h>
#include <string>

#define TAG "StreamX_MPV"

static mpv_handle* mpv_ctx = nullptr;

void init_mpv_engine() {
    setlocale(LC_NUMERIC, "C");
    if (!mpv_ctx) {
        mpv_ctx = mpv_create();
        if (mpv_ctx) {
            mpv_set_option_string(mpv_ctx, "vo", "gpu");
            mpv_set_option_string(mpv_ctx, "gpu-api", "opengl"); 
            mpv_set_option_string(mpv_ctx, "hwdec", "auto");
            
            // FIX: Enable Aggressive Cache for Torrent Streaming
            mpv_set_option_string(mpv_ctx, "cache", "yes");
            mpv_set_option_string(mpv_ctx, "demuxer-max-bytes", "64MiB");
            mpv_set_option_string(mpv_ctx, "demuxer-readahead-secs", "20");

            mpv_set_option_string(mpv_ctx, "sub-auto", "fuzzy"); 
            mpv_set_option_string(mpv_ctx, "sub-ass-override", "force"); 
            mpv_set_option_string(mpv_ctx, "sub-font-size", "45");
            mpv_initialize(mpv_ctx);
            __android_log_print(ANDROID_LOG_DEBUG, TAG, "MPV Engine Initialized Modularly with Caching");
        }
    }
}

void play_mpv_video(const char* path) {
    if (!mpv_ctx) return;
    const char* cmd[] = {"loadfile", path, NULL};
    mpv_command(mpv_ctx, cmd);
}

void set_mpv_surface(ANativeWindow* window) {
    if (!mpv_ctx) return;
    int64_t wid = (int64_t)window;
    mpv_set_property(mpv_ctx, "wid", MPV_FORMAT_INT64, &wid);
}

void toggle_vulkan_fsr(bool enable) {
    if (!mpv_ctx) return;
    if (enable) {
        mpv_set_option_string(mpv_ctx, "scale", "ewa_lanczossharp");
        mpv_set_option_string(mpv_ctx, "cscale", "ewa_lanczossharp");
    } else {
        mpv_set_option_string(mpv_ctx, "scale", "bilinear");
        mpv_set_option_string(mpv_ctx, "cscale", "bilinear");
    }
}

double get_mpv_time() {
    if (!mpv_ctx) return 0.0;
    double time_pos = 0.0;
    mpv_get_property(mpv_ctx, "time-pos", MPV_FORMAT_DOUBLE, &time_pos);
    return time_pos;
}

double get_mpv_duration() {
    if (!mpv_ctx) return 0.0;
    double duration = 0.0;
    mpv_get_property(mpv_ctx, "duration", MPV_FORMAT_DOUBLE, &duration);
    return duration;
}

void seek_mpv_video(double seconds) {
    if (!mpv_ctx) return;
    std::string sec_str = std::to_string(seconds);
    const char* cmd[] = {"seek", sec_str.c_str(), "relative", NULL};
    mpv_command(mpv_ctx, cmd);
}

void pause_mpv_video(bool pause) {
    if (!mpv_ctx) return;
    int pause_val = pause ? 1 : 0;
    mpv_set_property(mpv_ctx, "pause", MPV_FORMAT_FLAG, &pause_val);
}

void command_mpv(const char** args) {
    if (!mpv_ctx) return;
    mpv_command(mpv_ctx, args);
}

void set_property_string_mpv(const char* name, const char* value) {
    if (!mpv_ctx) return;
    mpv_set_property_string(mpv_ctx, name, value);
}
