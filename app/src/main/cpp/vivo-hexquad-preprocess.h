#pragma once
#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <stdexcept>
#include <vector>

// Verified sparse-RAW boundary operations for the HP9 NICE HexQuad models.
// This is NOT a capture backend: alignment, calibrated LUT generation, QNN
// tensor types/scales and overlap placement still need device validation.
// See docs/vivo-hexquad.md for evidence and limits. No vendor source included.
namespace vivo_hexquad {
constexpr size_t Frames = 6, Colors = 3, Levels = 16384, Channels = 18;
constexpr int InputSide = 288;

inline void require(bool ok, const char* message) {
    if (!ok) throw std::invalid_argument(message);
}

// Colour belongs to the sampled SOURCE coordinate, not its destination after
// registration. redCorner: 0 RGGB, 1 GRBG, 2 GBRG, 3 BGGR, in 4x4 blocks.
inline uint16_t tagSource(uint16_t raw, int sourceX, int sourceY, int redCorner) {
    require(redCorner >= 0 && redCorner < 4, "Invalid HexQuad CFA");
    unsigned quadrant = ((unsigned(sourceY) & 7u) / 4u) * 2u
                      + (unsigned(sourceX) & 7u) / 4u;
    unsigned color = quadrant == unsigned(redCorner) ? 0u
                   : quadrant == (unsigned(redCorner) ^ 3u) ? 2u : 1u;
    return uint16_t(std::min<unsigned>(raw, Levels - 1) | (color << 14));
}

// A registered tagged RAW canvas: low 14 bits are data, high 2 bits are R/G/B
// (0/1/2) or a hole (3). Stride and capacity are in uint16_t elements.
struct TaggedFrame {
    const uint16_t* data;
    size_t capacity, stride;
    int width, height;
};
using VstLuts = std::array<std::vector<uint16_t>, Frames>;
struct InputScale {
    float factor, clip;
    unsigned leftShift;
};

inline std::vector<float> packTile(const std::array<TaggedFrame, Frames>& frames,
                                   const VstLuts& luts, int x, int y,
                                   int width, int height, InputScale scale) {
    require(x >= 0 && y >= 0 && width > 0 && height > 0 &&
            width <= InputSide && height <= InputSide, "Invalid HexQuad tile");
    require(std::isfinite(scale.factor) && scale.factor > 0 &&
            scale.factor <= 1.0e30f && std::isfinite(scale.clip) &&
            scale.clip > 0 && scale.leftShift < 16, "Invalid VST scale");
    for (size_t f = 0; f < Frames; ++f) {
        const auto& in = frames[f];
        require(in.data && in.width >= width && in.height >= height &&
                x <= in.width - width && y <= in.height - height &&
                in.stride >= size_t(in.width), "Invalid registered RAW canvas");
        // Division avoids overflow in (height-1)*stride + width.
        require(in.capacity >= size_t(in.width) &&
                size_t(in.height - 1) <= (in.capacity - size_t(in.width)) / in.stride,
                "Truncated registered RAW canvas");
        require(luts[f].size() == Colors * Levels, "Invalid VST LUT size");
    }
    std::vector<float> out(size_t(width) * height * Channels, 0.0f);
    for (int row = 0; row < height; ++row) {
        for (int col = 0; col < width; ++col) {
            size_t dst = (size_t(row) * width + col) * Channels;
            for (size_t f = 0; f < Frames; ++f) {
                const auto& in = frames[f];
                uint16_t sample = in.data[size_t(y + row) * in.stride + x + col];
                unsigned color = sample >> 14;
                // Stock masks holes after lookup. Test before lookup to avoid
                // addressing a nonexistent fourth LUT plane.
                if (color == 3) continue;
                uint16_t value = luts[f][color * Levels + (sample & 0x3fffu)];
                // Stock shifts in ushort storage, including 16-bit wrap.
                value = uint16_t(uint32_t(value) << scale.leftShift);
                out[dst + f * Colors + color] =
                    std::min(float(value) * scale.factor, scale.clip);
            }
        }
    }
    return out;
}

using IvstLuts = std::array<std::vector<float>, Colors>;
struct OutputScale {
    float multiplier, offset, minimum, maximum;
    unsigned rightShift;
};

// Decode one NHWC RGB tile with overlap fusion disabled. The caller must first
// convert the actual QNN datatype to floats; XML scale is not automatically
// the graph's runtime dequantization scale. Do not reuse TELE square() decoding.
// Weighted overlap must accumulate before final clipping; this helper does not
// implement that branch of the stock kernel.
inline std::vector<float> decodeTile(const std::vector<float>& rgb,
                                     const IvstLuts& luts, OutputScale scale) {
    require(!rgb.empty() && rgb.size() % Colors == 0 && rgb.size() <= 576u * 576u * Colors,
            "Invalid HexQuad RGB tile");
    require(scale.rightShift < 16 && std::isfinite(scale.multiplier) &&
            scale.multiplier > 0 && std::isfinite(scale.offset) &&
            std::isfinite(scale.minimum) && std::isfinite(scale.maximum) &&
            scale.minimum <= scale.maximum, "Invalid IVST scale");
    size_t levels = size_t(65535u >> scale.rightShift) + 1;
    for (const auto& lut : luts) {
        require(lut.size() == levels, "Invalid IVST LUT size");
        for (float v : lut) require(std::isfinite(v), "Nonfinite IVST LUT");
    }
    std::vector<float> out(rgb.size());
    for (size_t i = 0; i < rgb.size(); ++i) {
        require(std::isfinite(rgb[i]), "Nonfinite neural output");
        float index = scale.multiplier * rgb[i] + scale.offset;
        require(std::isfinite(index), "IVST scale overflow");
        index = std::max(0.f, std::min(65535.f, index));
        unsigned q = unsigned(index) >> scale.rightShift; // truncate, not round
        out[i] = std::max(scale.minimum, std::min(scale.maximum, luts[i % Colors][q]));
    }
    return out;
}
} // namespace vivo_hexquad
