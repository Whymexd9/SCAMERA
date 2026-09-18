// Android/host adapter for the RawTherapee 5.12 denoise module.
// The numerical implementation is in vendor/ (GPL-3.0-or-later).
#pragma once
#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <vector>
#include "vendor/LUT.h"
#include "vendor/color.h"
#include "vendor/cplx_wavelet_dec.h"
#include "vendor/iccmatrices.h"
#define MIN(a,b) ((a)<(b)?(a):(b))
#define MAX(a,b) ((a)>(b)?(a):(b))

class MyMutex : public std::mutex {
public:
    using MyLock = std::unique_lock<MyMutex>;
};
class MyTime {
    std::chrono::steady_clock::time_point t;
public:
    void set() { t = std::chrono::steady_clock::now(); }
    int etime(const MyTime& o) const {
        return int(std::chrono::duration_cast<std::chrono::microseconds>(t-o.t).count());
    }
    double operator-(const MyTime& o) const {
        return std::chrono::duration<double, std::milli>(t-o.t).count();
    }
};
struct PortableOptions { int rgbDenoiseThreadLimit = 2; };
extern PortableOptions options;
namespace rtengine {
struct PortableSettings {
    int leveldnautsimpl = 0, leveldnti = 0, nrwavlevel = 1;
    float nrhigh = 0.45f;
    bool verbose = false;
};
extern const PortableSettings* settings;
using TMatrix = const double (*)[3];
class ICCStore {
public:
    static ICCStore* getInstance() { static ICCStore s; return &s; }
    TMatrix workingSpaceMatrix(const std::string&) const { return xyz_prophoto; }
    TMatrix workingSpaceInverseMatrix(const std::string&) const { return prophoto_xyz; }
};
class Imagefloat {
    int w, h;
    std::vector<float> pixels;
public:
    Imagefloat(int width, int height): w(width), h(height), pixels(size_t(w)*h*3) {}
    int getWidth() const { return w; }
    int getHeight() const { return h; }
    float& r(int y,int x) { return pixels[size_t(y)*w+x]; }
    float& g(int y,int x) { return pixels[size_t(w)*h+size_t(y)*w+x]; }
    float& b(int y,int x) { return pixels[size_t(w)*h*2+size_t(y)*w+x]; }
    void copyData(Imagefloat* dst) const { dst->pixels=pixels; }
};
class NoiseCurve {
public:
    LUTf lut;
    explicit operator bool() const { return static_cast<bool>(lut); }
    float operator[](float x) const { return lut[x]; }
    float getSum() const {
        float s=0; if (lut) for(int i=0;i<501;i++) s+=lut[i]; return s;
    }
};
namespace procparams {
struct DirPyrDenoiseParams {
    bool median=false, autoGain=true;
    double luma=0, chroma=15, Ldetail=0, redchro=0, bluechro=0, gamma=1.7;
    int passes=1;
    std::string dmethod="Lab", smethod="shal", Cmethod="MAN", C2method="MANU";
    std::string methodmed="none", medmethod="soft", rgbmethod="soft";
};
struct ProcParams {
    DirPyrDenoiseParams dirpyrDenoise;
    struct { std::string workingProfile="ProPhoto"; } icm;
};
}
class ImProcFunctions {
public:
    enum class Median { TYPE_3X3_SOFT, TYPE_3X3_STRONG, TYPE_5X5_SOFT,
                        TYPE_5X5_STRONG, TYPE_7X7, TYPE_9X9 };
    procparams::ProcParams* params;
    explicit ImProcFunctions(procparams::ProcParams* p): params(p) {}
#include "methods.inc"
};
void initializeDenoiseColor();
}
