cmake_minimum_required(VERSION 3.6)

project(dngCreator)

set(CMAKE_CXX_STANDARD 14)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

add_link_options("-Wl,-z,max-page-size=16384")

file(READ ${CMAKE_CURRENT_SOURCE_DIR}/../../../version.properties ver)
string(REGEX MATCH "VERSION_BUILD=([0-9]*)" _ ${ver})
set(VERSION_BUILD ${CMAKE_MATCH_1})
string(REGEX MATCH "VERSION_NAME=([0-9,.]*)" _ ${ver})
set(VERSION_NAME ${CMAKE_MATCH_1})

if(NOT EXISTS ${CMAKE_CURRENT_SOURCE_DIR}/deps/tiny_dng_writer.h)
    file(DOWNLOAD
            https://raw.githubusercontent.com/ParticlesDevs/tinydng/refs/heads/master/tiny_dng_writer.h
            ${CMAKE_CURRENT_SOURCE_DIR}/deps/tiny_dng_writer.h
            STATUS TINYDNG_DOWNLOAD_STATUS)
    list(GET TINYDNG_DOWNLOAD_STATUS 0 TINYDNG_DOWNLOAD_CODE)
    if(NOT TINYDNG_DOWNLOAD_CODE EQUAL 0)
        message(FATAL_ERROR "tiny_dng_writer.h download failed: ${TINYDNG_DOWNLOAD_STATUS}")
    endif()
endif()

if(NOT EXISTS ${CMAKE_CURRENT_SOURCE_DIR}/deps/technicallyflac.h)
    file(DOWNLOAD
            https://raw.githubusercontent.com/ParticlesDevs/technicallyflac/main/technicallyflac.h
            ${CMAKE_CURRENT_SOURCE_DIR}/deps/technicallyflac.h)
endif()

if(NOT EXISTS ${CMAKE_CURRENT_SOURCE_DIR}/deps/archive.h)
    file(DOWNLOAD
            https://raw.githubusercontent.com/libarchive/libarchive/master/libarchive/archive.h
            ${CMAKE_CURRENT_SOURCE_DIR}/deps/archive.h)
endif()

if(NOT EXISTS ${CMAKE_CURRENT_SOURCE_DIR}/deps/archive_entry.h)
    file(DOWNLOAD
            https://raw.githubusercontent.com/libarchive/libarchive/master/libarchive/archive_entry.h
            ${CMAKE_CURRENT_SOURCE_DIR}/deps/archive_entry.h)
endif()

include_directories(${CMAKE_CURRENT_SOURCE_DIR}/deps)

set(SRC_DIR ${CMAKE_CURRENT_SOURCE_DIR})

add_library(${CMAKE_PROJECT_NAME} SHARED
    ${SRC_DIR}/dngCreator.cpp
)
add_library(allocator SHARED
    ${SRC_DIR}/allocator.cpp
    ${SRC_DIR}/worker-spawn.cpp
)
add_library(flacRecorder SHARED
    ${SRC_DIR}/flacRecorder.cpp
)
add_library(
        camera2native SHARED ${SRC_DIR}/native-engine.cpp
)

target_compile_definitions(${CMAKE_PROJECT_NAME} PRIVATE
        VERSION_BUILD="${VERSION_BUILD}"
        VERSION_NAME="${VERSION_NAME}"
)

target_link_libraries(${CMAKE_PROJECT_NAME} PRIVATE
        log
        dl
)

target_link_libraries(allocator PRIVATE
        log
)

target_link_libraries(flacRecorder PRIVATE
        log
)

target_link_libraries(
        camera2native
        log
)

# Isolated read-only loader probe; no vendor process ABI is assumed.
add_library(vivoRemosaicProbe SHARED vivo-remosaic-probe.cpp)
add_library(vivoAe SHARED vivo-aec-jni.cpp)
set_property(TARGET vivoAe PROPERTY CXX_STANDARD 17)
target_compile_options(vivoAe PRIVATE -ffp-contract=off)
target_link_libraries(vivoRemosaicProbe PRIVATE dl)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    add_library(vivoNeuralProbe SHARED vivo-neural-probe.cpp)
    target_link_libraries(vivoNeuralProbe PRIVATE dl)
    add_library(vivoNiceProbe SHARED vivo-nice-probe.cpp)
    target_link_libraries(vivoNiceProbe PRIVATE dl)
endif()

# Final, memory-bounded resampling of successful Vivo RGB output.
add_library(lanczosDownscale SHARED lanczos-downscale-jni.cpp)
target_compile_options(lanczosDownscale PRIVATE -O3)
target_link_libraries(lanczosDownscale PRIVATE jnigraphics)
