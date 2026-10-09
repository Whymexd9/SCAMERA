#pragma once
#include "scam-hexquad-preprocess.h"

namespace scam_hexquad {
// Multipliers of VARIANCE coefficients, not standard deviation or sensor ISO.
// The shared factor multiplies both terms; independent factors multiply a and b.
// Keep the ISO-50 normalization fixed, and use the same profile for VST/IVST.
struct NoiseScale {
    float overall=1.f, photon=1.f, readout=1.f;
    void validate() const {
        for(float v:{overall,photon,readout})
            require(std::isfinite(v)&&v>=.5f&&v<=2.f,"Noise profile multiplier outside 0.5..2.0");
    }
};
// Vendor NoiseInfo Sensor01: shot a=(a0*iso+a1)/255, read b=(b0*iso^2+b1*iso+b2)/65025.
struct NoiseCoefficients { float a0,a1,b0,b1,b2; };
constexpr NoiseCoefficients Hp9Noise{.0009349135f,.0186120867f,.0000017738f,.0001141268f,.0289801844f};
// MainCamera ScamCREConfigQuad.xml NormalConfig NoiseInfo name='IMX06C'.
constexpr NoiseCoefficients Imx06cNoise{.0004969585f,-.0009924785f,.0000001935f,.0002008723f,-.0063289573f};
// TeleCamera ScamCREConfigROIQuad.xml NormalConfig NoiseInfo name='HP9' (2x ISZ quad).
constexpr NoiseCoefficients Hp9RoiQuadNoise{.0002652420f,.0001493047f,.0000001319f,.0000304834f,.0361984851f};
// Restricted normal-exposure HP9 profile, verified against the stock CPU
// functions. Unity WB, equal exposures, zero black after upstream subtraction,
// hdrvstmode=1, vstBaseISOMode=0 (ISO 50 norm), vstNormCoeff=1, no AVST.
// HDR, unequal exposures and device-specific ISO calibration are NOT implied.
struct NormalVst {
    float shot, variance, norm;
    explicit NormalVst(int iso,NoiseScale scale={},NoiseCoefficients k=Hp9Noise,int normIso=50) {
        scale.validate();
        require(iso >= 50 && iso <= 12800, "HP9 VST ISO outside verified profile bounds");
        auto noise = [k](int sensitivity) {
            float x = float(sensitivity);
            float a = std::max(std::fma(k.a0, x, k.a1), 1.e-6f) / 255.f;
            float b = (std::fma(k.b0*x, x, k.b1*x) + k.b2);
            b = std::max(b, 1.e-6f) / 65025.f;
            return std::array<float,2>{{a,b}};
        };
        const auto base = noise(normIso), current = noise(iso);
        shot = current[0]*(scale.overall*scale.photon);
        variance = current[1]*(scale.overall*scale.readout);
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
} // namespace scam_hexquad
