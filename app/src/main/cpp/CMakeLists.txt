cmake_minimum_required(VERSION 3.22.1)
project("streamx-native")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# --- 1. Vosk API (AI Engine) Fetch ---
# Vosk সরাসরি প্রি-বিল্ড Android লাইব্রেরি দেয়, তাই বিল্ড টাইম অনেক কম লাগবে
include(FetchContent)
FetchContent_Declare(
    vosk_android
    URL https://github.com/alphacep/vosk-api/releases/download/0.3.50/vosk-android-lib-0.3.50.zip
)
FetchContent_MakeAvailable(vosk_android)

# --- 2. Rust Library Import (No Changes Here!) ---
if(${ANDROID_ABI} STREQUAL "arm64-v8a")
    set(RUST_TARGET_ARCH "aarch64-linux-android")
elseif(${ANDROID_ABI} STREQUAL "armeabi-v7a")
    set(RUST_TARGET_ARCH "armv7-linux-androideabi")
elseif(${ANDROID_ABI} STREQUAL "x86_64")
    set(RUST_TARGET_ARCH "x86_64-linux-android")
elseif(${ANDROID_ABI} STREQUAL "x86")
    set(RUST_TARGET_ARCH "i686-linux-android")
else()
    message(FATAL_ERROR "Unsupported Architecture: ${ANDROID_ABI}")
endif()

if (NOT DEFINED RUST_BUILD_DIR)
    message(FATAL_ERROR "RUST_BUILD_DIR is not defined. Check build.gradle.kts")
endif()

set(RUST_LIB_FILE "${RUST_BUILD_DIR}/${RUST_TARGET_ARCH}/release/libstreamx_core.a")
add_library(streamx_core STATIC IMPORTED)
set_target_properties(streamx_core PROPERTIES IMPORTED_LOCATION "${RUST_LIB_FILE}")


# GitHub Secrets থেকে আসা TMDB_API_KEY-টি Rust-এর কাছে পাঠানো
if (DEFINED TMDB_API_KEY)
    # এটি Rust কম্পাইলারকে বলবে যে একটি এনভায়রনমেন্ট ভেরিয়েবল সেট করতে হবে
    add_definitions(-DTMDB_API_KEY="${TMDB_API_KEY}")
    message(STATUS "TMDB API Key injected into CMake")
endif()

# --- 3. Define Vosk Library Path ---
set(VOSK_LIB_PATH "${vosk_android_SOURCE_DIR}/lib/${ANDROID_ABI}/libvosk.so")
add_library(vosk SHARED IMPORTED)
set_target_properties(vosk PROPERTIES IMPORTED_LOCATION ${VOSK_LIB_PATH})

# --- 4. Define C++ Libraries ---
include_directories("${vosk_android_SOURCE_DIR}/include")

add_library(
        streamx-native
        SHARED
        native-lib.cpp
        ai_engine.cpp
        torrent-engine.cpp 
)

# Find Dependencies
find_package(LibtorrentRasterbar CONFIG REQUIRED)
find_package(OpenSSL REQUIRED)
find_package(Boost REQUIRED COMPONENTS system)

# --- 5. Link Everything Together ---
target_link_libraries(
        streamx-native
        PRIVATE
        -Wl,--whole-archive streamx_core -Wl,--no-whole-archive
        vosk   # <--- Link Vosk instead of Whisper
        log
        android
        LibtorrentRasterbar::torrent-rasterbar
        OpenSSL::SSL
        OpenSSL::Crypto
        Boost::system
)
