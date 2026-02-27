cmake_minimum_required(VERSION 3.22.1)
project("streamx-native")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ABI অনুযায়ী Rust টার্গেট সেটআপ
if(${ANDROID_ABI} STREQUAL "arm64-v8a")
    set(RUST_TARGET_ARCH "aarch64-linux-android")
elseif(${ANDROID_ABI} STREQUAL "armeabi-v7a")
    set(RUST_TARGET_ARCH "armv7-linux-androideabi")
elseif(${ANDROID_ABI} STREQUAL "x86_64")
    set(RUST_TARGET_ARCH "x86_64-linux-android")
elseif(${ANDROID_ABI} STREQUAL "x86")
    set(RUST_TARGET_ARCH "i686-linux-android")
endif()

set(RUST_LIB_FILE "${RUST_BUILD_DIR}/${RUST_TARGET_ARCH}/release/libstreamx_core.a")
add_library(streamx_core STATIC IMPORTED)
set_target_properties(streamx_core PROPERTIES IMPORTED_LOCATION "${RUST_LIB_FILE}")

# TMDB API Key ডিফাইন
if (DEFINED TMDB_API_KEY)
    add_definitions(-DTMDB_API_KEY="${TMDB_API_KEY}")
endif()

# --- ✅ FIX: Auto-Download MPV Headers ---
# যদি GitHub Actions হেডার ফাইল কপি করতে ফেইল করে, CMake নিজে ডাউনলোড করে নিবে (Zero failure)
set(MPV_INCLUDE_DIR "${CMAKE_SOURCE_DIR}/include/mpv")
if(NOT EXISTS "${MPV_INCLUDE_DIR}/client.h")
    message(STATUS "⚠️ mpv headers not found! Downloading them automatically...")
    file(MAKE_DIRECTORY "${MPV_INCLUDE_DIR}")
    file(DOWNLOAD "https://raw.githubusercontent.com/mpv-player/mpv/master/libmpv/client.h" "${MPV_INCLUDE_DIR}/client.h")
    file(DOWNLOAD "https://raw.githubusercontent.com/mpv-player/mpv/master/libmpv/render.h" "${MPV_INCLUDE_DIR}/render.h")
    file(DOWNLOAD "https://raw.githubusercontent.com/mpv-player/mpv/master/libmpv/render_gl.h" "${MPV_INCLUDE_DIR}/render_gl.h")
    file(DOWNLOAD "https://raw.githubusercontent.com/mpv-player/mpv/master/libmpv/stream_cb.h" "${MPV_INCLUDE_DIR}/stream_cb.h")
    message(STATUS "✅ mpv headers downloaded successfully.")
endif()

include_directories(${CMAKE_SOURCE_DIR}/include)

add_library(
        streamx-native
        SHARED
        native-lib.cpp
        torrent-engine.cpp 
)

target_include_directories(streamx-native PRIVATE ${CMAKE_SOURCE_DIR})

# --- ✅ MPV & FFmpeg Setup ---
# ১. libmpv.so ইমপোর্ট
add_library(libmpv SHARED IMPORTED)
set_target_properties(libmpv PROPERTIES IMPORTED_LOCATION 
    ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libmpv.so)

# ২. FFmpeg লাইব্রেরি ইমপোর্ট (Crash-Proof Fix)
set(FFMPEG_LIBS avcodec avdevice avfilter avformat avutil swresample swscale)
set(FFMPEG_ALL_LIBS "")

foreach(LIB ${FFMPEG_LIBS})
    set(LIB_PATH "${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/lib${LIB}.so")
    # FIX: চেক করবে ফাইলগুলো ফিজিক্যালি আছে কিনা, না থাকলে Ninja ক্র্যাশ করবে না।
    if(EXISTS "${LIB_PATH}")
        add_library(lib${LIB} SHARED IMPORTED)
        set_target_properties(lib${LIB} PROPERTIES IMPORTED_LOCATION "${LIB_PATH}")
        list(APPEND FFMPEG_ALL_LIBS lib${LIB})
    else()
        message(WARNING "⚠️ Missing FFmpeg Lib: ${LIB_PATH}. Skipping link.")
    endif()
endforeach()

# --- অন্যান্য লাইব্রেরি ---
find_package(LibtorrentRasterbar CONFIG REQUIRED)
find_package(OpenSSL REQUIRED)
find_package(Boost REQUIRED COMPONENTS system)

find_library(log-lib log)
find_library(android-lib android)
find_library(vulkan-lib vulkan)

# --- ✅ Final Linking ---
target_link_libraries(
        streamx-native
        streamx_core
        libmpv
        ${FFMPEG_ALL_LIBS}
        LibtorrentRasterbar::torrent-rasterbar
        OpenSSL::SSL
        OpenSSL::Crypto
        Boost::system
        ${log-lib}
        ${android-lib}
        ${vulkan-lib}
)
