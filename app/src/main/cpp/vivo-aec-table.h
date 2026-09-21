#pragma once
#include <cmath>
#include <cstdint>
#include <stdexcept>
#include <vector>

namespace vivo_aec {
struct TableRow {
    float gain;
    uint64_t shutter;
    bool shutterFirst;
};
struct TableExposure {
    float gain;
    uint64_t shutter;
    float product;
};

// CExpTable::VivoExpTableEntryLiteLookUp, donor b2e3e124..., 0x1774d4.
// The table and its divisor must come from the selected stock tuning context.
// Gain is native gain, NOT Camera2 ISO. Shutter units follow the donor table.
inline TableExposure tableExposure(float requestedProduct, float divisor,
                                   const std::vector<TableRow>& rows) {
    if (!std::isfinite(requestedProduct) || requestedProduct <= 0.f ||
        !std::isfinite(divisor) || divisor <= 0.f || rows.size() < 2 || rows.size() > 4096)
        throw std::invalid_argument("Invalid stock exposure table input");
    float previous = 0.f;
    for (const auto& row : rows) {
        const float product = row.gain * static_cast<float>(row.shutter);
        if (!std::isfinite(row.gain) || row.gain <= 0.f || !row.shutter ||
            row.shutter > INT64_MAX || !std::isfinite(product) || product <= previous)
            throw std::invalid_argument("Invalid stock exposure table row");
        previous = product;
    }
    const float wanted = requestedProduct / divisor;
    if (!std::isfinite(wanted)) throw std::invalid_argument("Stock exposure product overflow");
    const float minimum = rows.front().gain * static_cast<float>(rows.front().shutter);
    const float maximum = rows.back().gain * static_cast<float>(rows.back().shutter);
    const float target = std::fmin(std::fmax(wanted, minimum), maximum);
    size_t upper = 0;
    while (upper < rows.size() &&
           !(target < rows[upper].gain * static_cast<float>(rows[upper].shutter))) ++upper;
    if (upper < 1) upper = 1;
    const TableRow& lower = rows[upper - 1];
    const float shutter = static_cast<float>(lower.shutter);
    const float ratio = target / (lower.gain * shutter);
    TableExposure out{lower.gain, lower.shutter, target};
    if (ratio > 1.f) {
        // At the last endpoint ratio is exactly one; no fictitious extra row.
        if (upper >= rows.size()) throw std::invalid_argument("Stock table interval overflow");
        const TableRow& next = rows[upper];
        float gainFactor, shutterFactor;
        if (lower.shutterFirst) {
            const float limit = static_cast<float>(next.shutter) / shutter;
            shutterFactor = ratio < limit ? ratio : limit;
            gainFactor = ratio < limit ? 1.f : ratio / limit;
        } else {
            const float limit = next.gain / lower.gain;
            gainFactor = ratio < limit ? ratio : limit;
            shutterFactor = ratio < limit ? 1.f : ratio / limit;
        }
        out.gain = lower.gain * gainFactor;
        // The donor truncates the increment, then adds it to the integer base.
        // Rounding the complete float shutter produces different RAW exposure.
        const float increment = (shutterFactor - 1.f) * shutter;
        if (!std::isfinite(increment) || increment < 0.f || increment >= 0x1p63f)
            throw std::invalid_argument("Invalid stock shutter increment");
        const uint64_t extra = static_cast<uint64_t>(increment);
        if (extra > uint64_t(INT64_MAX) - out.shutter)
            throw std::invalid_argument("Stock shutter overflow");
        out.shutter += extra;
        if (!std::isfinite(out.gain) || out.gain <= 0.f)
            throw std::invalid_argument("Invalid stock table gain");
    }
    return out;
}
} // namespace vivo_aec
