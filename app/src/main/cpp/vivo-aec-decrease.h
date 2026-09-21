#pragma once
#include "vivo-aec-scene.h"
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <stdexcept>

namespace vivo_aec {
struct DecreaseTuning {
    float threshold, ev, threshold5, ev5, thresholdLow, evLow;
    float knee0a, knee0b, knee1a, knee1b, knee2a, knee2b;
    float motionFloor, versionRatio;
};
struct DecreaseInput {
    int mode;
    NiceCaptureFlags flags;
    uint32_t tuningVersion;
    float historyProduct, targetProduct, deltaEv, deltaMultiplier, meteredProduct;
    float motion, highlight, highlightReference;
    bool limitDecrease, highlightEnabled;
    float contextEv;
    bool contextEvEnabled;
};
struct DecreaseResult { float ev, divisor; int32_t ratioMilli, evMilli; };

// decreaseEVCalc, pinned Vivo donor at 0x178b3c. Context values retain
// native units; none of these inputs is Camera2 ISO or an app motion estimate.
inline DecreaseResult decreaseExposure(const DecreaseInput& p, const DecreaseTuning& c) {
    const float values[]={p.historyProduct,p.targetProduct,p.deltaEv,p.deltaMultiplier,
        p.meteredProduct,p.motion,p.highlight,p.highlightReference,p.contextEv,
        c.threshold,c.ev,c.threshold5,c.ev5,c.thresholdLow,c.evLow,
        c.knee0a,c.knee0b,c.knee1a,c.knee1b,c.knee2a,c.knee2b,c.motionFloor,c.versionRatio};
    for(float v:values) if(!std::isfinite(v))throw std::invalid_argument("Nonfinite decrease context");
    if(p.mode<0 || p.mode>14 || p.historyProduct<=0 || p.targetProduct<=0 || p.deltaMultiplier<=0)
        throw std::invalid_argument("Invalid decrease context");
    const bool nativeBypass=((0x3dc0u>>p.mode)&1u)!=0 ||
        (p.mode==9 && (p.flags.base==0 || p.flags.family==1));
    const bool versionBypass=(p.mode<9 || p.mode>13) && p.tuningVersion>=256 &&
        std::abs(c.versionRatio-1.f)<1.e-6f;
    float ev=0.f;
    if(!nativeBypass && !versionBypass && p.mode!=14) {
        float threshold=c.threshold, limit=c.ev;
        const float target=p.targetProduct*p.deltaMultiplier;
        if(p.mode<=3) {
            threshold=c.thresholdLow;
            limit=target>60000002048.f ? 0.f : c.evLow;
        } else if(p.mode==5) {threshold=c.threshold5;limit=c.ev5;}
        else if(p.mode==9) {
            if(target>c.knee0a*c.knee0b) {
                if(target>c.knee1a*c.knee1b) {
                    if(target>c.knee2a*c.knee2b) {threshold=0.f;limit=0.f;}
                    else {threshold=c.thresholdLow;limit=c.evLow;}
                } else {threshold=c.threshold5;limit=c.ev5;}
            }
        }
        ev=p.limitDecrease ? std::max(limit,-2.f) : limit;
        if(p.flags.decrease>=1) {
            float ratio=p.targetProduct/p.historyProduct;
            float logEv=static_cast<float>(std::log10(static_cast<double>(ratio))*3.321928024291992);
            ev=std::min(ev+logEv,0.f);
        }
        float motionEv=-0.f;
        if(p.motion>c.motionFloor) {
            motionEv=ev;
            if(p.motion<threshold)motionEv=((ev+0.f)*(p.motion-c.motionFloor))/(threshold-c.motionFloor);
        }
        float highlightEv=-0.f;
        if(p.highlight>0.f) {
            const bool strong=p.highlight>p.highlightReference && p.highlightEnabled;
            const float bound=strong ? .1f : .15f;
            highlightEv=p.highlight>=bound ? -1.f : p.highlight/(-bound);
        }
        const float combined=highlightEv<motionEv ? highlightEv : motionEv;
        ev=ev>combined ? ev : combined;
    }
    if(p.contextEvEnabled && std::abs(p.contextEv)>=1.e-6f)ev=0.f-p.contextEv;
    float divisor=std::exp2(ev);
    float milli=(nativeBypass || versionBypass) ? p.deltaEv*-1000.f : ev*1000.f;
    float ratio=p.meteredProduct>1.e-4f ?
        ((p.targetProduct*p.deltaMultiplier)/p.meteredProduct)*1000.f : 1000.f;
    // Avoid undefined float-to-int conversion for corrupt runtime metadata.
    if(!std::isfinite(divisor) || divisor<=0 || !std::isfinite(milli) ||
       !std::isfinite(ratio) || double(milli)<INT32_MIN || double(milli)>INT32_MAX ||
       double(ratio)<INT32_MIN || double(ratio)>INT32_MAX)
        throw std::invalid_argument("Decrease result outside native domain");
    return {ev,divisor,static_cast<int32_t>(ratio),static_cast<int32_t>(milli)};
}
} // namespace vivo_aec
