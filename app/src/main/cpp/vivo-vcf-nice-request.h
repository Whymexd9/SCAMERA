#pragma once
#include "vivo-vcf-nice-hdr-plan.h"
#include <cstring>

namespace vivo_vcf {
// getNiceHdrCaptureControlInfo metadata publication, 102410..1029ec.
// Values already come from the stock AE query. No ISO or shutter conversion.
struct NiceHdrRequestContext {
    std::array<float,16> shortGain{}, shortShutter{};
    std::array<int32_t,3> rawHdrParams{};
    std::array<int32_t,2> evContent{};
    float captureDrcGain = 0;
    int32_t dualRawType = 0;
};
struct NiceHdrRequestMetadata {
    // vivo.parameter.VivoAlgoAECFrameControl: EV[16], gain[16], shutter[16].
    std::array<float,48> aec{};
    // Last 32-bit cell is an INTEGER type field, not a numeric float conversion.
    // setMetadata count=49 at 1028cc publishes all 196 bytes.
    struct ShortAec {
        std::array<float,48> values{};
        int32_t dualRawType = 0;
    } shortAec;
    std::array<int32_t,9> captureControl{};
    std::array<int32_t,3> rawHdrParams{};
    std::array<int32_t,2> evContent{};
    float captureDrcGain = 0;
};
static_assert(sizeof(NiceHdrRequestMetadata::ShortAec)==196);
static_assert(offsetof(NiceHdrRequestMetadata::ShortAec,dualRawType)==192);
struct NiceHdrCaptureBundle {
    NiceHdrPlan plan;
    NiceHdrRequestMetadata metadata;
};
inline NiceHdrCaptureBundle buildNiceHdrCapture(const NiceHdrQuery& query,
                                               const NiceHdrRequestContext& context,
                                               const NiceHdrPlan& initializedPlan) {
    NiceHdrCaptureBundle result;
    result.plan=initializedPlan;
    // One validated query supplies both internal batch/RAW control and HAL
    // payloads. Do not rebuild either side from unrelated preview metadata.
    applyNiceHdrQuery(query,result.plan);
    if(!std::isfinite(context.captureDrcGain))
        throw std::invalid_argument("Nonfinite NICE capture DRC gain");
    auto& m=result.metadata;
    const auto copy=[](auto& dst,size_t offset,const auto& src) {
        std::memcpy(dst.data()+offset,src.data(),sizeof(float)*16);
    };
    if(query.alternateExposureMode) {
        copy(m.aec,0,query.alternateEv);
        copy(m.shortAec.values,0,query.alternateShortEv);
        // Native memset + EV-only copies leave gain/shutter payloads zero.
        // This differs from preserving those fields in the initialized plan.
    } else {
        copy(m.aec,0,query.ev);copy(m.aec,16,query.gain);copy(m.aec,32,query.shutter);
        copy(m.shortAec.values,0,query.shortEv);
        copy(m.shortAec.values,16,context.shortGain);
        copy(m.shortAec.values,32,context.shortShutter);
        for(size_t i=0;i<query.futureCount;++i)
            if(!std::isfinite(context.shortGain[i])||!std::isfinite(context.shortShutter[i]))
                throw std::invalid_argument("Nonfinite NICE paired-short AE");
    }
    m.shortAec.dualRawType=context.dualRawType;
    m.captureControl[0]=int32_t(query.pastCount);
    m.captureControl[1]=int32_t(query.futureCount);
    // The donor publishes seven trailing zero words here. Internal one-frame
    // batches must NOT be copied into this separate nine-integer vendor field.
    m.rawHdrParams=context.rawHdrParams;
    m.evContent=context.evContent;
    m.captureDrcGain=context.captureDrcGain;
    return result;
}
} // namespace vivo_vcf
