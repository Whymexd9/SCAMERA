#pragma once
#include "vivo-vcf-nice-request.h"

namespace vivo_vcf {
// Inputs read by previewDetectMetaOut from the current preview metadata.
// Units and all inactive array words remain untouched. These are NOT the
// per-RAW Vivo3rdAlgoAECFrameControl (35-float) records used for processing.
struct NicePreviewAe {
    std::array<float,48> primary{};
    NiceHdrRequestMetadata::ShortAec paired;
    std::array<int32_t,2> evContent{};
};

// Port of the NICE branch ad30c..ad3ec in libvivo.vas.adapter.vcf.so.
// sceneResultFlags belongs to the caller's scene result; do not infer it from
// image brightness, Camera2 ISO, sensor mode or mere presence of an AEC tag.
// The paired type is returned separately: its subsequent route to the query
// context has not yet been proven, so this helper must not silently replace it.
struct NicePreviewAeBinding {
    NiceHdrQuery query;
    NiceHdrRequestContext context;
    int32_t pairedType;
};
inline NicePreviewAeBinding bindNicePreviewAe(uint32_t sceneResultFlags,
                                             const NicePreviewAe& ae,
                                             const NiceHdrQuery& decision,
                                             const NiceHdrRequestContext& context) {
    if(!(sceneResultFlags & 0x30000u))
        throw std::invalid_argument("Preview AE belongs to a different capture branch");
    NicePreviewAeBinding out{decision,context,ae.paired.dualRawType};
    auto copy=[](auto& dst,const auto& src,size_t offset) {
        std::memcpy(dst.data(),src.data()+offset,16*sizeof(float));
    };
    copy(out.query.ev,ae.primary,0);
    copy(out.query.gain,ae.primary,16);
    copy(out.query.shutter,ae.primary,32);
    copy(out.query.shortEv,ae.paired.values,0);
    copy(out.context.shortGain,ae.paired.values,16);
    copy(out.context.shortShutter,ae.paired.values,32);
    out.context.evContent=ae.evContent;
    return out;
}

// Connect the preview AE binding to the existing plan/payload builder. Count,
// scene, alternate exposure and DRC inputs still come from their own producers.
// Reject a conflicting paired-type route instead of fabricating that context.
inline NiceHdrCaptureBundle buildNiceHdrCaptureFromPreviewAe(
        uint32_t sceneResultFlags,const NicePreviewAe& ae,
        const NiceHdrQuery& decision,const NiceHdrRequestContext& context,
        const NiceHdrPlan& initial) {
    const auto bound=bindNicePreviewAe(sceneResultFlags,ae,decision,context);
    if(bound.pairedType!=context.dualRawType)
        throw std::invalid_argument("Preview paired type does not match query context");
    return buildNiceHdrCapture(bound.query,bound.context,initial);
}
} // namespace vivo_vcf
