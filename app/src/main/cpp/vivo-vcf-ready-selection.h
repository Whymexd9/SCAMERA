#pragma once
#include "vivo-vcf-frame-compatibility.h"
#include <algorithm>
#include <climits>
#include <stdexcept>
#include <vector>

namespace vivo_vcf {
struct TimedFrameCandidate {
    FrameCandidate frame;
    uint64_t timestamp;
};
struct ReadyFrameSelection {
    int offset;
    int referenceIndex;
    int exposureReferenceIndex;
    int firstCompatible, lastCompatible;
    bool onlyNeedNext;
};
// Arithmetic/compatibility part of modifiedOffseByMotionInfo, 0x1417e8--1422a8.
// Caller still owns readiness, metadata extraction, scene/config policy and
// translating the returned offset into past/future requests. In particular,
// preferReferenceAfterQueue is the RESULT of stock config helper 0x1426f0,
// not a guessed user preference. Debug property override must be disabled.
inline ReadyFrameSelection selectReadyFrames(const std::vector<TimedFrameCandidate>& frames,
        size_t originalQueueCount, uint32_t requested, uint64_t referenceTimestamp,
        FrameSelectionParameters parameters, bool preferReferenceAfterQueue,
        bool onlyNeedNext = false) {
    if (frames.empty() || frames.size() > INT_MAX || !requested || requested > INT_MAX)
        throw std::invalid_argument("VCF ready selection requires bounded nonempty input");
    const size_t initial = originalQueueCount > 3 ? originalQueueCount - 3 : 0;
    // Native code assumes this index exists after readiness filtering. Never
    // reproduce an out-of-bounds read when the caller violates that invariant.
    if (initial >= frames.size())
        throw std::invalid_argument("VCF original/ready queue mismatch");
    for (size_t i = 1; i < frames.size(); ++i)
        if (frames[i].timestamp < frames[i-1].timestamp)
            throw std::invalid_argument("VCF ready queue is not chronological");
    int reference = static_cast<int>(initial), exposureReference = reference;
    const int count = static_cast<int>(frames.size());
    for (int i = 1; i < count; ++i) {
        const auto lower = frames[i-1].timestamp, upper = frames[i].timestamp;
        if (referenceTimestamp >= lower && referenceTimestamp < upper) {
            reference = referenceTimestamp - lower > upper - referenceTimestamp ? i : i-1;
            // Stock retains the lower bracket's exposure even when the later
            // timestamp is closer. This is intentionally not nearest metadata.
            exposureReference = i-1;
            break;
        }
    }
    const bool afterQueue = referenceTimestamp > frames.back().timestamp;
    if (referenceTimestamp >= frames.back().timestamp)
        reference = exposureReference = count-1;
    const auto& ref = frames[exposureReference].frame;
    parameters.referenceExposureNs = ref.exposureNs;
    parameters.referenceIso = ref.iso;
    parameters.referenceStaggerShortExposure = ref.staggerShortExposure;
    parameters.referenceStaggerGain = ref.staggerGain;
    int left = reference, right = reference;
    for (int i = reference; i >= 0 && frameCompatible(frames[i].frame, parameters); --i) left = i;
    for (int i = reference; i < count && frameCompatible(frames[i].frame, parameters); ++i) right = i;
    const int half = static_cast<int>(requested / 2);
    const int compatible = right-left+1;
    int offset = reference-half;
    const int mode = parameters.selectionMode;
    const auto centered = [&]() {
        if (uint32_t(reference) < uint32_t(left) + uint32_t(half)) return left;
        // Original compares this subtraction unsigned; retain its behavior
        // when the half-window extends before zero.
        if (uint32_t(reference) <= uint32_t(right) - uint32_t(half)) return offset;
        return afterQueue && preferReferenceAfterQueue ? reference : right-int(requested)+1;
    };
    if (mode == 1 || mode == 3 || mode == 6) {
        offset = uint32_t(compatible) < requested ? (right >= count-1 ? left : right+1) : centered();
    } else if (mode == 4 || mode == 5) {
        if (uint32_t(count) < requested) {
            offset = left;
            if (count > compatible) { onlyNeedNext = true; offset = count; }
        } else if (uint32_t(compatible) < requested) {
            onlyNeedNext = true;
            offset = count;
        } else offset = centered();
    }
    return {std::max(0, offset), reference, exposureReference, left, right, onlyNeedNext};
}
} // namespace vivo_vcf
