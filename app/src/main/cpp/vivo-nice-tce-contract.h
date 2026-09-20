#pragma once
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <limits>
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

// CRE 37a990..37a9dc, followed by 37b03c..37b04c.
// refEv0EV is NOT the Camera2 exposure-compensation index, ISO or ADRC gain.
// The two mode fields retain their parent-object offsets: their full enum
// identities have not been established, so do not substitute app mode enums.
struct GainRouting {
    float referenceEv0; // parent+4a8, already in the selected CRE domain
    int32_t parent22fc, parent2320;
    bool singleDemosaicModelSelected;
    float imageDigitalGain, imageDrcGain;
};
inline Exposure routeExposure(const GainRouting& input, float finalDeltaEvMilli,
                              float logMaximum) {
    const bool imageDomain = input.parent22fc == 0 || input.parent22fc == 3;
    return {imageDomain && input.parent2320 == 1 && input.singleDemosaicModelSelected
                ? input.referenceEv0 : 1.f,
            finalDeltaEvMilli, logMaximum,
            imageDomain ? input.imageDigitalGain : 1.f, input.imageDrcGain};
}

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

// TCE Process+0x78 and LogConvert's reference gain are different inputs.
// CRE 38d1cc..38d20c always uses args+0x28 here, before model-dependent
// args+0x2c routing or double-stream overrides applied to LogConvert.
inline float processExposureEv(float reference, float deltaEvMilli) {
    if (!std::isfinite(reference) || reference <= 0.f || !std::isfinite(deltaEvMilli))
        throw std::invalid_argument("Invalid CRE process exposure metadata");
    const float delta = deltaEvMilli / 1000.f;
    const float absoluteDelta = delta < 0.f ? -delta : delta;
    return absoluteDelta + float(std::log(double(reference)) / 0.6931471805599453);
}
// ICInputPreProcess 36fe0c..36fe18, used when an IC input supplies refEv0EV
// as uint32 Q10. Ordinary selected-frame reference EV is already float and
// must NOT be quantized to this representation just to call the tone mapper.
inline float decodeIcReferenceEv(uint32_t q10) {
    return float(q10) * (1.f / 1024.f);
}
struct LogExposureConfig {
    int32_t motionDoubleStream; // config+5ec8, override only when ==1
    int32_t hdrDoubleStream;    // config+55cc, override only when ==1
    int32_t bypassZeroDeltaEv;  // config+5a4c, zero enables override
    float logMaximum;          // config+5eac
};
// Complete coefficient preparation performed by CRE 38d680..38d820.
inline Exposure prepareLogExposure(const GainRouting& gains, float deltaEvMilli,
                                   const LogExposureConfig& config) {
    auto p = routeExposure(gains, deltaEvMilli, config.logMaximum);
    if (config.motionDoubleStream == 1) p.digitalGain = 1.f;
    if (config.hdrDoubleStream == 1 && config.bypassZeroDeltaEv == 0)
        p.deltaEvMilli = 0.f;
    return p;
}
// NICEIntegration 10d34..10d3c -> CRE 38d150..38d15c -> TCE Process+0xc0.
// Truncation toward zero, NOT Java Math.round. Nonfinite/out-of-range input
// rejection is added boundary validation, not emulation of ARM saturation.
inline int32_t sceneLuxIndex(float lux) {
    if (!std::isfinite(lux) || double(lux) < std::numeric_limits<int32_t>::min() ||
        double(lux) > std::numeric_limits<int32_t>::max())
        throw std::invalid_argument("Invalid NICE scene lux");
    return int32_t(lux);
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
