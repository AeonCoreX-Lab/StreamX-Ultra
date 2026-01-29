cmake_minimum_required(VERSION 3.22.1)

project("streamx-native")

# C++17 required for Libtorrent 2.0+
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# FIX: Removed manual _FORTIFY_SOURCE definition to avoid conflicts with NDK toolchain
# add_compile_definitions(_FORTIFY_SOURCE=2)

add_library(
        streamx-native
        SHARED
        native-lib.cpp
        torrent-engine.cpp
)

# Find Libtorrent and dependencies
find_package(LibtorrentRasterbar CONFIG REQUIRED)
find_package(OpenSSL REQUIRED)
find_package(Boost REQUIRED COMPONENTS system)

# Link Libraries
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
