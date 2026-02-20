# Update CMakeLists.txt to include Vosk AAR dependency v0.3.50

cmake_minimum_required(VERSION 3.4.1)

# If you have vosk AAR, replace 'path_to_your_vosk.aar' with the actual path
set(VOSK_AAR_PATH "path_to_your_vosk.aar")

add_library(vosk SHARED IMPORTED)
set_target_properties(vosk PROPERTIES IMPORTED_LOCATION ${VOSK_AAR_PATH})

# Add your other configurations here
# For example:
set(CMAKE_CXX_STANDARD 11)


# Your code involving vosk

# Add additional configurations
# Example:
find_library(log-lib log)
target_link_libraries(native-lib vosk ${log-lib})  
