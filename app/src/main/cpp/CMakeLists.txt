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

# ── FFmpeg Shared Libraries ───────────────────────────────────────────
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
#  vcpkg OpenSSL — manual configuration for Android cross-build
#
#  WHY: CMake's FindOpenSSL module cannot auto-detect vcpkg's Android
#  OpenSSL because CMAKE_SYSROOT prefixes all search paths, and vcpkg
#  uses custom triplets (arm64-android, arm-android, etc.).
#
#  BUG FIXED: The previous check used EXISTS "...lib/libssl.so" which
#  ALWAYS FAILS because vcpkg builds STATIC (.a) libraries for Android
#  by default — it never produces .so files for Android triplets.
#  This caused every ABI to fall through to find_package(OpenSSL REQUIRED)
#  which then failed on the cross-compiled Android sysroot.
#
#  FIX: Probe for .a first (vcpkg Android default), fall back to .so.
#
#  Priority order for locating vcpkg root:
#   0. CMake var OPENSSL_ROOT_DIR_<ABI>  (passed by build.gradle.kts)
#   1. ENV{VCPKG_ROOT}/installed/<triplet>  (CI: run-vcpkg action)
#   2. Project-relative vcpkg/installed/<triplet>  (local dev)
#   3. VCPKG_INSTALLED_DIR/<triplet>  (vcpkg toolchain integration)
# ═══════════════════════════════════════════════════════════════════════

# Map Android ABI → vcpkg triplet name
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

# Resolve vcpkg OpenSSL root — try all 4 sources in priority order
set(VCPKG_OPENSSL_ROOT "")

# Priority 0: CMake variable passed from build.gradle.kts
#   e.g. -DOPENSSL_ROOT_DIR_arm64-v8a=/path/to/vcpkg/installed/arm64-android
set(_GRADLE_OPENSSL_ROOT_VAR "OPENSSL_ROOT_DIR_${ANDROID_ABI}")
if(DEFINED ${_GRADLE_OPENSSL_ROOT_VAR} AND EXISTS "${${_GRADLE_OPENSSL_ROOT_VAR}}")
    set(VCPKG_OPENSSL_ROOT "${${_GRADLE_OPENSSL_ROOT_VAR}}")
    message(STATUS "🔑 vcpkg OpenSSL found via Gradle CMake arg (${_GRADLE_OPENSSL_ROOT_VAR}): ${VCPKG_OPENSSL_ROOT}")

# Priority 1: VCPKG_ROOT env variable (set by CI run-vcpkg action or user)
elseif(DEFINED ENV{VCPKG_ROOT} AND EXISTS "$ENV{VCPKG_ROOT}/installed/${VCPKG_TRIPLET}")
    set(VCPKG_OPENSSL_ROOT "$ENV{VCPKG_ROOT}/installed/${VCPKG_TRIPLET}")
    message(STATUS "🔑 vcpkg OpenSSL found via ENV{VCPKG_ROOT}: ${VCPKG_OPENSSL_ROOT}")

# Priority 2: Project-relative vcpkg (local dev without env var)
elseif(EXISTS "${CMAKE_SOURCE_DIR}/../../../vcpkg/installed/${VCPKG_TRIPLET}")
    set(VCPKG_OPENSSL_ROOT "${CMAKE_SOURCE_DIR}/../../../vcpkg/installed/${VCPKG_TRIPLET}")
    message(STATUS "🔑 vcpkg OpenSSL found at project-relative path: ${VCPKG_OPENSSL_ROOT}")

# Priority 3: VCPKG_INSTALLED_DIR (vcpkg CMake toolchain integration)
elseif(DEFINED VCPKG_INSTALLED_DIR AND EXISTS "${VCPKG_INSTALLED_DIR}/${VCPKG_TRIPLET}")
    set(VCPKG_OPENSSL_ROOT "${VCPKG_INSTALLED_DIR}/${VCPKG_TRIPLET}")
    message(STATUS "🔑 vcpkg OpenSSL found via VCPKG_INSTALLED_DIR: ${VCPKG_OPENSSL_ROOT}")
endif()

