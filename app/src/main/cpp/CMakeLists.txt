cmake_minimum_required(VERSION 3.22.1)

project("streamx-native")

# C++17 প্রয়োজন Libtorrent 2.0+ এর জন্য
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Fortify Source ইস্যু হ্যান্ডেল করার জন্য (যদি NDK মিসম্যাচ ফিক্সে কাজ না হয়)
add_compile_definitions(_FORTIFY_SOURCE=2)

add_library(
        streamx-native
        SHARED
        native-lib.cpp
        torrent-engine.cpp
)

# Libtorrent এবং ডিপেনডেন্সি খোঁজা
find_package(LibtorrentRasterbar CONFIG REQUIRED)
find_package(OpenSSL REQUIRED)
find_package(Boost REQUIRED COMPONENTS system)

# লাইব্রেরি লিঙ্ক করা
target_link_libraries(
        streamx-native
        PRIVATE
        LibtorrentRasterbar::torrent-rasterbar
        OpenSSL::SSL
        OpenSSL::Crypto
        Boost::system
        log
        atomic  # অনেক সময় লিঙ্কিং এররের জন্য এটি প্রয়োজন হয়
)
