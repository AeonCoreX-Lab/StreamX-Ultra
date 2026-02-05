cmake_minimum_required(VERSION 3.22.1)

project("streamx-native")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# --- 1. Whisper.cpp (AI Engine) Fetch ---
include(FetchContent)
FetchContent_Declare(
    whisper_cpp
    GIT_REPOSITORY https://github.com/ggerganov/whisper.cpp.git
    GIT_TAG master
)
FetchContent_MakeAvailable(whisper_cpp)

add_compile_options(-Wno-macro-redefined)

# --- 2. Rust Library Import (FIXED) ---
# Map Android ABI to Rust Triple
if(${ANDROID_ABI} STREQUAL "arm64-v8a")
    set(RUST_TARGET_ARCH "aarch64-linux-android")
elseif(${ANDROID_ABI} STREQUAL "armeabi-v7a")
    set(RUST_TARGET_ARCH "armv7-linux-androideabi")
elseif(${ANDROID_ABI} STREQUAL "x86_64")
    set(RUST_TARGET_ARCH "x86_64-linux-android")
else()
    message(FATAL_ERROR "Unsupported Architecture: ${ANDROID_ABI}")
endif()

# Rust output path from build.gradle argument
set(RUST_LIB_FILE "${RUST_BUILD_DIR}/${RUST_TARGET_ARCH}/release/libstreamx_core.a")

# Check if file exists to give better error message
if(NOT EXISTS "${RUST_LIB_FILE}")
    message(WARNING "Rust library not found at: ${RUST_LIB_FILE}. Make sure 'cargoBuild' task runs before CMake.")
endif()

message(STATUS "Linking Rust Static Lib: ${RUST_LIB_FILE}")

add_library(streamx_core STATIC IMPORTED)
set_target_properties(streamx_core PROPERTIES IMPORTED_LOCATION "${RUST_LIB_FILE}")

# --- 3. Define C++ Libraries ---
add_library(
        streamx-native
        SHARED
        native-lib.cpp
        torrent-engine.cpp
        ai_engine.cpp
)

# Find Dependencies
find_package(LibtorrentRasterbar CONFIG REQUIRED)
find_package(OpenSSL REQUIRED)
find_package(Boost REQUIRED COMPONENTS system)

# --- 4. Link Everything Together ---
target_link_libraries(
        streamx-native
        PRIVATE
        # Force include all symbols from Rust static lib to avoid stripping JNI functions
        -Wl,--whole-archive streamx_core -Wl,--no-whole-archive
        
        LibtorrentRasterbar::torrent-rasterbar
        OpenSSL::SSL
        OpenSSL::Crypto
        Boost::system
        whisper
        log
        android
        atomic
)
