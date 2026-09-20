#pragma once
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>

namespace vivo_vcf {
// Selected fields of vcf.parameter-CaptureFrameControlInfo, NOT the complete
// native VcfVivoCaptureFrameControlInfo C++ object or an executable Camera2 plan.
// Layout pinned to libvcf_core.so SHA256
// c3449dbb9867118173abc649de1fbab7edebebd826603cabe51fb9b58c0f07c5.
// FeatureHandler::queryCaptureControlInfo, 0x58b94. See accompanying contract.
struct CaptureFrameControl {
    uint32_t format = 0;
    float ev = 0, gain = 0, shutter = 0;
    uint32_t direction = 0;
};
struct CaptureControlFields {
    static constexpr size_t MaxBatches = 16, MaxFrames = 32;
    uint32_t frameCount = 0, batchCount = 0, remosaicType = 0;
    bool needImageEcho = false, needImageEchoYuvProcess = false;
    bool needSelectPreferred = false, needSubCam = false;
    uint8_t remosaicSizeType = 0;
    std::array<uint32_t, MaxBatches> batchFrameCounts{}, batchAlgoTypes{};
    std::array<CaptureFrameControl, MaxFrames> frames{};
    uint32_t shot2shotDepth = 0, countDown = 0, frameCatchMode = 0;
    float pastFrameIsoThreshold = 0, pastFrameExposureThreshold = 0;
};
enum class CaptureControlRead {
    Present, Missing, Truncated, DefaultRequired, InvalidCount, Nonfinite
};
namespace capture_control_detail {
inline uint32_t word(const uint8_t* p, size_t offset) {
    return uint32_t(p[offset]) | (uint32_t(p[offset + 1]) << 8) |
           (uint32_t(p[offset + 2]) << 16) | (uint32_t(p[offset + 3]) << 24);
}
inline float real(const uint8_t* p, size_t offset) {
    const uint32_t bits = word(p, offset);
    float value;
    static_assert(sizeof(value) == sizeof(bits), "32-bit float required");
    std::memcpy(&value, &bits, sizeof(value));
    return value;
}
}
// Byte count, not metadata element count. Unaligned little-endian data is safe.
// Failure clears output, preventing an earlier capture's plan being reused.
// Bounds/finite rejection are our boundary checks; native code assumes trusted
// HAL data. Numeric enums and shutter units are deliberately not guessed.
inline CaptureControlRead readCaptureControlFields(const void* data, size_t bytes,
                                                  CaptureControlFields& output) {
    output = {};
    if (!data) return bytes ? CaptureControlRead::Truncated : CaptureControlRead::Missing;
    if (bytes < 8) return CaptureControlRead::Truncated;
    const auto* p = static_cast<const uint8_t*>(data);
    using namespace capture_control_detail;
    const uint32_t frameCount = word(p, 0), batchCount = word(p, 4);
    // Native query requests its default when either count is zero.
    if (!frameCount || !batchCount) return CaptureControlRead::DefaultRequired;
    if (frameCount > CaptureControlFields::MaxFrames || batchCount > CaptureControlFields::MaxBatches)
        return CaptureControlRead::InvalidCount;
    // Highest recovered field ends here. This is NOT the full native ABI size.
    if (bytes < 0xb30) return CaptureControlRead::Truncated;
    CaptureControlFields result;
    result.frameCount = frameCount;
    result.batchCount = batchCount;
    result.remosaicType = word(p, 8);
    result.needImageEcho = p[0xc] != 0;
    result.needImageEchoYuvProcess = p[0xd] != 0;
    result.needSelectPreferred = p[0xe] != 0;
    result.needSubCam = p[0xf] != 0;
    result.remosaicSizeType = p[0x10];
    for (size_t i = 0; i < batchCount; ++i) {
        result.batchFrameCounts[i] = word(p, 0x54 + 4 * i);
        result.batchAlgoTypes[i] = word(p, 0x14 + 4 * i);
    }
    for (size_t i = 0; i < frameCount; ++i) {
        const size_t offset = 0x94 + 20 * i;
        auto& f = result.frames[i];
        f.format = word(p, offset);
        f.ev = real(p, offset + 4);
        f.gain = real(p, offset + 8);
        f.shutter = real(p, offset + 12);
        f.direction = word(p, offset + 16);
        if (!std::isfinite(f.ev) || !std::isfinite(f.gain) || !std::isfinite(f.shutter))
            return CaptureControlRead::Nonfinite;
    }
    result.shot2shotDepth = word(p, 0xb10);
    result.countDown = word(p, 0xb14);
    result.frameCatchMode = word(p, 0xb18);
    result.pastFrameIsoThreshold = real(p, 0xb28);
    result.pastFrameExposureThreshold = real(p, 0xb2c);
    if (!std::isfinite(result.pastFrameIsoThreshold) || !std::isfinite(result.pastFrameExposureThreshold))
        return CaptureControlRead::Nonfinite;
    output = result;
    return CaptureControlRead::Present;
}
} // namespace vivo_vcf
