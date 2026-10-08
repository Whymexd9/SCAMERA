# AVIF photo output: libavif (container, RGB -> YUV) + libaom (AV1 encoder, still images in AOM_USAGE_ALL_INTRA mode).
# Both are pinned release archives, fetched at configure time and checked against their SHA-256, so CI and local builds
# compile the same sources. Shared by the app (CMakeLists.txt, library scameraAvif) and the host check
# (tools/avif_host/CMakeLists.txt, tools/check_avif.py).
#
# libaom is configured and built by its own CMake as an external project (it needs Perl for its RTCD headers; x86 hosts
# also need nasm, arm64 needs no assembler); libavif's few C files are compiled here (target scameraLibavif).
#
# Inputs (optional): SCAMERA_DEPS_CACHE (download / source cache, default app/build/native-deps), SCAMERA_PERL.
# Output: static library target scameraLibavif (include <avif/avif.h>), linking libaom.
include_guard(GLOBAL)
if(CMAKE_VERSION VERSION_LESS 3.18)
    message(FATAL_ERROR "The AVIF dependencies need CMake 3.18 or newer (file(ARCHIVE_EXTRACT)), this is ${CMAKE_VERSION}")
endif()
cmake_policy(VERSION 3.18) # include() keeps this to the module
include(ExternalProject)

set(SCAMERA_AOM_VERSION 3.15.1)
set(SCAMERA_AOM_URL "https://storage.googleapis.com/aom-releases/libaom-${SCAMERA_AOM_VERSION}.tar.gz")
set(SCAMERA_AOM_SHA256 8ca0c52746174603500f0adb6f2a215d69c9ca2aab2acb3caa06fb791d8d01bf)
set(SCAMERA_AVIF_VERSION 1.4.2)
set(SCAMERA_AVIF_URL "https://github.com/AOMediaCodec/libavif/archive/refs/tags/v${SCAMERA_AVIF_VERSION}.tar.gz")
set(SCAMERA_AVIF_SHA256 2b645287340ba5a631d268b551dc2d72bd73ac33335962dd36dcdb6d8366921d)

set(SCAMERA_DEPS_CACHE "${CMAKE_CURRENT_LIST_DIR}/../../../build/native-deps" CACHE PATH
    "Download and source cache of the fetched native dependencies (libaom, libavif)")
get_filename_component(SCAMERA_DEPS_CACHE "${SCAMERA_DEPS_CACHE}" ABSOLUTE)

# Downloads <url> once (verified SHA-256), unpacks it to <cache>/<name> (only <patterns> when given) and returns that
# directory. A lock keeps parallel configures (several ABIs / variants) from unpacking the same archive at once.
function(scamera_fetch_source name url sha256 out_var)
    set(dir "${SCAMERA_DEPS_CACHE}/${name}")
    set(stamp "${dir}/.scamera-sha256")
    file(MAKE_DIRECTORY "${SCAMERA_DEPS_CACHE}")
    file(LOCK "${SCAMERA_DEPS_CACHE}/${name}.lock" GUARD FUNCTION TIMEOUT 1800)
    set(want "${sha256} ${ARGN}")
    set(have "")
    if(EXISTS "${stamp}")
        file(READ "${stamp}" have)
        string(STRIP "${have}" have)
    endif()
    if(NOT have STREQUAL want)
        set(archive "${SCAMERA_DEPS_CACHE}/${name}.tar.gz")
        set(actual "")
        if(EXISTS "${archive}")
            file(SHA256 "${archive}" actual)
        endif()
        if(NOT actual STREQUAL sha256)
            message(STATUS "Downloading ${url}")
            file(DOWNLOAD "${url}" "${archive}.part" EXPECTED_HASH SHA256=${sha256} TLS_VERIFY ON STATUS status)
            list(GET status 0 code)
            if(NOT code EQUAL 0)
                file(REMOVE "${archive}.part")
                message(FATAL_ERROR "Download of ${url} failed: ${status}")
            endif()
            file(RENAME "${archive}.part" "${archive}")
        endif()
        file(REMOVE_RECURSE "${dir}" "${dir}.unpack")
        if(ARGN)
            file(ARCHIVE_EXTRACT INPUT "${archive}" DESTINATION "${dir}.unpack" PATTERNS ${ARGN})
        else()
            file(ARCHIVE_EXTRACT INPUT "${archive}" DESTINATION "${dir}.unpack")
        endif()
        file(GLOB top LIST_DIRECTORIES true "${dir}.unpack/*")
        list(LENGTH top count)
        if(NOT count EQUAL 1)
            message(FATAL_ERROR "${archive}: expected one top-level directory, found ${top}")
        endif()
        file(RENAME "${top}" "${dir}")
        file(REMOVE_RECURSE "${dir}.unpack")
        file(WRITE "${stamp}" "${want}")
    endif()
    set(${out_var} "${dir}" PARENT_SCOPE)
