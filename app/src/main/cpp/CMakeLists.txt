# app/src/main/cpp/CMakeLists.txt
# ══════════════════════════════════════════════════════════════════════
#  StreamX Native Build  —  v3  (Rust Core integrated)
#
#  CHANGES from v2:
#    REMOVED:  libtorrent (vcpkg), OpenSSL, Boost
#              torrent-engine.cpp  torrent_system.hpp
#    KEPT:     MPV + FFmpeg  (mpv_handler.cpp, native-lib.cpp)
#    RUST:     libstreamx_core.a linked via --whole-archive
#              Torrent JNI now lives in Rust
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

# ── Rust static library ───────────────────────────────────────────────
set(RUST_LIB "${RUST_BUILD_DIR}/${RUST_TRIPLE}/release/libstreamx_core.a")
add_library(streamx_core STATIC IMPORTED)
set_target_properties(streamx_core PROPERTIES IMPORTED_LOCATION "${RUST_LIB}")

# ── TMDB key ──────────────────────────────────────────────────────────
if(DEFINED TMDB_API_KEY)
    add_definitions(-DTMDB_API_KEY="${TMDB_API_KEY}")
endif()

# ── MPV headers ───────────────────────────────────────────────────────
set(MPV_INCLUDE_DIR "${CMAKE_SOURCE_DIR}/include/mpv")
if(NOT EXISTS "${MPV_INCLUDE_DIR}/client.h")
    file(MAKE_DIRECTORY "${MPV_INCLUDE_DIR}")
    foreach(header client render render_gl stream_cb)
        file(DOWNLOAD
            "https://raw.githubusercontent.com/mpv-player/mpv/master/include/mpv/${header}.h"
            "${MPV_INCLUDE_DIR}/${header}.h")
    endforeach()
endif()
include_directories("${CMAKE_SOURCE_DIR}/include")
include_directories("${CMAKE_SOURCE_DIR}")

# ── Main shared library ───────────────────────────────────────────────
# REMOVED: torrent-engine.cpp (Rust handles torrent now)
# KEPT:    native-lib.cpp (MPV JNI only), mpv_handler.cpp
add_library(streamx-native SHARED
    native-lib.cpp
    mpv_handler.cpp
)

# ── MPV + FFmpeg shared libs ──────────────────────────────────────────
add_library(libmpv SHARED IMPORTED)
set_target_properties(libmpv PROPERTIES
    IMPORTED_LOCATION "${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libmpv.so")

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

# ── Link everything together ──────────────────────────────────────────
# --whole-archive ensures Rust JNI functions are not dead-stripped
target_link_libraries(streamx-native
    -Wl,--whole-archive
    streamx_core
    -Wl,--no-whole-archive
    libmpv
    ${FFMPEG_ALL}
    ${log-lib}
    ${android-lib}
)
