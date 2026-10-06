// gauss.h stand-in: gaussianBlur for sigma >= 25 (RT: Young - van Vliet in double, rows then columns). The harness gives both
// builds the same implementation (vivo_rawca::gaussianBlur, single-threaded): RT's gauss.cc is not part of the comparison.
#pragma once
#include "rtengine.h"
#include "../../../../app/src/main/cpp/vivo-nice-rawca.h"
namespace rtengine {
inline void gaussianBlur(float** src, float** dst, const int W, const int H, const double sigma) {
    if (src != dst) for (int i = 0; i < H; ++i) std::memcpy(dst[i], src[i], sizeof(float) * size_t(W));
    vivo_rawca::gaussianBlur(dst[0], W, H, sigma, 1);
}
}
