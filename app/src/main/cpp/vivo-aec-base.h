#pragma once
#include "vivo-aec-decrease.h"
#include "vivo-aec-adjust.h"
#include "vivo-aec-table.h"

namespace vivo_aec {
inline float nativeLogEv(float ratio) {
    if(!std::isfinite(ratio) || ratio<=0)throw std::invalid_argument("Invalid native EV ratio");
    return static_cast<float>(std::log10(static_cast<double>(ratio))*3.321928024291992);
}
struct BaseInput {
    uint64_t historyShutter;
    float historyGain, historyProduct, targetProduct, deltaEv, deltaMultiplier;
    int mode;
    NiceCaptureFlags flags;
    uint32_t tuningVersion;
    float versionRatio, bandingThreshold, bandingMetric;
    bool bandingDetected, forceHistoryArbitration, resetDelta;
    bool retainHistoryCorrection;
    float historyCorrection;
    int32_t priorCorrectionMilli;
};
struct BaseResult {
    Exposure normal;
    DecreaseResult decrease;
    float targetRatio, correctionMilli;
    int32_t retainedCorrectionMilli;
};
inline BaseResult plannedBaseExposure(const BaseInput& p, DecreaseResult decrease,
        float divisor,const std::vector<TableRow>& rows,NormalAdjustment adjustment,
        const std::vector<BlurRow>& blur={},float motion=0.f) {
    if(p.mode<0 || p.mode>14 || !p.historyShutter || p.historyGain<=0 ||
       p.historyProduct<=0 || p.targetProduct<=0 || p.deltaMultiplier<=0)
        throw std::invalid_argument("Invalid native base context");
    Exposure n{static_cast<float>(p.historyShutter),p.historyGain,0,0};
    float ratio=p.targetProduct/p.historyProduct;
    const bool bypass=((0x39c0u>>p.mode)&1u)!=0 ||
        (p.mode==9 && (p.flags.base==0 || (p.flags.family==1 && p.flags.alternate==1))) ||
        ((p.mode<9 || p.mode>13) && p.tuningVersion>=256 && std::abs(p.versionRatio-1.f)<1.e-6f);
    auto milli=[](float v) {
        if(!std::isfinite(v) || double(v)<INT32_MIN || double(v)>INT32_MAX)
            throw std::invalid_argument("Native base milli EV overflow");
        return static_cast<int32_t>(v);
    };
    if(!bypass) {
        adjustment.mode=(adjustment.period/1.1f<=n.shutter && decrease.ev<-.01f &&
            adjustment.bandingEnabled && p.bandingMetric>=p.bandingThreshold &&
            adjustment.period>0 && p.bandingDetected) ? 1 : 0;
        auto arbitrate=[&](float product) {
            const auto selected=tableExposure(product,divisor,rows);
            n.shutter=static_cast<float>(selected.shutter);n.gain=selected.gain;
            auto a=normalExposureAdjustment(n,decrease.divisor,adjustment,blur,motion);
            n=a.value;decrease.divisor=a.correction;
            decrease.ev=nativeLogEv(decrease.divisor);
        };
        if(p.forceHistoryArbitration) {
            arbitrate(p.historyProduct);
            decrease.evMilli=milli((decrease.ev-nativeLogEv(p.deltaMultiplier))*1000.f);
        } else if(p.mode==10 || (p.mode==9 && p.flags.family==1))arbitrate(p.historyProduct);
        else if(p.flags.decrease>=1) {
            if(p.flags.alternate==1) {
                auto a=normalExposureAdjustment(n,decrease.divisor,adjustment,blur,motion);
                n=a.value;decrease.divisor=a.correction;decrease.ev=nativeLogEv(decrease.divisor);
                decrease.evMilli=milli((decrease.ev-nativeLogEv(p.deltaMultiplier))*1000.f);
            } else {
                arbitrate(p.historyProduct*p.deltaMultiplier);
                decrease.evMilli=milli(decrease.ev*1000.f);
            }
        } else {
            arbitrate(p.targetProduct*p.deltaMultiplier);
            decrease.evMilli=milli(decrease.ev*1000.f);ratio=0.f;
        }
    }
    if(p.resetDelta || !p.retainHistoryCorrection) {
        ratio=p.targetProduct/p.historyProduct;
        decrease.evMilli=milli(p.deltaEv*-1000.f);
    }
    // A zero history correction occurs in real native contexts. log10(0)
    // gives -infinity and ARM64 FCVTZS saturates to INT32_MIN.
    int32_t retained=p.retainHistoryCorrection ? p.priorCorrectionMilli :
        (p.historyCorrection==0.f ? INT32_MIN : milli(nativeLogEv(p.historyCorrection)*1000.f));
    float correctionMilli=0.f;
    if(retained>=1) {
        int64_t difference=int64_t(decrease.evMilli)-retained;
        if(difference<INT32_MIN || difference>INT32_MAX)throw std::invalid_argument("Base correction overflow");
        decrease.evMilli=static_cast<int32_t>(difference);correctionMilli=static_cast<float>(retained);
    }
    n.flags=1;
    return {n,decrease,ratio,correctionMilli,retained};
}
} // namespace vivo_aec
