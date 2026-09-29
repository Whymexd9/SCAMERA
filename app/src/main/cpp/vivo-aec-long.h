#pragma once
#include "vivo-aec-adjust.h"
#include "vivo-aec-table.h"
#include "vivo-aec-scene.h"

namespace vivo_aec {
// VivoEVPlusExpCalc at 0x17a238, donor b2e3e124... . This function receives
// an already selected EV multiplier/table/context; it does not select a scene.
inline Exposure longExposure(Exposure value,float multiplier,float shutterCap,
        float tableDivisor,const std::vector<TableRow>& rows,const NormalAdjustment& context,
        const std::vector<BlurRow>& blur={},float motion=0.f) {
    if (!std::isfinite(value.shutter) || value.shutter<=0.f ||
        !std::isfinite(value.gain) || value.gain<=0.f || !std::isfinite(value.ev) ||
        !std::isfinite(multiplier) || multiplier<=0.f ||
        !std::isfinite(shutterCap) || shutterCap<=0.f)
        throw std::invalid_argument("Invalid stock long-exposure input");
    // Validate the same selected table even when the direct branch bypasses
    // lookup. Never fabricate a table from Camera2 min/max sensitivity.
    const float product=(value.shutter*value.gain)*multiplier;
    const auto tableValue=tableExposure(product,tableDivisor,rows);
    if (context.mode!=0 || context.minGain!=rows.front().gain ||
        context.maxGain!=rows.back().gain ||
        context.minShutter!=static_cast<float>(rows.front().shutter) ||
        context.maxShutter!=static_cast<float>(rows.back().shutter))
        throw std::invalid_argument("Long adjustment does not match selected stock table");
    const float candidate=value.shutter*multiplier;
    const float maxShutter=static_cast<float>(rows.back().shutter);
    const float maxProduct=rows.back().gain*maxShutter;
    if (candidate<=shutterCap && candidate<=maxShutter && candidate*value.gain<=maxProduct) {
        value.shutter=candidate;return value;
    }
    value.shutter=static_cast<float>(tableValue.shutter);value.gain=tableValue.gain;
    return normalExposureAdjustment(value,1.f,context,blur,motion).value;
}

// EVPlusCalc (0x179fec). All history fields below are from the same stock
// process-params snapshot; they cannot be reconstructed from a single ISO.
struct LongPlanInput {
    uint64_t historyShutter;     // params+0
    float historyGain;          // params+8
    float historyRatio;         // params+0xc
    float longDeltaEv;          // params+0x38
    float targetRatio;          // params+0x5c
    float deltaEv;              // params+0x60
    float deltaMultiplier;      // params+0xd8 (exp2f(deltaEv))
    float longGap;              // EVGap+8
    int runMode;
    NiceCaptureFlags flags;
};

inline Exposure plannedLongExposure(Exposure normal, const LongPlanInput& in,
        float shutterCap, float tableDivisor, const std::vector<TableRow>& rows,
        const NormalAdjustment& adjustment, const std::vector<BlurRow>& blur={},
        float motion=0.f) {
    const float positive[]={normal.shutter,normal.gain,in.historyGain,
        in.historyRatio,in.targetRatio,in.deltaMultiplier};
    for (float v:positive) if (!std::isfinite(v) || v<=0.f)
        throw std::invalid_argument("Missing stock long-plan history");
    if (!in.historyShutter || !std::isfinite(in.longDeltaEv) ||
        !std::isfinite(in.deltaEv) || !std::isfinite(in.longGap) || in.longGap<2.f)
        throw std::invalid_argument("Invalid stock long-plan EV context");
    Exposure value=normal;
    float reference=normal.shutter*normal.gain;
    float ev=std::min(in.longGap,std::max(in.longDeltaEv-in.deltaEv,1.f));
    // The native log10 multiplier is a float-rounded constant widened to double,
    // not log2 or a newly rounded full-precision 1/log10(2).
    constexpr double log2Factor=3.321928024291992;
    if (in.runMode!=10 && !(in.runMode==9 && (in.flags.base==1 || in.flags.family==1))) {
        if (in.runMode==9 && in.flags.base==0) {
            value.shutter=static_cast<float>(in.historyShutter);
            value.gain=in.historyGain;
            reference=value.gain*value.shutter;
            const float ratio=(in.targetRatio/in.historyRatio)*in.deltaMultiplier;
            ev=static_cast<float>(std::log10(static_cast<double>(ratio))*log2Factor);
        } else ev=0.f;
    }
    value.ev=std::clamp(ev,1.f,in.longGap);
    value=longExposure(value,std::exp2(std::abs(value.ev)),shutterCap,
        tableDivisor,rows,adjustment,blur,motion);
    value.flags=0;
    const float ratio=(value.shutter*value.gain)/reference;
    value.ev=static_cast<float>(std::log10(static_cast<double>(ratio))*log2Factor);
    if (!std::isfinite(value.ev)) throw std::invalid_argument("Invalid stock long-plan result");
    return value;
}
} // namespace vivo_aec
