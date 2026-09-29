#pragma once
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <stdexcept>

namespace vivo_aec {
struct Exposure {
    float shutter;
    float gain;
    float ev;
    uint32_t flags;
};
struct EvGaps { float shortEv, extraShortEv, longEv; };

inline EvGaps exposureGaps(float shortEv, float extraShortEv, float longEv) {
    if (!std::isfinite(shortEv) || !std::isfinite(extraShortEv) || !std::isfinite(longEv))
        throw std::invalid_argument("Nonfinite stock AE gap");
    constexpr float epsilon = 1.e-6f;
    const float s = std::min(std::abs(shortEv) >= epsilon ? shortEv : -4.f, -2.f);
    return {s, std::min(s - 2.f, std::abs(extraShortEv) >= epsilon ? extraShortEv : -8.f),
            std::max(std::abs(longEv) >= epsilon ? longEv : 4.f, 2.f)};
}

struct ShortExposureLimits {
    float tableMinGain;
    uint64_t tableMinShutter;
    float sensorMinGain;
    float bandingPeriod;
    int bandingMode;
};

// VivoEVMinusExpCalc, donor b2e3e124..., 0x17aae4. Shutter units must be
// identical across input, table and banding period; no Camera2 conversion here.
inline Exposure shortExposure(Exposure value, float divisor, const ShortExposureLimits& limits) {
    if (!std::isfinite(value.shutter) || value.shutter <= 0.f ||
        !std::isfinite(value.gain) || value.gain <= 0.f || !std::isfinite(value.ev) ||
        !std::isfinite(divisor) || divisor <= 0.f ||
        !std::isfinite(limits.tableMinGain) || limits.tableMinGain <= 0.f ||
        limits.tableMinShutter == 0 || !std::isfinite(limits.sensorMinGain) ||
        limits.sensorMinGain <= 0.f || !std::isfinite(limits.bandingPeriod) ||
        limits.bandingPeriod < 0.f || limits.bandingMode < 0 || limits.bandingMode > 4)
        throw std::invalid_argument("Invalid stock short-exposure input");
    if (value.ev <= 0.f) {
        const float gain = value.gain / divisor;
        if (gain >= limits.tableMinGain) {
            value.gain = gain;
        } else {
            value.shutter = ((value.gain / limits.tableMinGain) * value.shutter) / divisor;
            value.gain = limits.tableMinGain;
            value.shutter = std::max(value.shutter, static_cast<float>(limits.tableMinShutter));
        }
    }
    const float period = limits.bandingPeriod;
    if (period > 0.f && value.shutter > period) {
        // Match the donor's successive float multiples, including equality.
        // Bound malformed input instead of allowing its native loop to hang.
        if (value.shutter / period > 1000000.f)
            throw std::invalid_argument("Unbounded stock banding quantization");
        float multiple = 0.f;
        for (uint32_t n = 1; ; ++n) {
            const float next = period * static_cast<float>(n);
            if (next > value.shutter) break;
            multiple = next;
        }
        value.gain = (value.shutter * value.gain) / multiple;
        value.shutter = multiple;
    }
    const float minGain = std::abs(limits.tableMinGain) < 1.e-6f
                        ? limits.sensorMinGain : limits.tableMinGain;
    float floor = 0.f;
    if (limits.bandingMode == 2 || limits.bandingMode == 4) floor = period;
    else if (limits.bandingMode == 3) floor = period * .25f;
    if (value.shutter < floor) {
        value.gain = std::max(minGain, (value.shutter * value.gain) / floor);
        value.shutter = floor;
    }
    if (!std::isfinite(value.shutter) || !std::isfinite(value.gain) || value.shutter <= 0.f || value.gain <= 0.f)
        throw std::invalid_argument("Invalid stock short-exposure output");
    return value;
}
} // namespace vivo_aec