# ── Probe for OpenSSL libraries — .a (static) first, .so (shared) fallback ──
# vcpkg ALWAYS builds static .a for Android triplets (arm64-android etc.).
# The old code checked for .so here which ALWAYS FAILED — that was the bug.
set(OPENSSL_FOUND FALSE)

if(VCPKG_OPENSSL_ROOT)
    set(_SSL_STATIC    "${VCPKG_OPENSSL_ROOT}/lib/libssl.a")
    set(_SSL_SHARED    "${VCPKG_OPENSSL_ROOT}/lib/libssl.so")
    set(_CRYPTO_STATIC "${VCPKG_OPENSSL_ROOT}/lib/libcrypto.a")
    set(_CRYPTO_SHARED "${VCPKG_OPENSSL_ROOT}/lib/libcrypto.so")

    # Prefer .a (static) — vcpkg Android default
    if(EXISTS "${_SSL_STATIC}")
        set(_SSL_LIB    "${_SSL_STATIC}")
        set(_CRYPTO_LIB "${_CRYPTO_STATIC}")
        message(STATUS "🔒 vcpkg OpenSSL: using static libs (.a) for ${ANDROID_ABI}")
    elseif(EXISTS "${_SSL_SHARED}")
        set(_SSL_LIB    "${_SSL_SHARED}")
        set(_CRYPTO_LIB "${_CRYPTO_SHARED}")
        message(STATUS "🔓 vcpkg OpenSSL: using shared libs (.so) for ${ANDROID_ABI}")
    endif()

    # Verify include directory is also present before declaring FOUND
    if(DEFINED _SSL_LIB AND EXISTS "${VCPKG_OPENSSL_ROOT}/include/openssl/ssl.h")
        set(OPENSSL_ROOT_DIR       "${VCPKG_OPENSSL_ROOT}")
        set(OPENSSL_INCLUDE_DIR    "${VCPKG_OPENSSL_ROOT}/include")
        set(OPENSSL_SSL_LIBRARY    "${_SSL_LIB}")
        set(OPENSSL_CRYPTO_LIBRARY "${_CRYPTO_LIB}")
        set(OPENSSL_LIBRARIES      "${OPENSSL_SSL_LIBRARY}" "${OPENSSL_CRYPTO_LIBRARY}")
        set(OPENSSL_FOUND TRUE)

        list(APPEND CMAKE_FIND_ROOT_PATH "${VCPKG_OPENSSL_ROOT}")
        include_directories(${OPENSSL_INCLUDE_DIR})

        message(STATUS "✅ OpenSSL manually configured for ${ANDROID_ABI}")
        message(STATUS "   Root:    ${OPENSSL_ROOT_DIR}")
        message(STATUS "   Include: ${OPENSSL_INCLUDE_DIR}")
        message(STATUS "   SSL:     ${OPENSSL_SSL_LIBRARY}")
        message(STATUS "   Crypto:  ${OPENSSL_CRYPTO_LIBRARY}")
    else()
        message(STATUS "⚠️  vcpkg root resolved but libs/headers missing in: ${VCPKG_OPENSSL_ROOT}")
        message(STATUS "   Checked: ${_SSL_STATIC} | ${_SSL_SHARED}")
        message(STATUS "   Checked: ${VCPKG_OPENSSL_ROOT}/include/openssl/ssl.h")
    endif()
else()
    message(STATUS "⚠️  vcpkg root not found for ABI=${ANDROID_ABI} triplet=${VCPKG_TRIPLET}")
    message(STATUS "   Set VCPKG_ROOT env var or pass -DOPENSSL_ROOT_DIR_${ANDROID_ABI}=<path>")
endif()

# ── System dependencies ───────────────────────────────────────────────
find_library(log-lib log)
find_library(android-lib android)

# Only fall back to find_package if manual vcpkg config above failed.
# NOTE: find_package(OpenSSL) almost always fails on Android cross-builds
# because CMake's sysroot interferes with library search paths.
if(NOT OPENSSL_FOUND)
    message(WARNING "⚠️  Manual vcpkg OpenSSL config failed for ${ANDROID_ABI}. "
                    "Falling back to find_package — this will likely FAIL on Android. "
                    "Ensure VCPKG_ROOT is set and 'vcpkg install openssl:${VCPKG_TRIPLET}' was run.")
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
