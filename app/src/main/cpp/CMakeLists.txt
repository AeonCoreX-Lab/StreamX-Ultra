cmake_minimum_required(VERSION 3.22.1)

project("streamx-native")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ওয়ার্নিং বন্ধ করার জন্য ফ্ল্যাগ
add_compile_options(-Wno-macro-redefined)

add_library(
        streamx-native
        SHARED
        native-lib.cpp
        torrent-engine.cpp
)

find_package(LibtorrentRasterbar CONFIG REQUIRED)
find_package(OpenSSL REQUIRED)
find_package(Boost REQUIRED COMPONENTS system)

target_link_libraries(
        streamx-native
        PRIVATE
        LibtorrentRasterbar::torrent-rasterbar
        OpenSSL::SSL
        OpenSSL::Crypto
        Boost::system
        log
        atomic
)