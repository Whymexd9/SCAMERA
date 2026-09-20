#pragma once
#include "vivo-vcf-capture-control.h"
#include <limits>
#include <stdexcept>

namespace vivo_vcf {
// Inputs to getNiceHdrCaptureControlInfo in the PD2454 VAS adapter.
// These are outputs of the scene/AE decision, not estimates from ISO or gyro.
// Gain/shutter retain native units; this is not a Camera2 request converter.
struct NiceHdrQuery {
    static constexpr size_t Capacity = 16;
    uint32_t pastCount = 0, futureCount = 0;
    std::array<float, Capacity> ev{}, gain{}, shutter{}, shortEv{};
    std::array<float, Capacity> alternateEv{}, alternateShortEv{};
    bool alternateExposureMode = false, imageEchoWithPast = false;
    int32_t sceneType = 0;
};
struct NiceHdrPlan {
    CaptureControlFields control;
    // QueryToShot's separate RAW descriptors carry short EV, not normal EV.
    std::array<CaptureFrameControl, NiceHdrQuery::Capacity> rawFrames{};
    int32_t imageEchoFrameIndex = -1;
    int32_t accumulatedShutter = 0;
};

// Port of 101fa4..1022c4 and 1023ec..102410. Sensor/seamless branches,
// vendor-metadata publishing and scene/AE estimation are separate operations.
// Preserve untouched AE fields of past frames and EV-only alternate frames;
// the donor does not overwrite them with fabricated gain/shutter defaults.
// Reject malformed query data before mutating the caller's initialized plan.
inline void applyNiceHdrQuery(const NiceHdrQuery& q, NiceHdrPlan& plan) {
    if (q.pastCount > q.Capacity || q.futureCount > q.Capacity - q.pastCount ||
        q.pastCount + q.futureCount == 0)
        throw std::invalid_argument("NICE HDR query exceeds native batch capacity");
    float shutterSum = 0.f;
    for (uint32_t i = 0; i < q.futureCount; ++i) {
        if (q.alternateExposureMode) {
            if (!std::isfinite(q.alternateEv[i]) || !std::isfinite(q.alternateShortEv[i]))
                throw std::invalid_argument("Nonfinite NICE HDR alternate exposure");
        } else {
            if (!std::isfinite(q.ev[i]) || !std::isfinite(q.gain[i]) ||
                !std::isfinite(q.shutter[i]) || !std::isfinite(q.shortEv[i]))
                throw std::invalid_argument("Nonfinite NICE HDR exposure");
            shutterSum += q.shutter[i];
        }
    }
    // Native FCVTZS truncates toward zero. Reject overflow rather than rely on
    // undefined C++ conversion; do not reinterpret the native shutter unit.
    if (!std::isfinite(shutterSum) || double(shutterSum) < std::numeric_limits<int32_t>::min() ||
        double(shutterSum) > std::numeric_limits<int32_t>::max())
        throw std::invalid_argument("NICE HDR shutter sum outside int32");
    auto result = plan;
    auto& c = result.control;
    c.frameCount = c.batchCount = q.pastCount + q.futureCount;
    for (uint32_t i = 0; i < c.frameCount; ++i) {
        c.batchAlgoTypes[i] = 1;
        c.batchFrameCounts[i] = 1;
        c.frames[i].format = result.rawFrames[i].format = 0x12;
        c.frames[i].direction = result.rawFrames[i].direction = i < q.pastCount ? 0 : 1;
    }
    for (uint32_t i = 0; i < q.futureCount; ++i) {
        const uint32_t index = q.pastCount + i;
        auto& f = c.frames[index];
        f.ev = q.alternateExposureMode ? q.alternateEv[i] : q.ev[i];
        result.rawFrames[index].ev = q.alternateExposureMode ? q.alternateShortEv[i] : q.shortEv[i];
        if (!q.alternateExposureMode) {
            f.gain = q.gain[i];
            f.shutter = q.shutter[i];
        }
        // 0x42ca0000 is the stock sentinel 101.f, not an EV of zero.
        if ((!q.pastCount || q.imageEchoWithPast) && f.ev == 101.f)
            result.imageEchoFrameIndex = int32_t(index);
    }
    result.accumulatedShutter = int32_t(shutterSum);
    if (q.pastCount) c.frameCatchMode = q.sceneType == 31 || q.sceneType == 8 ? 3 : 4;
    plan = result;
}
} // namespace vivo_vcf
