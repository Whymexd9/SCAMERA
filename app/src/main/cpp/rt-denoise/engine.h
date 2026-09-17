#pragma once
#include <cstddef>
extern "C" int rt_curve(const double* points,int count,float* lut,char* error,size_t errorSize);
// Float RGBA, linear ProPhoto RGB. Alpha is preserved. Transactional on failure.
// params: luma, chroma, detail, red-green, blue-yellow, gamma, colorSpace(0 Lab/1 RGB),
// quality(0 standard/1 high), medianChannels(0 off/1 L/2 ab/3 Lab/4 L+ab/5 RGB),
// medianKernel(0 soft3/1 strong3/2 soft5/3 strong5/4 7/5 9), passes,
// autoChroma(0 manual/1 automatic), autoGain, exposureCompensation.
extern "C" int rt_denoise(float* rgba, int width, int height,
                          const float* params, int paramsCount,
                          const float* lumaCurve, const float* chromaCurve,
                          char* error, size_t errorSize);
