// rawimagesource.h stand-in: the members of RawImageSource that CA_correct_RT reads.
#pragma once
#include "rtengine.h"
#ifndef RT_CLASS
#define RT_CLASS RawImageSource
#endif
namespace rtengine {
class RT_CLASS {
public:
    int W = 0, H = 0;
    unsigned cfaTable[2][2]{};
    ProgressListener* plistener = nullptr;
    unsigned FC(int row, int col) const { return cfaTable[row & 1][col & 1]; }
    float* CA_correct_RT(bool autoCA, size_t autoIterations, double cared, double cablue, bool avoidColourshift, int border_crop,
                         array2D<float>& rawData, double* fitParamsTransfer, bool fitParamsIn, bool fitParamsOut, float* buffer,
                         bool freeBuffer, size_t chunkSize = 2, bool measure = false);
};
}
