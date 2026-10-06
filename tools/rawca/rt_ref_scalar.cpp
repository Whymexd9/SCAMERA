// RawTherapee's CA_correct_RT, scalar path (__SSE2__ undefined before the source).
#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <iostream>
#include <memory>
#include <vector>
#include "rt_ref.h"
#undef __SSE2__
#define RawImageSource RawImageSourceScalar
#include "rt/CA_correct_RT.cc"
float* rtRefScalar(bool autoCA, size_t iterations, double cared, double cablue, bool avoidColourshift, float* data, int W, int H,
                   const unsigned cfa[2][2], double* fitParams, bool fitParamsIn, bool fitParamsOut, float* buffer, bool freeBuffer) {
    rtengine::RawImageSourceScalar src;
    src.W = W; src.H = H;
    std::memcpy(src.cfaTable, cfa, sizeof(src.cfaTable));
    rtengine::array2D<float> a(W, H);
    std::memcpy(a.plane(), data, sizeof(float) * size_t(W) * H);
    float* r = src.CA_correct_RT(autoCA, iterations, cared, cablue, avoidColourshift, 0, a, fitParams, fitParamsIn, fitParamsOut, buffer, freeBuffer);
    std::memcpy(data, a.plane(), sizeof(float) * size_t(W) * H);
    return r;
}
