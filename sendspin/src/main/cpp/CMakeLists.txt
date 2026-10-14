# Sendspin player: sendspin-cpp (the protocol reference implementation, Apache-2.0) plus a small
# JNI bridge. sendspin-cpp fetches its own dependencies (ArduinoJson, micro-flac, micro-opus,
# IXWebSocket) during configure, so the first native build needs git and network access.
cmake_minimum_required(VERSION 3.22.1)
project(vaca_sendspin LANGUAGES C CXX ASM)

include(FetchContent)

# v0.8.0
FetchContent_Declare(
    sendspin_cpp
    GIT_REPOSITORY https://github.com/Sendspin/sendspin-cpp.git
    GIT_TAG        9331ace6428979982c934384702d289958ca125e
)

# Player plays the audio; metadata (track info, progress), controller (transport, shuffle, repeat)
# and colour (the track's palette) feed the on-screen now-playing overlay. Artwork arrives as a URL
# in the metadata, so the artwork role, and the other roles VACA has no use for, stay out of the
# binary.
set(SENDSPIN_ENABLE_PLAYER ON CACHE BOOL "" FORCE)
set(SENDSPIN_ENABLE_CONTROLLER ON CACHE BOOL "" FORCE)
set(SENDSPIN_ENABLE_METADATA ON CACHE BOOL "" FORCE)
set(SENDSPIN_ENABLE_COLOR ON CACHE BOOL "" FORCE)
set(SENDSPIN_ENABLE_ARTWORK OFF CACHE BOOL "" FORCE)
set(SENDSPIN_ENABLE_VISUALIZER OFF CACHE BOOL "" FORCE)
set(BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)

FetchContent_MakeAvailable(sendspin_cpp)

add_library(sendspin_jni SHARED sendspin_jni.cpp)
target_compile_features(sendspin_jni PRIVATE cxx_std_20)
target_compile_options(sendspin_jni PRIVATE -Wall -Wextra)
find_library(log-lib log)
target_link_libraries(sendspin_jni PRIVATE sendspin ${log-lib})
