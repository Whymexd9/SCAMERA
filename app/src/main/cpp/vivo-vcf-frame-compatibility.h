#pragma once
#include <cstdint>
#include <cmath>
#include <limits>

namespace vivo_vcf {
// libvcf_session.so 0x1429ec, called from modifiedOffseByMotionInfo.
// This is a candidate compatibility predicate, not a complete frame selector.
struct FrameCandidate {
    uint64_t exposureNs;
    uint32_t iso;
    float staggerShortExposure;
    float staggerGain;
    bool aecSettled;
};
struct FrameSelectionParameters {
    uint64_t referenceExposureNs;
    uint64_t referenceIso;
    float referenceStaggerShortExposure;
    float referenceStaggerGain;
    float exposureTolerance;
    float gainTolerance;
    int selectionMode;
    int captureType;
    uint32_t scene;
    int auxiliaryMode;
};
// ARM FCVTZU saturates; express it without undefined out-of-range C++ casts.
inline uint64_t saturatingU64(double x) {
    if (!(x > 0.0)) return 0;
    if (x >= 18446744073709551616.0) return UINT64_MAX;
    return static_cast<uint64_t>(x);
}
inline bool integerWindow(uint64_t sample, uint64_t reference, double tolerance) {
    const double ref = static_cast<double>(reference);
    return sample >= saturatingU64(ref * (1.0 - tolerance)) &&
           sample <= saturatingU64(ref * (1.0 + tolerance));
}
inline bool frameCompatible(const FrameCandidate& f, const FrameSelectionParameters& p) {
    // Modes retain numeric donor values: their enum names are not established.
    if (p.selectionMode != 6 && p.captureType == 0xf00) {
        const float low = static_cast<float>(double(p.referenceStaggerShortExposure) * 0.8);
        const float high = static_cast<float>(double(p.referenceStaggerShortExposure) * 1.2);
        const bool inRange = f.staggerShortExposure >= low && f.staggerShortExposure <= high;
        return inRange && (p.selectionMode != 4 || f.aecSettled);
    }
    if (p.selectionMode == 6) {
        if (p.scene == 0xc00000 && (p.auxiliaryMode == 0x502 || p.auxiliaryMode == 6)) {
            const float loExp = static_cast<float>(double(p.referenceStaggerShortExposure) * (1.0 - double(p.exposureTolerance)));
            const float hiExp = static_cast<float>(double(p.referenceStaggerShortExposure) * (1.0 + double(p.exposureTolerance)));
            const float loGain = static_cast<float>(double(p.referenceStaggerGain) * (1.0 - double(p.gainTolerance)));
            const float hiGain = static_cast<float>(double(p.referenceStaggerGain) * (1.0 + double(p.gainTolerance)));
            return f.staggerShortExposure >= loExp && f.staggerShortExposure <= hiExp &&
                   f.staggerGain >= loGain && f.staggerGain <= hiGain;
        }
        return integerWindow(f.exposureNs, p.referenceExposureNs, double(p.exposureTolerance)) &&
               integerWindow(f.iso, p.referenceIso, double(p.gainTolerance));
    }
    // Use literal donor coefficients, not 1 +/- a float approximation of .2.
    const auto matches = [](uint64_t value, uint64_t ref) {
        return value >= saturatingU64(double(ref) * 0.8) &&
               value <= saturatingU64(double(ref) * 1.2);
    };
    if (!matches(f.exposureNs, p.referenceExposureNs)) return false;
    if (p.selectionMode == 5 && !matches(f.iso, p.referenceIso)) return false;
    return (p.selectionMode != 4 && p.selectionMode != 5) || f.aecSettled;
}
} // namespace vivo_vcf
