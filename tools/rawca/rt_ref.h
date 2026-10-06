// The original RawTherapee CA_correct_RT (rt/CA_correct_RT.cc, unmodified) built twice: scalar path (rt_ref_scalar.cpp, the
// path the port follows) and SSE2 path (rt_ref_sse.cpp, what RawTherapee runs on x86). data: W x H floats in RT's scale.
#pragma once
#include <cstddef>
float* rtRefScalar(bool autoCA, size_t iterations, double cared, double cablue, bool avoidColourshift, float* data, int W, int H,
                   const unsigned cfa[2][2], double* fitParams, bool fitParamsIn, bool fitParamsOut, float* buffer, bool freeBuffer);
float* rtRefSse(bool autoCA, size_t iterations, double cared, double cablue, bool avoidColourshift, float* data, int W, int H,
                const unsigned cfa[2][2], double* fitParams, bool fitParamsIn, bool fitParamsOut, float* buffer, bool freeBuffer);
