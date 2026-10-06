// RawTherapee's CA_correct_RT, SSE2 path (the x86 build of RawTherapee).
#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <iostream>
#include <memory>
#include <vector>
#include "rt_ref.h"
#ifndef __SSE2__
#error "SSE2 build needs an x86-64 target"
#endif
#define RawImageSource RawImageSourceSse
#include "rt/CA_correct_RT.cc"
float* rtRefSse(bool autoCA, size_t iterations, double cared, double cablue, bool avoidColourshift, float* data, int W, int H,
                const unsigned cfa[2][2], double* fitParams, bool fitParamsIn, bool fitParamsOut, float* buffer, bool freeBuffer) {
    rtengine::RawImageSourceSse src;
    src.W = W; src.H = H;
    std::memcpy(src.cfaTable, cfa, sizeof(src.cfaTable));
    rtengine::array2D<float> a(W, H);
    std::memcpy(a.plane(), data, sizeof(float) * size_t(W) * H);
    float* r = src.CA_correct_RT(autoCA, iterations, cared, cablue, avoidColourshift, 0, a, fitParams, fitParamsIn, fitParamsOut, buffer, freeBuffer);
    std::memcpy(data, a.plane(), sizeof(float) * size_t(W) * H);
    return r;
}
