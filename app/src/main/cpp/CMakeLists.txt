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
# If RUST_LIB_PATH isn't passed from Gradle, try to guess it (fallback)
if (NOT DEFINED RUST_LIB_PATH)
    message(WARNING "RUST_LIB_PATH not defined via Gradle! Guessing path...")
    set(RUST_LIB_PATH "${CMAKE_BINARY_DIR}/../../../rustJniLibs/android")
endif()

message(STATUS "Linking Rust Library from: ${RUST_LIB_PATH}/${ANDROID_ABI}/libstreamx_core.so")

add_library(streamx_core SHARED IMPORTED)
set_target_properties(streamx_core PROPERTIES IMPORTED_LOCATION 
    ${RUST_LIB_PATH}/${ANDROID_ABI}/libstreamx_core.so)

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
        streamx_core        # Linked Rust Library
        LibtorrentRasterbar::torrent-rasterbar
        OpenSSL::SSL
        OpenSSL::Crypto
        Boost::system
        whisper
        log
        android
        atomic
)