endfunction()

scamera_fetch_source(libaom-${SCAMERA_AOM_VERSION} "${SCAMERA_AOM_URL}" ${SCAMERA_AOM_SHA256} SCAMERA_AOM_SOURCE_DIR)
set(avif_top libavif-${SCAMERA_AVIF_VERSION})
scamera_fetch_source(libavif-${SCAMERA_AVIF_VERSION} "${SCAMERA_AVIF_URL}" ${SCAMERA_AVIF_SHA256} SCAMERA_AVIF_SOURCE_DIR
    "${avif_top}/include/*" "${avif_top}/src/*" "${avif_top}/third_party/libyuv/*" "${avif_top}/LICENSE")

# libaom generates its RTCD headers with Perl. Git for Windows ships one.
if(NOT SCAMERA_PERL)
    find_package(Git QUIET)
    set(perl_hints "$ENV{ProgramFiles}/Git/usr/bin" "C:/Program Files/Git/usr/bin" "C:/Strawberry/perl/bin")
    if(GIT_EXECUTABLE)
        get_filename_component(git_dir "${GIT_EXECUTABLE}" DIRECTORY)
        list(APPEND perl_hints "${git_dir}/../usr/bin" "${git_dir}/../../usr/bin")
    endif()
    find_program(SCAMERA_PERL NAMES perl HINTS ${perl_hints})
endif()
if(NOT SCAMERA_PERL)
    message(FATAL_ERROR "libaom (AVIF photo output) needs Perl to build: install it or pass -DSCAMERA_PERL=<perl>")
endif()

# libaom: encoder only (Android decodes AVIF itself), high bit depth on (10 / 12-bit photos), runtime CPU detection
# (NEON / dot product / i8mm / SVE on arm64), no apps, tests or docs.
set(aom_build "${CMAKE_CURRENT_BINARY_DIR}/scamera-aom")
set(aom_lib "${aom_build}/${CMAKE_STATIC_LIBRARY_PREFIX}aom${CMAKE_STATIC_LIBRARY_SUFFIX}")
set(aom_args
    -DCMAKE_BUILD_TYPE=Release
    -DBUILD_SHARED_LIBS=0
    -DCONFIG_AV1_DECODER=0
    -DCONFIG_AV1_ENCODER=1
    -DCONFIG_AV1_HIGHBITDEPTH=1
    -DCONFIG_PIC=1
    -DCONFIG_WEBM_IO=0
    -DCONFIG_LIBYUV=0
    -DENABLE_DOCS=0
    -DENABLE_EXAMPLES=0
    -DENABLE_APPS=0
    -DENABLE_TESTDATA=0
    -DENABLE_TESTS=0
    -DENABLE_TOOLS=0
    "-DPERL_EXECUTABLE=${SCAMERA_PERL}")
if(NOT MSVC)
    list(APPEND aom_args "-DCMAKE_C_FLAGS=-ffunction-sections -fdata-sections" "-DCMAKE_CXX_FLAGS=-ffunction-sections -fdata-sections")
