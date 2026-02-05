cmake_minimum_required(VERSION 3.22.1)

project("streamx-native")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# --- 1. Whisper.cpp (AI Engine) Fetch করা ---
include(FetchContent)
FetchContent_Declare(
    whisper_cpp
    GIT_REPOSITORY https://github.com/ggerganov/whisper.cpp.git
    GIT_TAG master
)
FetchContent_MakeAvailable(whisper_cpp)

add_compile_options(-Wno-macro-redefined)

# --- 2. Rust Library Import (Crucial Change) ---
# Rust যে .so ফাইল তৈরি করবে (libstreamx_core.so), সেটি এখানে লিঙ্ক করতে হবে
add_library(streamx_core SHARED IMPORTED)
set_target_properties(streamx_core PROPERTIES IMPORTED_LOCATION 
    ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libstreamx_core.so)

# --- 3. Define C++ Libraries ---
add_library(
        streamx-native
        SHARED
        native-lib.cpp
        torrent-engine.cpp
        ai_engine.cpp
)

# Dependencies খোঁজা
find_package(LibtorrentRasterbar CONFIG REQUIRED)
find_package(OpenSSL REQUIRED)
find_package(Boost REQUIRED COMPONENTS system)

# --- 4. Link Everything Together ---
target_link_libraries(
        streamx-native
        PRIVATE
        streamx_core        # Rust লাইব্রেরি লিঙ্ক করা হলো
        LibtorrentRasterbar::torrent-rasterbar
        OpenSSL::SSL
        OpenSSL::Crypto
        Boost::system
        whisper             # Whisper AI লিঙ্ক করা হলো
        log
        android             # অডিও প্রসেসিং এর জন্য
        atomic
)
