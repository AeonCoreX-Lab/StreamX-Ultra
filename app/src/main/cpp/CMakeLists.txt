cmake_minimum_required(VERSION 3.22.1)
project("streamx-native")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ── ABI → Rust target triple ──────────────────────────────────────────
if(${ANDROID_ABI} STREQUAL "arm64-v8a")
    set(RUST_TARGET_ARCH "aarch64-linux-android")
elseif(${ANDROID_ABI} STREQUAL "armeabi-v7a")
    set(RUST_TARGET_ARCH "armv7-linux-androideabi")
elseif(${ANDROID_ABI} STREQUAL "x86_64")
    set(RUST_TARGET_ARCH "x86_64-linux-android")
elseif(${ANDROID_ABI} STREQUAL "x86")
    set(RUST_TARGET_ARCH "i686-linux-android")
endif()

# Dynamic build type matching (defaults to release if not explicitly provided)
if(NOT DEFINED RUST_BUILD_TYPE)
    set(RUST_BUILD_TYPE "release")
endif()

# ── Rust static library ───────────────────────────────────────────────
set(RUST_LIB_FILE "${RUST_BUILD_DIR}/${RUST_TARGET_ARCH}/${RUST_BUILD_TYPE}/libstreamx_core.a")
add_library(streamx_core STATIC IMPORTED)
set_target_properties(streamx_core PROPERTIES IMPORTED_LOCATION "${RUST_LIB_FILE}")

# ── TMDB key injection ────────────────────────────────────────────────
if (DEFINED TMDB_API_KEY)
    add_definitions(-DTMDB_API_KEY="${TMDB_API_KEY}")
    message(STATUS "TMDB API Key injected into CMake")
endif()

# ── MPV and Internal Include Paths ────────────────────────────────────
set(MPV_INCLUDE_BASE "${CMAKE_SOURCE_DIR}/include")
set(MPV_INCLUDE_DIR "${MPV_INCLUDE_BASE}/mpv")

if(NOT EXISTS "${MPV_INCLUDE_DIR}/client.h")
    message(STATUS "⚠️ mpv headers not found locally! Downloading them automatically...")
    file(MAKE_DIRECTORY "${MPV_INCLUDE_DIR}")
    file(DOWNLOAD "https://raw.githubusercontent.com/mpv-player/mpv/master/include/mpv/client.h" "${MPV_INCLUDE_DIR}/client.h")
    file(DOWNLOAD "https://raw.githubusercontent.com/mpv-player/mpv/master/include/mpv/render.h" "${MPV_INCLUDE_DIR}/render.h")
    file(DOWNLOAD "https://raw.githubusercontent.com/mpv-player/mpv/master/include/mpv/render_gl.h" "${MPV_INCLUDE_DIR}/render_gl.h")
    file(DOWNLOAD "https://raw.githubusercontent.com/mpv-player/mpv/master/include/mpv/stream_cb.h" "${MPV_INCLUDE_DIR}/stream_cb.h")
    message(STATUS "✅ mpv headers downloaded successfully.")
endif()

# Include global project directories and internal headers folder
include_directories(${MPV_INCLUDE_BASE})
include_directories(${CMAKE_SOURCE_DIR})

# ── Main Shared Library ───────────────────────────────────────────────
add_library(
        streamx-native
        SHARED
        native-lib.cpp
        mpv_handler.cpp
)

# Crucial: Ensures your main source code can easily read your custom headers 
target_include_directories(streamx-native PRIVATE ${CMAKE_SOURCE_DIR} ${MPV_INCLUDE_BASE})

# ── MPV Shared Library ────────────────────────────────────────────────
add_library(libmpv SHARED IMPORTED)
set_target_properties(libmpv PROPERTIES IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libmpv.so)

# ── FFmpeg Shared Libraries (Restored exactly from old CMake) ─────────
set(FFMPEG_LIBS avcodec avdevice avfilter avformat avutil swresample swscale)
set(FFMPEG_ALL_LIBS "")

foreach(LIB ${FFMPEG_LIBS})
    set(LIB_PATH "${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/lib${LIB}.so")
    if(EXISTS "${LIB_PATH}")
        add_library(lib${LIB} SHARED IMPORTED)
        set_target_properties(lib${LIB} PROPERTIES IMPORTED_LOCATION "${LIB_PATH}")
        list(APPEND FFMPEG_ALL_LIBS lib${LIB})
    endif()
endforeach()

# ═══════════════════════════════════════════════════════════════════════
#  FIX: vcpkg OpenSSL — CMake find_package fails on Android cross-build
#  Reason: CMake's FindOpenSSL module can't auto-detect vcpkg's Android
#  OpenSSL because CMAKE_SYSROOT prefixes paths and vcpkg uses custom
#  triplets. We manually set OPENSSL_ROOT_DIR per ABI.
# ═══════════════════════════════════════════════════════════════════════

