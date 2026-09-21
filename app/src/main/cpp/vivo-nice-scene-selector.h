#pragma once
#include "vivo-nice-scene-rules.h"
#include "vivo-nice-frame-calculator.h"

namespace vivo_nice {
// PD2454 VAF setDetectTypeForNICE, 29bfb0..29ce4c. Inputs retain native
// scene/tuning domains. No ISO-to-lux or Camera2-to-VAF conversion is implied.
// Debug property overrides and factory self-test are deliberately not inputs.
struct NiceSceneInputs {
    int captureType, sensorQuadMode, tuningQuadMode, lens, uiMode, sceneMode, algoSceneMode;
    int seamlessMode, previewHdrVersion, hdrType, dualRawType, previousMode;
    int luxIndex, stageTeleThreshold, stageWideThreshold;
    int moreFrameThreshold, moreEv0Threshold, highLuxThreshold;
    int exposureIndex, exposureIndexThreshold, timeThresholdMs;
    int forcedMode, motionPortraitState, motionPortraitFlags, uiFlags;
    int64_t manualExposureNs;
    float lux, zoom, motion, motionLower, motionUpper;
    float exposureTime, hdrGain, sensorGain, referenceGain;
    float exposureProductThreshold, exposureIndexScaleThreshold, exposureBias;
    float sunsetScore, sunsetThreshold, motionPortraitExposureThreshold;
    float binningZoomLower, binningZoomUpper, quadZoomLower, quadZoomUpper;
    bool tripod, fastNight, quickNightMode, stageEnabled, forceBack;
    bool motionPortraitEnabled, livePhoto;
};
struct NiceSceneSelection { int mode, dualRawType; bool moreEv0Frames; };

inline bool niceStage(const NiceSceneInputs& p) {
    if(p.sceneMode!=13)return false;
    const bool tele=p.lens==2 && p.tuningQuadMode==2;
    const bool wide=p.lens==0 && p.tuningQuadMode==2;
    if(!p.stageEnabled)return !tele;
    if(tele)return p.luxIndex<p.stageTeleThreshold;
    if(wide)return p.luxIndex<p.stageWideThreshold;
    return true;
}
inline bool niceSunset(const NiceSceneInputs& p) {
    return (p.uiMode==1 || p.uiMode==7 || p.uiMode==74 || p.uiMode==77)
        && p.lens==2 && (p.sensorQuadMode==0 || p.sensorQuadMode==2)
        && p.sunsetScore>=p.sunsetThreshold && (p.sceneMode==39 || p.sceneMode==40);
}
inline bool niceHighLux(const NiceSceneInputs& p) {
    if(uint32_t(p.lens)>1 || (p.uiMode!=1 && p.uiMode!=74)
        || (p.sensorQuadMode!=0 && p.sensorQuadMode!=2))return false;
    return p.luxIndex<p.highLuxThreshold
        || (uint32_t(p.sceneMode)<41 && ((UINT64_C(0x18000000400)>>p.sceneMode)&1));
}

// The native fallback lambda (29ed74) always indexes the SINGLE group layout.
// It tests the last vector entry, not count1-1, and scans modes 0 through 33.
template<class RowLookup>
inline int niceLastEv0Mode(int selected, const RowLookup& rowForMode) {
    const auto& initial=rowForMode(selected);
    if(!initial.evSize || initial.evSize>32)
        throw std::invalid_argument("Uninitialized NICE scene EV table");
    if(initial.ev[initial.evSize-1]==0)return selected;
    for(int mode=0;mode<34;++mode) {
        const auto& row=rowForMode(mode);
        if(row.evSize>32)throw std::invalid_argument("Invalid NICE scene EV table");
        if(row.evSize && row.ev[row.evSize-1]==0)return mode;
    }
    return 0;
}

template<class RowLookup>
inline NiceSceneSelection selectNiceScene(const NiceSceneInputs& p, const RowLookup& rowForMode) {
    for(float v:{p.lux,p.zoom,p.motion,p.motionLower,p.motionUpper,p.exposureTime,
        p.hdrGain,p.sensorGain,p.referenceGain,p.exposureProductThreshold,
        p.exposureIndexScaleThreshold,p.exposureBias,p.sunsetScore,p.sunsetThreshold,
        p.motionPortraitExposureThreshold})
        if(!std::isfinite(v))throw std::invalid_argument("Missing NICE scene measurement");
    if(p.manualExposureNs<0 || p.manualExposureNs/1000000>INT32_MAX)
        throw std::invalid_argument("Invalid NICE scene manual exposure");
    const auto shaking=classifyHdrMotion(p.motion,p.motionLower,p.motionUpper);
    const bool more=niceHdrMoreFrames(p.lux,p.moreFrameThreshold,p.tripod);
    const bool stage=niceStage(p), sunset=niceSunset(p), highLux=niceHighLux(p);
    const bool nonTeleQuad=!(p.lens==2 && p.tuningQuadMode==2);
    const bool quick=p.quickNightMode && p.fastNight;
    const bool forwardUi=nonTeleQuad && (p.uiFlags&15)
        && (p.uiMode==1 || p.uiMode==31 || p.uiMode==70 || p.uiMode==77);
    // Keep individual float operations: the donor does not fuse multiply-adds.
    const float biasGain=std::exp2(p.exposureBias);
    const float baseProduct=p.sensorGain*p.referenceGain;
    const float product=(p.seamlessMode==4 || p.seamlessMode==0x500)
        && p.previewHdrVersion==2 ? biasGain*baseProduct : p.sensorGain*p.exposureTime;
    const bool back=p.hdrGain>=float(p.exposureIndexThreshold)
        || product>p.exposureProductThreshold
        || float(p.exposureIndex)*2.5f>p.exposureIndexScaleThreshold;
    const bool forced=p.forcedMode!=0 && p.dualRawType==0;
    const bool longManual=p.timeThresholdMs<p.manualExposureNs/1000000;
    int dual=p.dualRawType;
    const auto choose=[&]() -> int {
        if(p.captureType==43) {
            if(more)return shaking.shaking1?20:19;
            return stage ? (shaking.shaking1?22:21) : (shaking.shaking1?18:17);
        }
        if(p.captureType!=41)return 1;
        if(p.sensorQuadMode!=0 && p.sensorQuadMode!=2)return p.previousMode;
        const bool quad=p.sensorQuadMode==2;
        if(forced)return more?(quad?13:6):(quad?32:31);
        if(p.motionPortraitEnabled && p.seamlessMode==(quad?0x500:4)) {
            if(!(p.motionPortraitFlags&3) && p.exposureTime>p.motionPortraitExposureThreshold
                && p.motionPortraitState<=0)
                return quad?(shaking.shaking1?27:28):(shaking.shaking1?24:25);
            return quad?26:23;
        }
        if(p.livePhoto)return quad ? (dual==1 && !p.forceBack?9:30) : (dual==1?2:29);
        if(sunset && (!quad || !p.forceBack))return quad?16:8;
        if(highLux && p.algoSceneMode!=1)return quad?9:2;
        if(!quad && p.hdrType==2) {dual=2;return 2;}
        if(!shaking.shaking1 && !shaking.shaking2 && forwardUi && (!quad || !p.forceBack))
            return quad?9:2;
        if(quick && (!quad || (!p.forceBack && nonTeleQuad)))return quad?9:2;
        if(shaking.shaking2)return quad?(more?15:12):(more?6:5);
        if(shaking.shaking1)return quad?(more?14:11):(more?7:4);
        if(stage && (!quad || !p.forceBack))return quad?9:2;
        const int forward=quad?9:2, hdr=quad?10:3, many=quad?13:6;
        if(p.uiMode==71) {
            if(back)return more?many:(longManual?hdr:forward);
            return longManual?hdr:forward;
        }
        if(p.algoSceneMode==1)return niceLastEv0Mode(more?many:hdr,rowForMode);
        return back || (quad && p.forceBack) ? (more?many:hdr) : forward;
    };
    const int mode=choose();
    const bool moreEv0=niceHdrMoreEv0({p.lens,p.luxIndex,p.moreEv0Threshold,p.uiMode,
        dual,p.tuningQuadMode,p.zoom,p.binningZoomLower,p.binningZoomUpper,
        p.quadZoomLower,p.quadZoomUpper});
    return {mode,dual,moreEv0};
}
inline NiceSceneSelection selectNiceScene(const NiceSceneInputs& p) {
    return selectNiceScene(p,[&](int mode)->const tuning::HdrRow& {
        const auto* row=tuning::findHdrRow(p.lens,false,hdrVariant(mode,false));
        if(!row)throw std::invalid_argument("Missing initialized NICE scene tuning");
        return *row;
    });
}
// Connected scene -> tuning -> frame-plan boundary. AE gain/shutter production
// remains a required next stage; this result must not be submitted as exposure.
struct NiceSceneFramePlan { NiceSceneSelection scene; NiceDetectResult frames; };
inline NiceSceneFramePlan planNiceSceneFrames(const NiceSceneInputs& p,bool imageEcho) {
    const auto scene=selectNiceScene(p);
    return {scene,calculateNiceFrameInfo(p.lens,scene.mode,p.seamlessMode,
        p.previewHdrVersion,imageEcho,scene.moreEv0Frames)};
}

struct NiceHdrPostInputs {
    int captureMode, forwardBlock, luxBoundary1, luxBoundary2;
    int echoLuxThreshold, motionThreshold, exposureMode;
    float motionScore, extremeThreshold, extremeAutoThreshold, extremeHysteresis;
    bool portrait, previousExtreme, nightScene;
};
struct NiceHdrDecision {
    NiceSceneSelection scene;
    uint64_t flags;
    int motionClass, forceBack, quickNightClass;
    bool nightScene, extreme, imageEcho;
};
// HDR branch of postProcess (29b9a0), after preProcess reset. The non-HDR
// captures 40/42/44 and the optional virtual callback at scene+50 are outside
// this entry point. This consumes an already-enabled HDR decision, not an HDR
// eligibility heuristic. Flag word is the native scene+260 contract.
template<class RowLookup>
inline NiceHdrDecision finishNiceHdrScene(const NiceSceneInputs& p,const NiceHdrPostInputs& h,
                                         const RowLookup& rowForMode) {
    if(p.captureType!=41 && p.captureType!=43)
        throw std::invalid_argument("NICE HDR postprocess requires HDR capture type");
    for(float v:{h.motionScore,h.extremeThreshold,h.extremeAutoThreshold,h.extremeHysteresis})
        if(!std::isfinite(v))throw std::invalid_argument("Missing NICE HDR postprocess input");
    const auto scene=selectNiceScene(p,rowForMode);
    const int mode=scene.mode;
    uint64_t flags=0;
    if(p.livePhoto && scene.dualRawType!=1 && h.captureMode==24 && (mode==29 || mode==30))
        flags|=UINT64_C(0x20000000000000);
    const bool back=p.captureType==43 || h.forwardBlock==0;
    if(back)flags|=1;
    const uint32_t bit=uint32_t(mode)<=20 ? (1u<<mode) : 0;
    if(bit&0x104890)flags|=0x2000;
    else if(bit&0x9020)flags|=0x3000;
    const bool more=bit&0x18e0c0;
    if(more)flags|=0x100000;
    if(h.luxBoundary1<=p.luxIndex)flags|=h.luxBoundary2>p.luxIndex?0x100:0x200;
    const float threshold=(h.exposureMode==0?h.extremeAutoThreshold:h.extremeThreshold)
        -(h.previousExtreme?h.extremeHysteresis:0.f);
    const bool extreme=std::fabs(threshold)>=0x1.0c6f7ap-20f && p.lux>threshold;
    if(extreme || p.tripod)flags|=extreme?(p.tripod?0x30000:0x20000):0x10000;
    if(h.portrait)flags|=0x5000000;
    if(p.forcedMode && scene.dualRawType==0)flags|=UINT64_C(0x100000000000010);
    flags|=uint64_t(uint8_t(mode))<<40;
    flags|=uint64_t(p.lens&15)<<48;
    const bool night=p.uiMode==71 ? p.timeThresholdMs<p.manualExposureNs/1000000 : h.nightScene;
    const bool echo=niceImageEcho({p.captureType,p.uiMode,0,h.echoLuxThreshold,p.lux,false,back,more});
    return {scene,flags,int(h.motionScore>float(h.motionThreshold)),int(p.forceBack),
        p.quickNightMode && p.fastNight?2:0,night,extreme,echo};
}
inline NiceHdrDecision finishNiceHdrScene(const NiceSceneInputs& p,const NiceHdrPostInputs& h) {
    return finishNiceHdrScene(p,h,[&](int mode)->const tuning::HdrRow& {
        const auto* row=tuning::findHdrRow(p.lens,false,hdrVariant(mode,false));
        if(!row)throw std::invalid_argument("Missing initialized NICE scene tuning");
        return *row;
    });
}
struct NiceHdrSceneFramePlan { NiceHdrDecision decision; NiceDetectResult frames; };
inline NiceHdrSceneFramePlan planNiceHdrSceneFrames(const NiceSceneInputs& p,const NiceHdrPostInputs& h) {
    const auto decision=finishNiceHdrScene(p,h);
    return {decision,calculateNiceFrameInfo(p.lens,decision.scene.mode,p.seamlessMode,
        p.previewHdrVersion,decision.imageEcho,decision.scene.moreEv0Frames)};
}
} // namespace vivo_nice
