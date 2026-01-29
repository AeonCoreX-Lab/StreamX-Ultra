cmake_minimum_required(VERSION 3.22.1)

# প্রজেক্টের নাম পরিবর্তন করা হয়েছে
project("streamx-native")

set(CMAKE_CXX_STANDARD 17)

# ১. লাইব্রেরির নাম 'streamx-native' করা হলো (Kotlin কোড অনুযায়ী)
add_library(
        streamx-native
        SHARED
        src/main/cpp/native-lib.cpp
        src/main/cpp/torrent-engine.cpp
)

find_library(
        log-lib
        log
)

target_link_libraries(
        streamx-native
        ${log-lib}
)
