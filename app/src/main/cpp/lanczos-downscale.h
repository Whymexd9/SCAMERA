#pragma once
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <stdexcept>
#include <vector>

// Opaque sRGB RGBA8. Separable, scale-aware Lanczos in linear light.
// Cache only the horizontal rows needed by the vertical filter, not a full
// floating-point image (a 50 MP intermediate would be prohibitively large).
namespace scamera_lanczos {
struct Tap { int first; std::vector<float> weights; };
inline double sinc(double x) {
    if (std::abs(x) < 1e-12) return 1.0;
    const double p = 3.14159265358979323846 * x;
    return std::sin(p) / p;
}
inline std::vector<Tap> coefficients(int input, int output, int lobes) {
    const double scale = double(output) / input, radius = lobes / scale;
    std::vector<Tap> taps(output);
    for (int i = 0; i < output; ++i) {
        const double center = (i + 0.5) / scale - 0.5;
        int first = std::max(0, int(std::ceil(center - radius)));
        int last = std::min(input - 1, int(std::floor(center + radius)));
        Tap& tap = taps[i]; tap.first = first;
        double sum = 0;
        for (int j = first; j <= last; ++j) {
            const double d = (j - center) * scale;
            double w = std::abs(d) < lobes ? sinc(d) * sinc(d / lobes) : 0;
            tap.weights.push_back(float(w)); sum += w;
        }
        for (float& w : tap.weights) w = float(w / sum);
    }
    return taps;
}
inline void resize(const uint8_t* source, int sw, int sh, size_t srcStride,
                   uint8_t* destination, int dw, int dh, size_t dstStride, int lobes) {
    if (!source || !destination || sw < 1 || sh < 1 || dw < 1 || dh < 1 ||
        dw > sw || dh > sh || int64_t(dw)*4 < sw || int64_t(dh)*4 < sh ||
        lobes < 2 || lobes > 5 || srcStride < size_t(sw)*4 || dstStride < size_t(dw)*4)
        throw std::invalid_argument("Invalid Lanczos downscale geometry");
    if (sw == dw && sh == dh) {
        for (int y=0; y<sh; ++y) std::memcpy(destination+y*dstStride, source+y*srcStride, size_t(sw)*4);
        return;
    }
    const auto horizontal = coefficients(sw, dw, lobes);
    const auto vertical = coefficients(sh, dh, lobes);
    float decode[256];
    for (int i=0; i<256; ++i) {
        const double v=i/255.0;
        decode[i]=float(v<=0.04045 ? v/12.92 : std::pow((v+0.055)/1.055,2.4));
    }
    std::vector<uint8_t> encode(65536);
    for (int i=0; i<65536; ++i) {
        const double v=i/65535.0;
        encode[i]=uint8_t(std::lround(255*(v<=0.0031308 ? 12.92*v : 1.055*std::pow(v,1/2.4)-0.055)));
    }
    size_t capacity=1;
    for (const auto& t:vertical) capacity=std::max(capacity,t.weights.size());
    std::vector<std::vector<float>> rows(capacity, std::vector<float>(size_t(dw)*3));
    std::vector<int> tags(capacity,-1);
    std::vector<float> sum(size_t(dw)*3);
    for (int y=0; y<dh; ++y) {
        std::fill(sum.begin(),sum.end(),0);
        const Tap& v=vertical[y];
        for (size_t k=0; k<v.weights.size(); ++k) {
            int sy=v.first+int(k); size_t slot=size_t(sy)%capacity;
            auto& row=rows[slot];
            if (tags[slot]!=sy) {
                const uint8_t* pixels=source+size_t(sy)*srcStride;
                for (int x=0; x<dw; ++x) {
                    float r=0,g=0,b=0; const Tap& h=horizontal[x];
                    for (size_t j=0; j<h.weights.size(); ++j) {
                        const uint8_t* p=pixels+(size_t(h.first)+j)*4; const float w=h.weights[j];
                        r+=decode[p[0]]*w; g+=decode[p[1]]*w; b+=decode[p[2]]*w;
                    }
                    row[x*3]=r; row[x*3+1]=g; row[x*3+2]=b;
                }
                tags[slot]=sy;
            }
            const float w=v.weights[k];
            for (size_t i=0; i<sum.size(); ++i) sum[i]+=row[i]*w;
        }
        uint8_t* out=destination+size_t(y)*dstStride;
        for (int x=0; x<dw; ++x) {
            for (int c=0; c<3; ++c) {
                const float value=std::max(0.f,std::min(1.f,sum[x*3+c]));
                out[x*4+c]=encode[size_t(value*65535.f+0.5f)];
            }
            out[x*4+3]=255;
        }
    }
}
}
