# P147: the JPEG encoders on the phone (scamera-jpeg-check.cpp). Built with the NDK toolchain, run through adb shell:
#   cmake -G Ninja -S tools/jpeg-check -B <dir> -DCMAKE_TOOLCHAIN_FILE=<ndk>/build/cmake/android.toolchain.cmake \
#         -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DCMAKE_BUILD_TYPE=Release && ninja -C <dir>
cmake_minimum_required(VERSION 3.22)
project(scamera_jpeg_check C CXX)
set(SRC_DIR ${CMAKE_CURRENT_LIST_DIR}/../../app/src/main/cpp)
# the same libraries as app/src/main/cpp/CMakeLists.txt (scameraHwy, scameraJpegli)
set(HWY_DIR ${SRC_DIR}/third_party/highway)
add_library(checkHwy STATIC
    ${HWY_DIR}/hwy/abort.cc ${HWY_DIR}/hwy/aligned_allocator.cc ${HWY_DIR}/hwy/nanobenchmark.cc ${HWY_DIR}/hwy/per_target.cc
    ${HWY_DIR}/hwy/perf_counters.cc ${HWY_DIR}/hwy/print.cc ${HWY_DIR}/hwy/profiler.cc ${HWY_DIR}/hwy/targets.cc ${HWY_DIR}/hwy/timer.cc)
set_property(TARGET checkHwy PROPERTY CXX_STANDARD 17)
target_include_directories(checkHwy PUBLIC ${HWY_DIR})
target_compile_definitions(checkHwy PUBLIC HWY_STATIC_DEFINE)
target_compile_options(checkHwy PRIVATE -O3)
file(GLOB JPEGLI_SRC ${SRC_DIR}/third_party/jpegli/lib/jpegli/*.cc)
add_library(checkJpegli STATIC ${JPEGLI_SRC})
set_property(TARGET checkJpegli PROPERTY CXX_STANDARD 17)
target_include_directories(checkJpegli PUBLIC ${SRC_DIR}/third_party/jpegli ${SRC_DIR}/third_party/jpegli/include)
target_compile_options(checkJpegli PRIVATE -O3)
target_link_libraries(checkJpegli PUBLIC checkHwy)
add_executable(scamera-jpeg-check scamera-jpeg-check.cpp)
set_property(TARGET scamera-jpeg-check PROPERTY CXX_STANDARD 17)
target_include_directories(scamera-jpeg-check PRIVATE ${SRC_DIR})
target_compile_options(scamera-jpeg-check PRIVATE -O3)
target_link_libraries(scamera-jpeg-check PRIVATE checkJpegli)
target_link_options(scamera-jpeg-check PRIVATE -static-libstdc++)