# Map Android ABI → vcpkg triplet
set(VCPKG_TRIPLET "")
if(${ANDROID_ABI} STREQUAL "arm64-v8a")
    set(VCPKG_TRIPLET "arm64-android")
elseif(${ANDROID_ABI} STREQUAL "armeabi-v7a")
    set(VCPKG_TRIPLET "arm-android")
elseif(${ANDROID_ABI} STREQUAL "x86_64")
    set(VCPKG_TRIPLET "x64-android")
elseif(${ANDROID_ABI} STREQUAL "x86")
    set(VCPKG_TRIPLET "x86-android")
endif()

# Try to locate vcpkg OpenSSL
set(VCPKG_OPENSSL_ROOT "")

# Priority 1: VCPKG_ROOT env variable (set by CI or user)
if(DEFINED ENV{VCPKG_ROOT} AND EXISTS "$ENV{VCPKG_ROOT}/installed/${VCPKG_TRIPLET}")
    set(VCPKG_OPENSSL_ROOT "$ENV{VCPKG_ROOT}/installed/${VCPKG_TRIPLET}")
    message(STATUS "🔑 vcpkg OpenSSL found via VCPKG_ROOT: ${VCPKG_OPENSSL_ROOT}")

# Priority 2: Check common vcpkg locations
elseif(EXISTS "${CMAKE_SOURCE_DIR}/../../../vcpkg/installed/${VCPKG_TRIPLET}")
    set(VCPKG_OPENSSL_ROOT "${CMAKE_SOURCE_DIR}/../../../vcpkg/installed/${VCPKG_TRIPLET}")
    message(STATUS "🔑 vcpkg OpenSSL found at project-relative vcpkg: ${VCPKG_OPENSSL_ROOT}")

# Priority 3: Check if vcpkg toolchain already set it
elseif(DEFINED VCPKG_INSTALLED_DIR AND EXISTS "${VCPKG_INSTALLED_DIR}/${VCPKG_TRIPLET}")
    set(VCPKG_OPENSSL_ROOT "${VCPKG_INSTALLED_DIR}/${VCPKG_TRIPLET}")
    message(STATUS "🔑 vcpkg OpenSSL found via VCPKG_INSTALLED_DIR: ${VCPKG_OPENSSL_ROOT}")
endif()

# If found, manually configure OpenSSL paths (bypass find_package auto-detection)
if(VCPKG_OPENSSL_ROOT AND EXISTS "${VCPKG_OPENSSL_ROOT}/lib/libssl.so")
    set(OPENSSL_ROOT_DIR "${VCPKG_OPENSSL_ROOT}")
    set(OPENSSL_INCLUDE_DIR "${VCPKG_OPENSSL_ROOT}/include")
    set(OPENSSL_SSL_LIBRARY "${VCPKG_OPENSSL_ROOT}/lib/libssl.so")
    set(OPENSSL_CRYPTO_LIBRARY "${VCPKG_OPENSSL_ROOT}/lib/libcrypto.so")
    set(OPENSSL_LIBRARIES "${OPENSSL_SSL_LIBRARY}" "${OPENSSL_CRYPTO_LIBRARY}")

    message(STATUS "✅ OpenSSL configured manually for ${ANDROID_ABI}")
    message(STATUS "   OPENSSL_ROOT_DIR: ${OPENSSL_ROOT_DIR}")
    message(STATUS "   OPENSSL_INCLUDE_DIR: ${OPENSSL_INCLUDE_DIR}")
    message(STATUS "   OPENSSL_SSL_LIBRARY: ${OPENSSL_SSL_LIBRARY}")
    message(STATUS "   OPENSSL_CRYPTO_LIBRARY: ${OPENSSL_CRYPTO_LIBRARY}")

    # Add to search path so find_package can locate it
    list(APPEND CMAKE_FIND_ROOT_PATH "${VCPKG_OPENSSL_ROOT}")

    # Also add include path directly
    include_directories(${OPENSSL_INCLUDE_DIR})

    # Set found flag to bypass find_package requirement
    set(OPENSSL_FOUND TRUE)
else()
    message(STATUS "⚠️  vcpkg OpenSSL not found for ${ANDROID_ABI}, falling back to find_package...")
endif()

# ── Dependencies ──────────────────────────────────────────────────────
find_library(log-lib log)
find_library(android-lib android)

# Only call find_package if not already manually configured
if(NOT OPENSSL_FOUND)
    find_package(OpenSSL REQUIRED)
endif()

# ── Link Configuration ────────────────────────────────────────────────
target_link_libraries(
        streamx-native
        -Wl,--whole-archive 
        streamx_core 
        -Wl,--no-whole-archive
        libmpv
        ${FFMPEG_ALL_LIBS}
        ${OPENSSL_LIBRARIES}
        ${log-lib}
        ${android-lib}
)
