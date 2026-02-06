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

# --- 2. Rust Library Import (FIXED & DYNAMIC) ---

# Map Android ABI to Rust Triple
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

# Check for RUST_BUILD_DIR from Gradle
if (NOT DEFINED RUST_BUILD_DIR)
    message(FATAL_ERROR "RUST_BUILD_DIR is not defined. Check build.gradle.kts")
endif()

# --- SMART PATH DETECTION ---
# আমরা চেক করব এখন Debug নাকি Release বিল্ড চলছে
if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    set(RUST_PROFILE "debug")
else()
    set(RUST_PROFILE "release")
endif()

# সম্ভাব্য পাথগুলো সেট করা
set(RUST_LIB_PATH_CURRENT "${RUST_BUILD_DIR}/${RUST_TARGET_ARCH}/${RUST_PROFILE}/libstreamx_core.a")
set(RUST_LIB_PATH_RELEASE "${RUST_BUILD_DIR}/${RUST_TARGET_ARCH}/release/libstreamx_core.a")
set(RUST_LIB_PATH_DEBUG   "${RUST_BUILD_DIR}/${RUST_TARGET_ARCH}/debug/libstreamx_core.a")

# লজিক: 
# ১. বর্তমান প্রোফাইল (Debug/Release) অনুযায়ী খুঁজবে।
# ২. না পেলে Release ফোল্ডারে খুঁজবে (Fallback)।
# ৩. তাও না পেলে Debug ফোল্ডারে খুঁজবে।

if(EXISTS "${RUST_LIB_PATH_CURRENT}")
    set(RUST_LIB_FILE "${RUST_LIB_PATH_CURRENT}")
elseif(EXISTS "${RUST_LIB_PATH_RELEASE}")
    message(STATUS "Requested ${RUST_PROFILE} lib not found, falling back to Release lib.")
    set(RUST_LIB_FILE "${RUST_LIB_PATH_RELEASE}")
elseif(EXISTS "${RUST_LIB_PATH_DEBUG}")
    message(STATUS "Requested ${RUST_PROFILE} lib not found, falling back to Debug lib.")
    set(RUST_LIB_FILE "${RUST_LIB_PATH_DEBUG}")
else()
    # যদি কিছুই না পাওয়া যায়, তবে এরর দেখানোর জন্য ডিফল্ট পাথ সেট করি
    set(RUST_LIB_FILE "${RUST_LIB_PATH_CURRENT}")
    message(WARNING "Could not find rust library at ${RUST_LIB_FILE}. Build might fail.")
endif()

message(STATUS "Looking for Rust Lib at: ${RUST_LIB_FILE}")

# Import the library
add_library(streamx_core STATIC IMPORTED)
set_target_properties(streamx_core PROPERTIES IMPORTED_LOCATION "${RUST_LIB_FILE}")

# --- 3. Define C++ Libraries ---
add_library(
        streamx-native
        SHARED
        native-lib.cpp
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
        # Force include Rust symbols
        -Wl,--whole-archive streamx_core -Wl,--no-whole-archive
        
        whisper
        log
        android
        LibtorrentRasterbar::libtorrent-rasterbar
        OpenSSL::SSL
        OpenSSL::Crypto
        Boost::system
)
