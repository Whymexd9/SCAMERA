#pragma once
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <stdexcept>
#include <vector>

namespace vivo_nice {
namespace tce_contract {
struct GammaRegion {
    int32_t lowerLux, upperLux;
    std::vector<int32_t> table;
};

// TCE 3b1590: first region whose upper threshold is >= lux. Gaps between
// regions blend the preceding/current tables; points inside a region copy it.
// Above all upper thresholds the original leaves the destination untouched.
// This is LUT selection, not a replacement for TCE's full image processing.
inline bool selectGamma(int32_t lux, const std::vector<GammaRegion>& regions,
                        std::vector<int32_t>& output) {
    if (regions.empty()) return false;
    const size_t count = regions.front().table.size();
    if (!count || count > 65536 || regions.size() > 20)
        throw std::invalid_argument("Invalid TCE gamma table dimensions");
    for (size_t i = 0; i < regions.size(); ++i) {
        const auto& r = regions[i];
        if (r.lowerLux > r.upperLux || r.table.size() != count ||
            (i && r.lowerLux < regions[i-1].upperLux))
            throw std::invalid_argument("Invalid TCE gamma regions");
        for (int32_t v : r.table)
            if (v < 0 || v > 65535)
                throw std::invalid_argument("TCE gamma entry outside uint16 range");
    }
    for (size_t i = 0; i < regions.size(); ++i) {
        const auto& r = regions[i];
        if (r.upperLux < lux) continue;
        std::vector<int32_t> selected(count);
        if (i && lux < r.lowerLux) {
            // W-register subtraction in the donor is 32-bit. Reject differences
            // that overflow rather than reproducing malformed-metadata wrapping.
            const int64_t numerator = int64_t(r.lowerLux) - lux;
            const int64_t denominator = int64_t(r.lowerLux) - regions[i-1].upperLux;
            if (numerator > INT32_MAX || denominator <= 0 || denominator > INT32_MAX)
                throw std::invalid_argument("TCE gamma lux span overflow");
            const float previousWeight = float(numerator) / float(denominator);
            const float currentWeight = 1.f - previousWeight;
            for (size_t j = 0; j < count; ++j) {
                const float current = currentWeight * float(r.table[j]);
                const float mixed = std::fma(previousWeight, float(regions[i-1].table[j]), current);
                selected[j] = int32_t(mixed + .5f);
            }
        } else selected = r.table;
        output.swap(selected);
        return true;
    }
    return false;
}
} // namespace tce_contract
} // namespace vivo_nice
