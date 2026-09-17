#pragma once
#include "vivo-hexquad-preprocess.h"

namespace vivo_hexquad {
// Restricted normal-exposure HP9 profile, verified against the stock CPU
// functions. Unity WB, equal exposures, zero black after upstream subtraction,
// hdrvstmode=1, vstBaseISOMode=0 (ISO 50 norm), vstNormCoeff=1, no AVST.
// HDR, unequal exposures and device-specific ISO calibration are NOT implied.
struct NormalVst {
    float shot, variance, norm;
    explicit NormalVst(int iso) {
        require(iso >= 50 && iso <= 12800, "HP9 VST ISO outside verified profile bounds");
        auto noise = [](int sensitivity) {
            float x = float(sensitivity);
            float a = std::fma(.0009349135f, x, .0186120867f) / 255.f;
            float b = (std::fma(.0000017738f*x, x, .0001141268f*x) + .0289801844f);
            b = std::max(b, 1.e-6f) / 65025.f;
            return std::array<float,2>{{a,b}};
        };
        const auto base = noise(50), current = noise(iso);
        shot = current[0]; variance = current[1];
        float offset = float(double(base[1]) / (double(base[0])*base[0]) + .375);
        norm = 2.f * std::sqrt(1.f / base[0] + offset);
    }
    float offset() const { return float(double(variance) / (double(shot)*shot) + .375); }
    std::vector<uint16_t> forward() const {
        std::vector<uint16_t> lut(Colors * Levels);
        const float b = offset();
        for (size_t i = 0; i < Levels; ++i) {
            float v = (2.f * std::sqrt(float(i) / 16383.f / shot + b)) / norm;
            unsigned q = unsigned(std::min(65535.f, std::fma(v, 65535.f, .5f)));
            for (size_t c = 0; c < Colors; ++c) lut[c * Levels + i] = uint16_t(q);
        }
        return lut;
    }
    IvstLuts inverse() const {
        IvstLuts result;
        for (auto& lut : result) lut.resize(65536);
        const double b = offset();
        for (size_t i = 0; i < 65536; ++i) {
            // Match float normalization then double square in the stock loop.
            float t = (float(i) * (1.f / 65535.f) * norm) * .5f;
            float value = float(std::max(0., std::min(1., (double(t)*t - b) * shot)));
            for (auto& lut : result) lut[i] = value;
        }
        return result;
    }
};
} // namespace vivo_hexquad
