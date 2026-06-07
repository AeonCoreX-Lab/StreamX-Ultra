# app/src/main/cpp/CMakeLists.txt
# ══════════════════════════════════════════════════════════════════════
#  StreamX Native Build  —  v4  (vcpkg FFmpeg + Rust Core)
#
#  CHANGES from v3:
#    ADDED:   RUST_BUILD_TYPE var (debug|release) passed from Gradle
#    CHANGED: FFmpeg .so comes from vcpkg (copied to jniLibs by CI)
#    FIXED:   RUST_LIB path now respects debug vs release
# ══════════════════════════════════════════════════════════════════════

cmake_minimum_required(VERSION 3.22.1)
project("streamx-native")
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ── ABI → Rust target triple ──────────────────────────────────────────
if    (ANDROID_ABI STREQUAL "arm64-v8a")
    set(RUST_TRIPLE "aarch64-linux-android")
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(RUST_TRIPLE "armv7-linux-androideabi")
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(RUST_TRIPLE "x86_64-linux-android")
elseif(ANDROID_ABI STREQUAL "x86")
    set(RUST_TRIPLE "i686-linux-android")
endif()

# ── Rust build type ───────────────────────────────────────────────────
# Passed from build.gradle.kts via -DRUST_BUILD_TYPE=debug|release
# Debug builds: faster cargo build (no LTO), only arm64-v8a
# Release builds: full optimised cargo build, all 4 ABIs
if(NOT DEFINED RUST_BUILD_TYPE)
    set(RUST_BUILD_TYPE "release")
endif()
message(STATUS "StreamX: RUST_BUILD_TYPE = ${RUST_BUILD_TYPE}")

# ── Rust static library ───────────────────────────────────────────────
# Path: build/rust/targets/{triple}/{debug|release}/libstreamx_core.a
set(RUST_LIB "${RUST_BUILD_DIR}/${RUST_TRIPLE}/${RUST_BUILD_TYPE}/libstreamx_core.a")
add_library(streamx_core STATIC IMPORTED)
set_target_properties(streamx_core PROPERTIES IMPORTED_LOCATION "${RUST_LIB}")

# ── TMDB key ──────────────────────────────────────────────────────────
if(DEFINED TMDB_API_KEY)
    add_definitions(-DTMDB_API_KEY="${TMDB_API_KEY}")
endif()

# ── MPV headers ───────────────────────────────────────────────────────
# Headers are downloaded by CI into app/src/main/cpp/include/mpv/
# Fallback: download at CMake configure time (local dev only)
set(MPV_INCLUDE_DIR "${CMAKE_SOURCE_DIR}/include/mpv")
if(NOT EXISTS "${MPV_INCLUDE_DIR}/client.h")
    message(STATUS "StreamX: mpv headers missing — downloading...")
    file(MAKE_DIRECTORY "${MPV_INCLUDE_DIR}")
    foreach(header client render render_gl stream_cb)
        file(DOWNLOAD
            "https://raw.githubusercontent.com/mpv-player/mpv/master/include/mpv/${header}.h"
            "${MPV_INCLUDE_DIR}/${header}.h"
            STATUS dl_status)
    endforeach()
endif()

include_directories("${CMAKE_SOURCE_DIR}/include")
include_directories("${CMAKE_SOURCE_DIR}")

# ── Main shared library ───────────────────────────────────────────────
# torrent-engine.cpp removed — torrent JNI now lives in Rust (streamx_core.a)
add_library(streamx-native SHARED
    native-lib.cpp
    mpv_handler.cpp
)

# ── MPV shared lib ────────────────────────────────────────────────────
# libmpv.so is built by mpv-android buildscripts in CI and placed in jniLibs/
add_library(libmpv SHARED IMPORTED)
set_target_properties(libmpv PROPERTIES
    IMPORTED_LOCATION "${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libmpv.so")

# ── FFmpeg shared libs ────────────────────────────────────────────────
# Previously: built by mpv-android buildscripts from source (slow, uncached)
# NOW: built by vcpkg for each Android ABI and copied to jniLibs/ by CI
# vcpkg binary cache makes this near-instant on cache hit
set(FFMPEG_LIBS avcodec avdevice avfilter avformat avutil swresample swscale)
set(FFMPEG_ALL "")
foreach(LIB ${FFMPEG_LIBS})
    set(LIB_PATH "${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/lib${LIB}.so")
    if(EXISTS "${LIB_PATH}")
        add_library(lib${LIB} SHARED IMPORTED)
        set_target_properties(lib${LIB} PROPERTIES IMPORTED_LOCATION "${LIB_PATH}")
        list(APPEND FFMPEG_ALL lib${LIB})
    endif()
endforeach()

find_library(log-lib     log)
find_library(android-lib android)

# ── Link everything ───────────────────────────────────────────────────
# --whole-archive keeps Rust JNI symbols from being dead-stripped
target_link_libraries(streamx-native
    -Wl,--whole-archive
    streamx_core
    -Wl,--no-whole-archive
    libmpv
    ${FFMPEG_ALL}
    ${log-lib}
    ${android-lib}
)
