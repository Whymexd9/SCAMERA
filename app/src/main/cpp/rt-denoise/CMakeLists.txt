cmake_minimum_required(VERSION 3.18)
# FFTW distribution mirror contains the generated codelets absent from FFTW's development git.
include(FetchContent)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(ENABLE_FLOAT ON CACHE BOOL "" FORCE)
set(ENABLE_THREADS OFF CACHE BOOL "" FORCE)
set(ENABLE_OPENMP OFF CACHE BOOL "" FORCE)
set(DISABLE_FORTRAN ON CACHE BOOL "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
FetchContent_Declare(rt_fftw
    GIT_REPOSITORY https://github.com/alisw/fftw3.git
    GIT_TAG 2609f490804a8b606bbcb21e4ce3ac33c8074ea1)
FetchContent_MakeAvailable(rt_fftw)
add_library(rtDenoise SHARED engine.cc auto-chroma.cc portable.cc color-subset.cc jni.cc
    curve-subset.cc curve-api.cc vendor/flatcurves.cc
    vendor/FTblockDN.cc vendor/cplx_wavelet_dec.cc vendor/boxblur.cc vendor/labimage.cc)
target_include_directories(rtDenoise PRIVATE vendor ${rt_fftw_SOURCE_DIR}/api)
target_compile_features(rtDenoise PRIVATE cxx_std_17)
target_compile_options(rtDenoise PRIVATE -O2 -fexceptions -frtti -fopenmp)
# Preserve scalar RT arithmetic on ARM. No fast-math or reciprocal approximations.
target_link_libraries(rtDenoise PRIVATE fftw3f log)
target_link_options(rtDenoise PRIVATE "-Wl,-z,max-page-size=16384" -fopenmp -static-openmp)
