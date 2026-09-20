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
} // namespace vivo_nice
