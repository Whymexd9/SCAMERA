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

target_link_libraries(
        camera2native
        log
)

add_library(vivoAe SHARED vivo-aec-jni.cpp)
set_property(TARGET vivoAe PROPERTY CXX_STANDARD 17)
target_compile_options(vivoAe PRIVATE -ffp-contract=off)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    add_library(vivoNeuralProbe SHARED vivo-neural-probe.cpp)
    target_link_libraries(vivoNeuralProbe PRIVATE dl)
    add_library(vivoNiceProbe SHARED vivo-nice-probe.cpp)
    target_link_libraries(vivoNiceProbe PRIVATE dl)
endif()

# P24: every saved JPEG through jpegli, 4:4:4 (third_party/README.md); Highway gives it NEON / SVE.
set(HWY_DIR ${SRC_DIR}/third_party/highway)
add_library(scameraHwy STATIC
    ${HWY_DIR}/hwy/abort.cc ${HWY_DIR}/hwy/aligned_allocator.cc ${HWY_DIR}/hwy/nanobenchmark.cc ${HWY_DIR}/hwy/per_target.cc
    ${HWY_DIR}/hwy/perf_counters.cc ${HWY_DIR}/hwy/print.cc ${HWY_DIR}/hwy/profiler.cc ${HWY_DIR}/hwy/targets.cc ${HWY_DIR}/hwy/timer.cc)
set_property(TARGET scameraHwy PROPERTY CXX_STANDARD 17)
target_include_directories(scameraHwy PUBLIC ${HWY_DIR})
target_compile_definitions(scameraHwy PUBLIC HWY_STATIC_DEFINE)
target_compile_options(scameraHwy PRIVATE -O3 -fmerge-all-constants -fvisibility=hidden -fvisibility-inlines-hidden)
file(GLOB JPEGLI_SRC ${SRC_DIR}/third_party/jpegli/lib/jpegli/*.cc)
add_library(scameraJpegli STATIC ${JPEGLI_SRC})
set_property(TARGET scameraJpegli PROPERTY CXX_STANDARD 17)
target_include_directories(scameraJpegli PUBLIC ${SRC_DIR}/third_party/jpegli ${SRC_DIR}/third_party/jpegli/include)
target_compile_options(scameraJpegli PRIVATE -O3 -fvisibility=hidden -fvisibility-inlines-hidden)
target_link_libraries(scameraJpegli PUBLIC scameraHwy)
add_library(scameraJpeg SHARED scamera-jpeg.cpp)
set_property(TARGET scameraJpeg PROPERTY CXX_STANDARD 17)
target_compile_options(scameraJpeg PRIVATE -O3 -fvisibility=hidden -fvisibility-inlines-hidden)
target_link_libraries(scameraJpeg PRIVATE scameraJpegli jnigraphics log)

# AVIF photo output: libavif + libaom (pinned releases, scamera-avif-deps.cmake), 8/10/12-bit, 4:4:4 / 4:2:0, lossless.
include(${SRC_DIR}/scamera-avif-deps.cmake)
add_library(scameraAvif SHARED scamera-avif.cpp)
set_property(TARGET scameraAvif PROPERTY CXX_STANDARD 17)
target_compile_options(scameraAvif PRIVATE -O3 -fvisibility=hidden -fvisibility-inlines-hidden)
# The codec libraries are linked in privately: their symbols stay hidden, unused code is dropped.
target_link_options(scameraAvif PRIVATE -Wl,--exclude-libs,ALL -Wl,--gc-sections)
target_link_libraries(scameraAvif PRIVATE scameraLibavif jnigraphics log)

# Final, memory-bounded resampling of successful Vivo RGB output.
add_library(lanczosDownscale SHARED lanczos-downscale-jni.cpp)
target_compile_options(lanczosDownscale PRIVATE -O3)
target_link_libraries(lanczosDownscale PRIVATE jnigraphics)
