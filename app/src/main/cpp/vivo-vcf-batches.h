#pragma once
#include "vivo-vcf-capture-control.h"
#include <stdexcept>
#include <vector>

namespace vivo_vcf {
struct CaptureBatchSlice {
    uint32_t sourceBatch, firstFrame, frameCount, algoType, direction;
};
// Input-payload order, before FeatureHandler's past/future partition. This
// cannot be submitted as Camera2 requests: shutter/gain units remain unmapped.
inline std::vector<CaptureBatchSlice> captureBatchSlices(const CaptureControlFields& c) {
    if (!c.frameCount || c.frameCount > c.MaxFrames || !c.batchCount || c.batchCount > c.MaxBatches)
        throw std::invalid_argument("Invalid VCF frame/batch count");
    std::vector<CaptureBatchSlice> batches;
    uint32_t offset = 0;
    for (uint32_t i = 0; i < c.batchCount; ++i) {
        const uint32_t count = c.batchFrameCounts[i];
        // Native processRequest reads the first member unconditionally. These
        // checks protect the adapter, rather than emulate malformed HAL input.
        if (!count || count > c.frameCount - offset)
            throw std::invalid_argument("VCF batch extends beyond frame list");
        const uint32_t direction = c.frames[offset].direction;
        // Only 0 (past) and 1 (future) have a verified scheduling interpretation.
        if (direction > 1) throw std::invalid_argument("Unknown VCF batch direction");
        for (uint32_t j = 1; j < count; ++j)
            if (c.frames[offset+j].direction != direction)
                throw std::invalid_argument("VCF batch has mixed directions");
        batches.push_back({i, offset, count, c.batchAlgoTypes[i], direction});
        offset += count;
    }
    if (offset != c.frameCount) throw std::invalid_argument("VCF unassigned frames");
    return batches;
}
struct BatchedFrameCounts { uint32_t all = 0, future = 0; };
// Original DecisionRule::calcAllFrames (0x1409b0) and calcFutureFrames
// (0x140a10): the latter counts exactly direction==1, not every nonzero value.
// No classification of other enum values is implied by the difference.
inline BatchedFrameCounts countBatchedFrames(
        const std::vector<std::vector<CaptureFrameControl>>& batches) {
    BatchedFrameCounts counts;
    for (const auto& batch : batches) {
        counts.all += static_cast<uint32_t>(batch.size());
        for (const auto& frame : batch) counts.future += frame.direction == 1;
    }
    return counts;
}
} // namespace vivo_vcf
