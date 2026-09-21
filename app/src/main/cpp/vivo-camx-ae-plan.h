#pragma once
#include "vivo-aec-exposure.h"
#include <array>

namespace vivo_aec {
// camera.qcom.so 137ac72b..., FillRawHdrInfoByAEC at 0x8d2020.
// Codes select solver records; their magnitude is not an exposure in stops.
inline int alternateExposureSlot(float code) {
    constexpr float epsilon = 0x1p-23f;
    if (std::abs(code) < epsilon) return 0;
    if (std::abs(code + 100.f) < epsilon) return 1;
    if (std::abs(code + 200.f) < epsilon) return 2;
    if (std::abs(code - 100.f) < epsilon) return 4;
    return -1;
}

struct ResolvedExposure {
    float shutterMs;
    float gain;
    float ev;
    int solverSlot;
    bool echo;
};

// Only the direct solver-table branch (input+0x50 == 0). The separate
// Pro RAW arbitration branch and special code 102 are not implemented here.
inline ResolvedExposure resolveAlternateExposure(float code,
        const std::array<Exposure,6>& solver, uint64_t echoShutterNs, float echoGain) {
    const int slot = alternateExposureSlot(code);
    ResolvedExposure out{};
    if (slot >= 0) {
        const auto& value = solver[slot];
        out = {value.shutter / 1000000.f, value.gain, value.ev, slot, false};
    } else if (std::isfinite(code) && std::abs(code - 101.f) < 0x1p-23f) {
        out = {static_cast<float>(echoShutterNs) / 1000000.f, echoGain, 101.f, -1, true};
    } else {
        throw std::invalid_argument("Unsupported stock alternate exposure code");
    }
    if (!std::isfinite(out.shutterMs) || out.shutterMs <= 0.f ||
        !std::isfinite(out.gain) || out.gain <= 0.f || !std::isfinite(out.ev))
        throw std::invalid_argument("Unusable stock solver exposure");
    return out;
}
} // namespace vivo_aec
