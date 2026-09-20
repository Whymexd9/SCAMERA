#pragma once
// Independently implemented from the inspected CRE kernel/ARM64 contract.
// Not yet connected to capture: frame routing and per-shot calibration must be
// supplied explicitly. No guessed sensor constants or implicit RGB conversion.
#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <stdexcept>
#include <vector>

namespace vivo_nice {
struct VstMode2 {
    float black;                 // normalized black level
    float referenceSlope;        // noise slope at reference ISO
    float normalizationSlope;    // noise slope at normalization ISO
    float normalizationOffset;   // noise variance offset at normalization ISO
    float frameExposureRatio;
    float normalizationExposureRatio;
    float norm;
    float exposureMultiplier;    // explicit host-selected sqrt(EV)/clip factor
    std::array<float, 3> gains;
    unsigned inputBits;
    unsigned outputBits;
};

inline std::vector<uint16_t> makeVstMode2(const VstMode2& p) {
    const auto positive = [](float x) { return std::isfinite(x) && x > 0; };
    if (p.inputBits == 0 || p.inputBits > 14 || p.outputBits == 0 || p.outputBits > 16 ||
        !std::isfinite(p.black) || p.black < 0 || p.black >= 1 ||
        !positive(p.referenceSlope) || !positive(p.normalizationSlope) ||
        !std::isfinite(p.normalizationOffset) || p.normalizationOffset < 0 ||
        !positive(p.frameExposureRatio) || !positive(p.normalizationExposureRatio) ||
        !positive(p.norm) || !positive(p.exposureMultiplier))
        throw std::invalid_argument("NICE VST mode 2 parameters");
    for (float gain : p.gains)
        if (!positive(gain)) throw std::invalid_argument("NICE VST gain");
    const unsigned count = 1u << p.inputBits;
    const unsigned maximum = (1u << p.outputBits) - 1;
    std::vector<uint16_t> lut(3 * count);
    // CRE computes the noise offset term in double, then converts to float.
    const double offset = (double(p.normalizationOffset) /
        (double(p.normalizationSlope) * p.normalizationSlope) + 0.375) /
        double(p.normalizationExposureRatio);
    for (unsigned c = 0; c < 3; ++c) {
        const float noise = float(offset * double(p.gains[c]));
        for (unsigned i = 0; i < count; ++i) {
            float signal = std::max(float(i) / float(count - 1) - p.black, 0.0f);
            signal = std::min((signal / p.frameExposureRatio) * p.gains[c], 1.0f);
            signal /= p.referenceSlope;
            const float root = std::sqrt(std::max(signal + noise, 0.0f));
            const float normalized = p.exposureMultiplier * ((root + root) / p.norm);
            const float quantized = std::fma(float(maximum), normalized, 0.5f);
            if (!std::isfinite(quantized)) throw std::invalid_argument("NICE VST overflow");
            lut[c * count + i] = uint16_t(std::min(quantized, float(maximum)));
        }
    }
    return lut;
}

struct TaggedFrame {
    const uint16_t* data;
    size_t count;
    size_t stride; // uint16 elements, not bytes
    size_t offset;
    // Offsets from R plane to G/B: both zero for sparse tagged Bayer.
    size_t greenOffset;
    size_t blueOffset;
    const uint16_t* lut; // three color planes; entriesPerColor each
    size_t lutCount;
};

inline std::vector<float> packSevenFrames(const std::array<TaggedFrame, 7>& frames,
                                        unsigned width, unsigned height,
                                        unsigned valueMask, unsigned leftShift,
                                        float factor, uint16_t clipMask,
                                        float quantClip) {
    if (!width || !height || width > 544 || height > 544 ||
        !valueMask || valueMask > 0x3fff || (valueMask & (valueMask + 1)) ||
        leftShift > 15 || !std::isfinite(factor) || factor <= 0 ||
        !std::isfinite(quantClip) || quantClip <= 0)
        throw std::invalid_argument("NICE tensor packing parameters");
    const size_t entries = valueMask + 1;
    for (const auto& f : frames) {
        if (!f.data || !f.lut || f.stride < width || f.offset > f.count ||
            f.lutCount < entries * 3)
            throw std::invalid_argument("NICE frame/LUT bounds");
        const size_t remaining = f.count - f.offset;
        for (size_t plane : {size_t(0), f.greenOffset, f.blueOffset}) {
            if (plane > remaining || remaining - plane < width ||
                size_t(height - 1) > (remaining - plane - width) / f.stride)
                throw std::invalid_argument("NICE plane bounds");
        }
    }
    std::vector<float> output(size_t(width) * height * 22, 0.0f);
    for (unsigned y = 0; y < height; ++y) for (unsigned x = 0; x < width; ++x) {
        float* pixel = output.data() + (size_t(y) * width + x) * 22;
        for (unsigned i = 0; i < 7; ++i) {
            const auto& f = frames[i];
            const size_t pos = f.offset + size_t(y) * f.stride + x;
            const size_t planes[] = {0, f.greenOffset, f.blueOffset};
            for (unsigned c = 0; c < 3; ++c) {
                const uint16_t tagged = f.data[pos + planes[c]];
                const unsigned tag = tagged >> 14;
                // Original reads LUT before masking tag 3. Guard first so a
                // rejected/out-of-bounds warp sample cannot read a fourth plane.
                if (tag != c) continue;
                const uint16_t value = f.lut[tag * entries + (tagged & valueMask)];
                const uint16_t shifted = uint16_t(unsigned(value) << leftShift);
                pixel[3 * i + c] = std::min(float(shifted) * factor, quantClip);
            }
        }
        pixel[21] = factor * float(clipMask);
    }
    return output;
}

// Apply the general float-output kernel's LUT and overlap-add contract to an
// already cropped, contiguous RGB tile. Caller supplies valid crop and masks;
// there is deliberately no inferred 544-pixel tile margin or default gain.
inline std::vector<float> inverseVstTile(
        const std::vector<float>& tensor, unsigned width, unsigned height,
        const std::array<std::vector<float>, 3>& lut, unsigned rightShift,
        float scale, float zeroPoint, float minimum, float maximum,
        const std::vector<float>& rows, const std::vector<float>& columns,
        const std::vector<float>& base, bool blend) {
    const size_t count = size_t(width) * height * 3;
    if (!width || !height || width > 544 || height > 544 || tensor.size() != count ||
        rightShift > 15 || !std::isfinite(scale) || scale <= 0 ||
        !std::isfinite(zeroPoint) || !std::isfinite(minimum) ||
        !std::isfinite(maximum) || maximum < minimum ||
        (blend && (rows.size() != height || columns.size() != width || base.size() != count)))
        throw std::invalid_argument("NICE inverse VST tile parameters");
    const size_t required = (65535u >> rightShift) + 1;
    for (const auto& channel : lut) {
        if (channel.size() < required) throw std::invalid_argument("NICE inverse VST LUT bounds");
        for (size_t i=0; i<required; ++i)
            if (!std::isfinite(channel[i])) throw std::invalid_argument("NICE inverse VST LUT value");
    }
    if (blend) {
        for (const auto* mask : {&rows, &columns}) for (float weight : *mask)
            if (!std::isfinite(weight) || weight < 0 || weight > 1)
                throw std::invalid_argument("NICE tile overlap mask");
    }
    std::vector<float> out(count);
    for (unsigned y=0; y<height; ++y) for (unsigned x=0; x<width; ++x)
        for (unsigned c=0; c<3; ++c) {
            const size_t i=(size_t(y)*width+x)*3+c;
            if (!std::isfinite(tensor[i]) || (blend && !std::isfinite(base[i])))
                throw std::invalid_argument("NICE unwritten/nonfinite output");
            const float value=scale*tensor[i]+zeroPoint;
            if (!std::isfinite(value)) throw std::invalid_argument("NICE output scale overflow");
            const unsigned index=unsigned(std::clamp(value,0.0f,65535.0f)) >> rightShift;
            float result=lut[c][index];
            if (blend) result=result*rows[y]*columns[x]+base[i];
            if (!std::isfinite(result)) throw std::invalid_argument("NICE overlap overflow");
            out[i]=std::clamp(result,minimum,maximum);
        }
    return out;
}
} // namespace vivo_nice
