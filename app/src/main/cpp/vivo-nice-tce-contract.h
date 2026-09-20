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

// Layout follows CRE's five-float block at VNICETceNode+1780. The +8
// value is consumed separately by LogConvert, not by logExposure().
struct Exposure {
    float referenceGain;
    float deltaEvMilli;
    float conversionGain;
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
} // namespace tce_contract
} // namespace vivo_nice
