#pragma once
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <stdexcept>

namespace vivo_nice {
// PD2454 CRE 41b27775... / TCE 9f5deac3... only. These are recovered
// boundary contracts, NOT enough information to call the whole TCE pipeline.
// Unknown fields retain their offsets and are deliberately not assigned names.
namespace tce_contract {
constexpr size_t createArgumentBytes = 0x4c8; // TCE 384f58 and 3855d0
constexpr size_t processArgumentBytes = 0x6d0; // TCE 391664
// This is a lower bound established by accesses, NOT sizeof(output).
constexpr size_t outputMinimumBytes = 0x2e0; // extra-output pointer at +2d8

// Common image prefix, extended by TCE beyond CRE's 64-byte motion image.
// Addresses are represented as integers for host-side ABI tests, never as
// portable serialized pointers. Buffers and handles must belong to the caller.
struct Image {
    int32_t format, width, height, reserved;
    uint64_t data[4];
    int32_t stride[4];
    int32_t scanline[4];
    int32_t dataSize[4];
    std::array<uint8_t, 0x10> unknown60;
    uint64_t nativeHandle;
};
static_assert(sizeof(Image) == 0x78);
static_assert(offsetof(Image, data) == 0x10);
static_assert(offsetof(Image, stride) == 0x30);
static_assert(offsetof(Image, scanline) == 0x40);
static_assert(offsetof(Image, dataSize) == 0x50);
static_assert(offsetof(Image, nativeHandle) == 0x70);

// Known output prefix, not a claim about the complete allocation size. CRE
// owns RGB storage through shared image objects and borrows segmentation
// descriptors from its caller. These copies do not transfer buffer ownership.
struct OutputPrefix {
    Image rgbOutput;
    Image rgbDeRaw;
    int32_t toneMode;
    uint32_t unknownF4;
    Image sky;
    Image portrait[3];
    uint64_t extraOutput;
};
static_assert(sizeof(OutputPrefix) == outputMinimumBytes);
static_assert(offsetof(OutputPrefix, rgbDeRaw) == 0x78);
static_assert(offsetof(OutputPrefix, toneMode) == 0xf0);
static_assert(offsetof(OutputPrefix, sky) == 0xf8);
static_assert(offsetof(OutputPrefix, portrait) == 0x170);
static_assert(offsetof(OutputPrefix, extraOutput) == 0x2d8);

// CRE 38aeac..38afe8 copies the complete descriptors, including padding and
// unknown fields. Do not rebuild only width/height/data and discard the rest.
inline void bindAuxiliaryOutputs(OutputPrefix& output, const Image& sky,
                                 const std::array<Image, 3>& portrait,
                                 uint64_t extraOutput) {
    output.sky = sky;
    for (size_t i = 0; i < portrait.size(); ++i) output.portrait[i] = portrait[i];
    output.extraOutput = extraOutput;
}

// Layout follows CRE's five-float block at VNICETceNode+1780. The +8
// value is consumed separately by LogConvert, not by logExposure().
struct Exposure {
    float referenceGain;
    float deltaEvMilli;
    float logMaximum;
    float digitalGain;
    float drcGain;
};
static_assert(sizeof(Exposure) == 20);

// CRE 391f88..39209c. Preserve the donor's double log followed by separate
// float conversions/additions: log2(product) is not bit-equivalent.
inline float logExposure(const Exposure& p) {
    if (!std::isfinite(p.referenceGain) || p.referenceGain <= 0 ||
        !std::isfinite(p.deltaEvMilli) ||
        !std::isfinite(p.digitalGain) || p.digitalGain <= 0 ||
        !std::isfinite(p.drcGain) || p.drcGain <= 0)
        throw std::invalid_argument("Invalid CRE tone exposure metadata");
    constexpr double ln2 = 0.6931471805599453; // CRE VA 59b48
    const float delta = p.deltaEvMilli / 1000.f;
    const float absoluteDelta = delta < 0.f ? -delta : delta;
    const float reference = float(std::log(double(p.referenceGain)) / ln2);
    const float digital = float(std::log(double(p.digitalGain)) / ln2);
    const float drc = float(std::log(double(p.drcGain)) / ln2);
    const float first = absoluteDelta + reference;
    const float second = first + digital;
    return second + drc;
}

struct LogEncoding {
    float exposureScale, logMaximum;
    uint16_t zeroCode, oneCode;
    float inputScale;
};
static_assert(sizeof(LogEncoding) == 16);

// Arguments 5..9 of CRE niceLog, set by 38e208..38e2dc. Input is float
// normalized RGB for 32-bit data, or unsigned 16-bit RGB for 16-bit data.
// This only prepares the kernel arguments; std::log is not a bit-exact
// substitute for the GPU's native_log used on pixels by niceLog.
inline LogEncoding logEncoding(const Exposure& p, int inputBits) {
    if ((inputBits != 16 && inputBits != 32) || !std::isfinite(p.logMaximum) ||
        p.logMaximum < 0.f || p.logMaximum > 65535.f)
        throw std::invalid_argument("Invalid CRE log encoding format or ceiling");
    const float exponent = logExposure(p) + 14.f;
    const float scale = float(std::exp2(double(exponent)) - 1.0);
    if (!std::isfinite(scale) || scale <= 0.f)
        throw std::invalid_argument("CRE log encoding exposure outside positive range");
    return {scale, p.logMaximum,
        uint16_t(std::fmin(double(p.logMaximum), 236.59423763112798)),
        uint16_t(std::fmin(double(p.logMaximum), 354.891356446692)),
        1.f / (inputBits == 32 ? 1.f : 65535.f)};
}
} // namespace tce_contract
} // namespace vivo_nice
