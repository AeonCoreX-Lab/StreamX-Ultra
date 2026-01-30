cmake_minimum_required(VERSION 3.22.1)

# --- FIX: Vcpkg Triplet Selection based on Android ABI ---
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(VCPKG_TARGET_TRIPLET "arm64-android" CACHE STRING "" FORCE)
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(VCPKG_TARGET_TRIPLET "arm-android" CACHE STRING "" FORCE)
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(VCPKG_TARGET_TRIPLET "x64-android" CACHE STRING "" FORCE)
endif()
# ---------------------------------------------------------

project("streamx-native")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

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
