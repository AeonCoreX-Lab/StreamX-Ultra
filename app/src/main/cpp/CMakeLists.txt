cmake_minimum_required(VERSION 3.22.1)

project("streamx-native")

# C++17 বা তার বেশি প্রয়োজন Libtorrent 2.0+ এর জন্য
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(
        streamx-native
        SHARED
        native-lib.cpp
        torrent-engine.cpp
)

# Libtorrent এবং ডিপেনডেন্সি খোঁজা (vcpkg অটোমেটিক প্রোভাইড করবে)
find_package(Libtorrent RASERBAR REQUIRED)
find_package(OpenSSL REQUIRED)
find_package(Boost REQUIRED COMPONENTS system)

# লাইব্রেরি লিঙ্ক করা
target_link_libraries(
        streamx-native
        PRIVATE
        Libtorrent::torrent-rasterbar
        OpenSSL::SSL
        OpenSSL::Crypto
        Boost::system
        log
)
