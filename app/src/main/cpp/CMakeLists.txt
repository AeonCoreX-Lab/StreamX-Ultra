cmake_minimum_required(VERSION 3.22.1)

# প্রজেক্টের নাম
project("streamx-native")

set(CMAKE_CXX_STANDARD 17)

# FIX: সোর্স ফাইলের পাথ থেকে 'src/main/cpp/' সরিয়ে ফেলা হয়েছে
# কারণ CMakeLists.txt নিজেই ওই ফোল্ডারে আছে।
add_library(
        streamx-native
        SHARED
        native-lib.cpp
        torrent-engine.cpp
)

find_library(
        log-lib
        log
)

target_link_libraries(
        streamx-native
        ${log-lib}
)
