cmake_minimum_required(VERSION 3.22.1)
project("streamx-native")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

<<<<<<< HEAD
# --- 1. Vosk API (AI Engine) Fetch ---
# Vosk সরাসরি প্রি-বিল্ড Android লাইব্রেরি দেয়, তাই বিল্ড টাইম অনেক কম লাগবে
include(FetchContent)
FetchContent_Declare(
    vosk_android
    URL https://github.com/alphacep/vosk-api/releases/download/0.3.50/vosk-android-lib-0.3.50.zip
)
FetchContent_MakeAvailable(vosk_android)
=======
# ১. Sherpa-ONNX C-API লাইব্রেরি লোড করা
add_library(sherpa-onnx-c SHARED IMPORTED)
set_target_properties(sherpa-onnx-c PROPERTIES IMPORTED_LOCATION 
    "${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libsherpa-onnx-c-api.so")

# ২. ONNX Runtime লাইব্রেরি লোড করা
add_library(onnxruntime SHARED IMPORTED)
set_target_properties(onnxruntime PROPERTIES IMPORTED_LOCATION 
    "${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libonnxruntime.so")
>>>>>>> 2c69217 (feat: integrate Sherpa-ONNX AI engine with Rust security bridge)

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
    add_definitions(-DTMDB_API_KEY="${TMDB_API_KEY}")
    message(STATUS "TMDB API Key injected into CMake")
endif()

# --- 3. Define Sherpa-ONNX Library Path ---
set(SHERPA_LIB_PATH "${sherpa_onnx_SOURCE_DIR}/jniLibs/${ANDROID_ABI}/libsherpa-onnx-c-api.so")
set(ONNX_LIB_PATH "${sherpa_onnx_SOURCE_DIR}/jniLibs/${ANDROID_ABI}/libonnxruntime.so")

add_library(sherpa SHARED IMPORTED)
set_target_properties(sherpa PROPERTIES IMPORTED_LOCATION ${SHERPA_LIB_PATH})

add_library(onnxruntime SHARED IMPORTED)
set_target_properties(onnxruntime PROPERTIES IMPORTED_LOCATION ${ONNX_LIB_PATH})

include_directories("${sherpa_onnx_SOURCE_DIR}/headers")

# --- 4. Define C++ Libraries ---
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
        sherpa-onnx-c
        onnxruntime
        log
        android
        LibtorrentRasterbar::torrent-rasterbar
        OpenSSL::SSL
        OpenSSL::Crypto
        Boost::system
)
