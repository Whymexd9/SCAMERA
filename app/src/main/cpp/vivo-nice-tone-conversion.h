#pragma once
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <stdexcept>

namespace vivo_nice {
// Pinned PD2454 TCE host contract, not the complete tone pipeline. The exp table
// is supplied by the verified donor loader; do not substitute a guessed gamma.
// NormFloat 0x39dee4; Log output conversion 0x39e670; exp LUT VA 0x4c5aa.
struct FastTmConversion {
    static constexpr size_t tableSize=9938;
    using ExpTable=std::array<uint16_t,tableSize>;
    static float normalize(uint16_t rgb) {
        // Values above 15615 intentionally remain above 1, as in the donor.
        return float(rgb)/15615.f;
    }
    static uint16_t logOutput(float value,const ExpTable& exponential) {
        // Nonfinite graph output is a failure, not an arbitrary table index.
        if(!std::isfinite(value))throw std::invalid_argument("Nonfinite FastTM output");
        const auto index=uint32_t(std::clamp(value,0.f,1.f)*9937.f);
        return std::min<uint16_t>(exponential[index],16383);
    }
};
} // namespace vivo_nice
