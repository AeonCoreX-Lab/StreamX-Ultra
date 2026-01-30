cmake_minimum_required(VERSION 3.22.1)

# Ensure Android NDK toolchain and vcpkg chainload are set before project().
# This helps vcpkg detect the Android compiler correctly during manifest install.
if(DEFINED ENV{ANDROID_NDK_HOME})
    set(_ndk_toolchain_file "$ENV{ANDROID_NDK_HOME}/build/cmake/android.toolchain.cmake")
    if(NOT DEFINED CMAKE_TOOLCHAIN_FILE)
        set(CMAKE_TOOLCHAIN_FILE "${_ndk_toolchain_file}" CACHE STRING "Android toolchain file" FORCE)
    endif()
    if(NOT DEFINED VCPKG_CHAINLOAD_TOOLCHAIN_FILE)
        set(VCPKG_CHAINLOAD_TOOLCHAIN_FILE "${_ndk_toolchain_file}" CACHE STRING "vcpkg chainload toolchain file" FORCE)
    endif()
endif()

# --- Vcpkg Triplet Selection based on Android ABI ---
# Set VCPKG_TARGET_TRIPLET as a CACHE variable so vcpkg's detection uses it.
if(DEFINED ANDROID_ABI)
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(VCPKG_TARGET_TRIPLET "arm64-android" CACHE STRING "vcpkg triplet" FORCE)
    elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
        set(VCPKG_TARGET_TRIPLET "arm-android" CACHE STRING "vcpkg triplet" FORCE)
    elseif(ANDROID_ABI STREQUAL "x86_64")
        set(VCPKG_TARGET_TRIPLET "x64-android" CACHE STRING "vcpkg triplet" FORCE)
    elseif(ANDROID_ABI STREQUAL "x86")
        set(VCPKG_TARGET_TRIPLET "x86-android" CACHE STRING "vcpkg triplet" FORCE)
    endif()
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

# Use config packages provided by vcpkg
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