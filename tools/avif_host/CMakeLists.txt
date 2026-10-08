# Host build of the app's AVIF encoder for tools/check_avif.py: the same core (app/src/main/cpp/scamera-avif-core.h)
# and the same pinned libavif / libaom (app/src/main/cpp/scamera-avif-deps.cmake) as the APK's libscameraAvif.so.
# x86 hosts need nasm for libaom; Perl as for the app build.
cmake_minimum_required(VERSION 3.18)
project(scamera_avif_host C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

include(${CMAKE_CURRENT_LIST_DIR}/../../app/src/main/cpp/scamera-avif-deps.cmake)

add_executable(avif_host_encode avif_host_encode.cpp)
target_include_directories(avif_host_encode PRIVATE ${CMAKE_CURRENT_LIST_DIR}/../../app/src/main/cpp)
target_link_libraries(avif_host_encode PRIVATE scameraLibavif)
if(NOT MSVC)
    target_compile_options(avif_host_encode PRIVATE -O2 -Wall -Wextra)
endif()
