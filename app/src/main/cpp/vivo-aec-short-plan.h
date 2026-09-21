#pragma once
#include "vivo-aec-base.h"
namespace vivo_aec {
struct ShortPlanInput {
    int mode;
    NiceCaptureFlags flags;
    float targetProduct, historyProduct, deltaEv, decreaseEv, shortDeltaEv;
    float highlight, highlightThreshold, runtimeRatio;
    float bandingMetric, bandingThreshold, niceBandingThreshold;
    float extraRatioCap, extraBandingThreshold, specialBandingThreshold, sensorEvLimit;
    bool bandingEnabled, bandingDetected, specialBanding, nativeShortMode;
    int sensorType;
    bool sceneFlag, cameraFlag;
    EvGaps gaps;
};
struct ShortPlanResult { Exposure shortFrame,extraShortFrame; bool enabled; };
inline ShortPlanResult plannedShortExposures(Exposure normal,const ShortPlanInput& p,
        ShortExposureLimits limits) {
    if(p.mode<0 || p.mode>14 || p.runtimeRatio<=0 || p.historyProduct<=0 ||
       p.targetProduct<=0 || normal.shutter<=0 || normal.gain<=0)
        throw std::invalid_argument("Invalid native short context");
    if(p.highlight<p.highlightThreshold)return {{},{},false};
    const bool nice=usesAlternateTuningBank(p.mode);
    float threshold=p.specialBandingThreshold<=.001f ? .01f : p.specialBandingThreshold;
    if(!p.specialBanding)threshold=nice ? p.niceBandingThreshold : p.bandingThreshold;
    float minimum=std::abs(limits.tableMinGain)>=1.e-6f ? limits.tableMinGain : limits.sensorMinGain;
    const float reference=normal.shutter*normal.gain;
    const bool banding=p.bandingEnabled && p.bandingMetric>=threshold &&
        limits.bandingPeriod>0 && p.bandingDetected;
    int shortMode=0;
    float productFloor=(limits.bandingPeriod*minimum)*(!p.specialBanding && !nice ? 1.3f : 1.f);
    if(banding && normal.shutter>=limits.bandingPeriod/1.1f && reference>=productFloor)shortMode=2;
    const float extraEv=p.deltaEv>0 ?
        static_cast<float>(std::max(-double(p.decreaseEv)-double(p.deltaEv),-4.)) : 0.f;
    const float halfEv=extraEv*.5f;
    Exposure s=normal;
    s.ev=halfEv+p.shortDeltaEv;
    if(!p.nativeShortMode)s.ev=std::min(s.ev,nativeLogEv(1.f/p.runtimeRatio));
    s.ev=std::max(p.gaps.shortEv,std::min(s.ev,-2.f));
    limits.bandingMode=shortMode;
    s=shortExposure(s,std::exp2(std::abs(s.ev)),limits);
    const float sProduct=s.shutter*s.gain;
    const float actualShortEv=nativeLogEv(sProduct/reference);
    s.ev=std::fmin(actualShortEv,0.f);
    const float targetRatio=p.targetProduct/p.historyProduct;
    const float ratioCap=std::max(2.f,std::min(p.extraRatioCap,targetRatio));
    float extraThreshold=p.extraBandingThreshold<=.001f ? 9.9f : p.extraBandingThreshold;
    if(p.specialBanding)extraThreshold=p.specialBandingThreshold<=.001f ? .01f : p.specialBandingThreshold;
    int extraMode=0;
    if(nice && banding && s.shutter>=limits.bandingPeriod/1.1f) {
        extraMode=3;
        if((sProduct<=p.historyProduct && std::exp2(std::abs(s.ev))>=ratioCap) ||
           p.bandingMetric>extraThreshold)extraMode=4;
    }
    Exposure es=normal;es.flags=0;
    const bool sensor=p.sensorType==6 && p.flags.base==0 && p.mode==9;
    if(sensor && p.sceneFlag && p.cameraFlag)es.ev=std::max(p.gaps.extraShortEv,nativeLogEv(1.f/p.runtimeRatio));
    else {
        es.ev=(halfEv+s.ev)+p.shortDeltaEv;
        es.ev=std::min(actualShortEv-2.f,es.ev);
        es.ev=std::max(p.gaps.extraShortEv,es.ev);
        if(sensor) {
            const float limit=p.sensorEvLimit<=.001f ? 5.f : p.sensorEvLimit;
            es.ev=std::max(nativeLogEv(1.f/p.runtimeRatio)-limit,es.ev);
        }
    }
    limits.bandingMode=extraMode;
    es=shortExposure(es,std::exp2(std::abs(es.ev)),limits);
    es.ev=std::fmin(nativeLogEv((es.shutter*es.gain)/reference),0.f);
    // The native optional minimum-shutter branch replaces S, not ES.
    if(std::abs(es.shutter-static_cast<float>(limits.tableMinShutter))<1.e-6f && p.nativeShortMode) {
        s=es;s.ev=es.ev*.5f;s.shutter=es.shutter*std::exp2(std::abs(s.ev));
    }
    return {s,es,true};
}
} // namespace vivo_aec