endif()
# The compilers come from the toolchain file (Android) or the CC / CXX environment (host); the rest is passed on.
foreach(var CMAKE_TOOLCHAIN_FILE ANDROID_ABI ANDROID_PLATFORM ANDROID_NDK ANDROID_STL CMAKE_MAKE_PROGRAM CMAKE_AR
        CMAKE_RANLIB CMAKE_POLICY_VERSION_MINIMUM CMAKE_ASM_NASM_COMPILER CMAKE_OSX_ARCHITECTURES)
    if(DEFINED ${var} AND NOT "${${var}}" STREQUAL "")
        # Windows paths with backslashes would be read as escapes in the external project's generated scripts.
        string(REPLACE "\\" "/" value "${${var}}")
        list(APPEND aom_args "-D${var}=${value}")
    endif()
endforeach()
if(ANDROID)
    # libaom enables the ASM language on arm64 and would look for a bare "as"; clang assembles for the target.
    list(APPEND aom_args "-DCMAKE_ASM_COMPILER=${CMAKE_C_COMPILER}")
endif()
ExternalProject_Add(scamera_aom_build
    SOURCE_DIR "${SCAMERA_AOM_SOURCE_DIR}"
    BINARY_DIR "${aom_build}"
    CMAKE_GENERATOR "${CMAKE_GENERATOR}"
    CMAKE_ARGS ${aom_args}
    BUILD_COMMAND "${CMAKE_COMMAND}" --build "${aom_build}" --target aom --config Release
    INSTALL_COMMAND ""
    BUILD_BYPRODUCTS "${aom_lib}"
    DOWNLOAD_COMMAND ""
    UPDATE_COMMAND ""
    LOG_CONFIGURE ON
    LOG_BUILD ON
    LOG_OUTPUT_ON_FAILURE ON)
add_library(scamera_aom STATIC IMPORTED GLOBAL)
set_target_properties(scamera_aom PROPERTIES IMPORTED_LOCATION "${aom_lib}")
add_dependencies(scamera_aom scamera_aom_build)

# libavif: core + the aom encoder glue; its bundled libyuv subset serves the scaler (unused here, but referenced).
set(avif "${SCAMERA_AVIF_SOURCE_DIR}")
add_library(scameraLibavif STATIC
    ${avif}/src/alpha.c ${avif}/src/avif.c ${avif}/src/colr.c ${avif}/src/colrconvert.c ${avif}/src/diag.c
    ${avif}/src/exif.c ${avif}/src/gainmap.c ${avif}/src/io.c ${avif}/src/mem.c ${avif}/src/obu.c
    ${avif}/src/properties.c ${avif}/src/rawdata.c ${avif}/src/read.c ${avif}/src/reformat.c
    ${avif}/src/reformat_libsharpyuv.c ${avif}/src/reformat_libyuv.c ${avif}/src/sampletransform.c ${avif}/src/scale.c
    ${avif}/src/stream.c ${avif}/src/utils.c ${avif}/src/write.c ${avif}/src/codec_aom.c
    ${avif}/third_party/libyuv/source/scale.c ${avif}/third_party/libyuv/source/scale_common.c
    ${avif}/third_party/libyuv/source/scale_any.c ${avif}/third_party/libyuv/source/row_common.c
    ${avif}/third_party/libyuv/source/planar_functions.c)
set_property(TARGET scameraLibavif PROPERTY C_STANDARD 11)
set_property(TARGET scameraLibavif PROPERTY POSITION_INDEPENDENT_CODE ON)
target_include_directories(scameraLibavif PUBLIC "${avif}/include"
    PRIVATE "${avif}/third_party/libyuv/include" "${SCAMERA_AOM_SOURCE_DIR}")
target_compile_definitions(scameraLibavif PRIVATE AVIF_CODEC_AOM=1 AVIF_CODEC_AOM_ENCODE=1)
if(NOT MSVC)
    target_compile_options(scameraLibavif PRIVATE -O2 -ffunction-sections -fdata-sections -Wno-unused-parameter)
endif()
add_dependencies(scameraLibavif scamera_aom_build)
set(THREADS_PREFER_PTHREAD_FLAG ON)
find_package(Threads REQUIRED)
target_link_libraries(scameraLibavif PUBLIC scamera_aom Threads::Threads)
if(UNIX)
    target_link_libraries(scameraLibavif PUBLIC m)
endif()
