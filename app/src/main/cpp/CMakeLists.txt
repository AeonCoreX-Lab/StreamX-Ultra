# CMake configuration for Vosk

# Remove FetchContent-based Vosk download

# Set up Vosk library from Gradle AAR dependency
set(VOSK_DIR ${CMAKE_CURRENT_SOURCE_DIR}/path/to/your/aar/extracted/Vosk)

# Include directories
include_directories(${VOSK_DIR}/include)

# Link libraries
add_library(vosk STATIC IMPORTED)
set_target_properties(vosk PROPERTIES IMPORTED_LOCATION ${VOSK_DIR}/libvosk.so)