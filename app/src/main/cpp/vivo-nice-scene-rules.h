#pragma once
#include <cmath>
#include <cstdint>
#include <initializer_list>
#include <stdexcept>

namespace vivo_nice {
// Small complete VAF decisions; these consume stock scene-domain measurements,
// NOT an arbitrary gyro norm, ISO-derived lux or motion from RAW alignment.
struct HdrMotionClass { bool shaking1, shaking2; };
inline HdrMotionClass classifyHdrMotion(float value,float lower,float upper) {
    if(!std::isfinite(value)||!std::isfinite(lower)||!std::isfinite(upper))
        throw std::invalid_argument("Missing NICE scene motion input");
    // 29ab8c / 29ac44. Preserve boundaries even if caller thresholds cross.
    return {value>upper, value<=upper && value>lower};
}
inline bool niceHdrMoreFrames(float lux,int threshold,bool tripod) {
    if(!std::isfinite(lux)||double(lux)<INT32_MIN||double(lux)>INT32_MAX)
        throw std::invalid_argument("Invalid NICE lux index");
    // 29a920. Tripod branch tests the -1 sentinel, not lux > threshold.
    return tripod ? threshold==-1 : int32_t(lux)>threshold;
}
struct MoreEv0Inputs {
    int lens, luxIndex, threshold, uiMode, dualRawShotType, sensorQuadMode;
    float zoom, binningLower, binningUpper, quadLower, quadUpper;
};
inline bool niceHdrMoreEv0(const MoreEv0Inputs& p) {
    for(float v:{p.zoom,p.binningLower,p.binningUpper,p.quadLower,p.quadUpper})
        if(!std::isfinite(v))throw std::invalid_argument("Invalid NICE EV0 zoom input");
    // Complete 29a9d8 decision. SceneModeAI appears in logging, not decisions.
    if(p.lens!=2 || p.luxIndex<=p.threshold || p.dualRawShotType==1)return false;
    switch(p.uiMode) {
        case 1:case 7:case 13:case 39:case 70:case 74:case 77:break;
        default:return false;
    }
    if(p.sensorQuadMode==0)return p.zoom>=p.binningLower && p.zoom<=p.binningUpper;
    if(p.sensorQuadMode==2)return p.zoom>=p.quadLower && p.zoom<p.quadUpper;
    return false;
}
struct NormalBackInputs {
    int32_t measuredIndex, indexThreshold, sceneMode, portraitState;
    int32_t captureType, uiMode, timeThresholdMs, forcedMode;
    int64_t manualExposureNs;
    bool forceBack;
};
inline bool niceNormalBack(const NormalBackInputs& p) {
    if(p.manualExposureNs<0 || p.manualExposureNs/1000000>INT32_MAX)
        throw std::invalid_argument("Invalid NICE exposure duration");
    bool back=(p.sceneMode|2)!=15 && p.portraitState==0
        && p.measuredIndex>p.indexThreshold;
    if(p.captureType==42 || p.captureType==44 || p.forceBack)back=true;
    if(p.timeThresholdMs<p.manualExposureNs/1000000 && p.uiMode==71)back=true;
    return back || p.forcedMode!=0;
}
inline bool niceFastNight(float lux,int32_t threshold,int32_t state) {
    if(!std::isfinite(lux)||double(lux)<INT32_MIN||double(lux)>INT32_MAX)
        throw std::invalid_argument("Invalid NICE night lux");
    return state==1 || int32_t(lux)>threshold;
}
inline bool niceQuickNight(int32_t mode,bool fastNight) {
    return mode==1 && fastNight;
}
inline bool niceNearMinExposure(float lux,float nativeAdrcGain) {
    if(!std::isfinite(lux)||double(lux)<INT32_MIN||double(lux)>INT32_MAX
        ||!std::isfinite(nativeAdrcGain)||nativeAdrcGain<1.f||double(nativeAdrcGain)>INT32_MAX)
        throw std::invalid_argument("Invalid NICE minimum exposure input");
    // Preserve integer truncation and single-precision log/division before comparison.
    const float threshold=std::log10(float(int32_t(nativeAdrcGain)))/0x1.a4a658p-7f;
    return double(int32_t(lux))-2.0<=double(threshold);
}
struct ImageEchoInputs {
    int32_t captureType, uiMode, platform, luxThreshold;
    float lux;
    bool firstCondition, secondCondition, thirdCondition;
};
inline bool niceImageEcho(const ImageEchoInputs& p) {
    if(!std::isfinite(p.lux))throw std::invalid_argument("Invalid NICE echo lux");
    const int32_t group=p.captureType & ~2;
    bool group41=group==41 && !p.secondCondition;
    if(group==41 && p.secondCondition && !p.thirdCondition)
        group41=p.lux<=float(p.luxThreshold);
    const uint32_t bit=uint32_t(p.uiMode)-13u;
    const bool ui=bit<59 && ((UINT64_C(0x0400000000040001)>>bit)&1);
    return ui || (group==40 && !p.firstCondition)
        || (p.captureType==44 && p.platform!=0) || group41;
}
} // namespace vivo_nice
