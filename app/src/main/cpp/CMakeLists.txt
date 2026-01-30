cmake_minimum_required(VERSION 3.22.1)

project("streamx-native")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# --- 1. Fetch Whisper.cpp (AI Engine) ---
include(FetchContent)
FetchContent_Declare(
    whisper_cpp
    GIT_REPOSITORY https://github.com/ggerganov/whisper.cpp.git
    GIT_TAG master
)
FetchContent_MakeAvailable(whisper_cpp)

add_compile_options(-Wno-macro-redefined)

# --- 2. Define Libraries ---
add_library(
        streamx-native
        SHARED
        native-lib.cpp
        torrent-engine.cpp
        ai_engine.cpp  # নতুন AI ইঞ্জিন ফাইল
)

find_package(LibtorrentRasterbar CONFIG REQUIRED)
find_package(OpenSSL REQUIRED)
find_package(Boost REQUIRED COMPONENTS system)

# --- 3. Link Libraries ---
target_link_libraries(
        streamx-native
        PRIVATE
        LibtorrentRasterbar::torrent-rasterbar
        OpenSSL::SSL
        OpenSSL::Crypto
        Boost::system
        whisper # Whisper লাইব্রেরি লিঙ্ক করা হলো
        log
        atomic
)
