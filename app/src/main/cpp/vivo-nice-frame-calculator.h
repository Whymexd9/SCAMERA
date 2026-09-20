#pragma once
#include "vivo-nice-tuning.h"
#include <array>
#include <cstdint>

namespace vivo_nice {
// NICEFrameCalculator::packedCaptureFrameInfo, PD2454 17ad4..182ec.
// Native forward/backward naming is retained. This is before AE scheduling;
// it is NOT a translation to pre-shutter/post-shutter Camera2 requests.
struct NiceDetectResult {
    uint32_t flags = 0;
    int32_t forwardInputNum = 0, backwardInputNum = 0;
    std::array<float,16> backwardEv{}, backwardGain{}, backwardShutter{};
    std::array<float,16> shortBackwardEv{}, shortBackwardGain{}, shortBackwardShutter{};
    uint32_t reserved = 0;
};
static_assert(sizeof(NiceDetectResult) == 0x190);
static_assert(offsetof(NiceDetectResult,backwardEv) == 0xc);
static_assert(offsetof(NiceDetectResult,shortBackwardEv) == 0xcc);

// The jump tables select members of different single/dual group layouts.
// Unknown modes use Binning, matching both native default branches.
inline const char* hdrVariant(int mode, bool dual) {
    static constexpr const char* single[] = {
        "nicehdrBinningForward", "nicehdrBinning", "nicehdrBinningShaking1",
        "nicehdrBinningShaking2", "nicehdrBinningMoreFrame", "nicehdrBinningMoreFrameShaking",
        "nicehdrBinningSunset", "nicehdrRoiQuadForward", "nicehdrRoiQuad",
        "nicehdrRoiQuadShaking1", "nicehdrRoiQuadShaking2", "nicehdrRoiQuadMoreFrame",
        "nicehdrRoiQuadMoreFrameShaking1", "nicehdrRoiQuadMoreFrameShaking2",
        "nicehdrRoiQuadMoreFrameSunset", "nicehdrSatQuad", "nicehdrSatQuadShaking",
        "nicehdrSatQuadMoreFrame", "nicehdrSatQuadMoreFrameShaking", "nicehdrSatQuadStage",
        "nicehdrSatQuadShakingStage", "nicehdrBinning", "nicehdrBinning", "nicehdrBinning",
        "nicehdrBinning", "nicehdrBinning", "nicehdrBinning", "nicehdrBinningLivePhoto",
        "nicehdrRoiQuadLivePhoto", "nicehdrBinningFlash", "nicehdrQuadFlash"
    };
    static constexpr const char* paired[] = {
        "nicehdrBinningForward", "nicehdrBinning", "nicehdrBinningShaking1", "nicehdrBinning",
        "nicehdrBinningMoreFrame", "nicehdrBinningMoreFrameShaking", "nicehdrBinningForward",
        "nicehdrRoiQuadForward", "nicehdrRoiQuad", "nicehdrRoiQuadShaking1", "nicehdrRoiQuad",
        "nicehdrRoiQuadMoreFrame", "nicehdrRoiQuadMoreFrameShaking1", "nicehdrRoiQuadMoreFrameShaking1",
        "nicehdrRoiQuadForward", "nicehdrBinning", "nicehdrBinning", "nicehdrBinning",
        "nicehdrBinning", "nicehdrBinning", "nicehdrBinning", "nicehdrDoLBinningMotionPortrait",
        "nicehdrDoLBinningMotionPortraitShaking", "nicehdrDoLBinningMotionPortraitForward",
        "nicehdrDoLRoiQuadMotionPortrait", "nicehdrDoLRoiQuadMotionPortraitShaking",
        "nicehdrDoLRoiQuadMotionPortraitForward", "nicehdrBinningLivePhoto", "nicehdrRoiQuadLivePhoto"
    };
    if (mode < 2 || mode > (dual ? 30 : 32)) return "nicehdrBinning";
    return dual ? paired[mode-2] : single[mode-2];
}
inline float decodeHdrEv(int code) {
    // Native constant table at 6f08. Code 1 denotes image echo, not +1EV.
    switch(code) {
        case -2:return -200.f;
        case -1:return -100.f;
        case 0:return 0.f;
        case 1:return 101.f;
        case 2:return 100.f;
        default:return 0.f;
    }
}
inline NiceDetectResult packNiceFrameInfo(const tuning::HdrRow& row, int mode,
                                        bool needImageEcho, bool moreEv0Frames) {
    if(row.count0<0 || row.count0>16 || row.count1<0 || row.count1>16 ||
       row.evSize>32 || row.evSize<size_t(row.count1))
        throw std::invalid_argument("Invalid NICE frame configuration");
    NiceDetectResult out;
    bool echo = false;
    for(int i=0;i<row.count1;++i) {
        const int ev = row.ev[i];
        if(row.count0==0 && ev==1) {
            if(!needImageEcho) continue;
            echo = true;
        }
        const size_t dest = size_t(out.backwardInputNum++);
        out.backwardEv[dest] = decodeHdrEv(ev);
        if(row.dual) {
            const size_t index = size_t(row.count1+i);
            if(index>=row.evSize) throw std::invalid_argument("Missing paired NICE short EV");
            out.shortBackwardEv[dest] = decodeHdrEv(row.ev[index]);
        }
    }
    out.forwardInputNum = row.count0;
    if(row.count0==0 && moreEv0Frames) {
        if(out.backwardInputNum>13) throw std::invalid_argument("NICE EV0 prefix exceeds capacity");
        for(int i=out.backwardInputNum-1;i>=0;--i)out.backwardEv[i+3]=out.backwardEv[i];
        out.backwardEv[0]=out.backwardEv[1]=out.backwardEv[2]=0.f;
        out.backwardInputNum+=3;
        // Original moves only the primary EV array, not the paired-short one.
    }
    out.flags = mode>=17 && mode<=22 ? 0x20000u : 0x10000u;
    if(echo) out.flags|=0x40000u;
    return out;
}
inline NiceDetectResult calculateNiceFrameInfo(int lens, int mode, int seamlessMode,
                                               int previewHdrVersion, bool imageEcho,
                                               bool moreEv0Frames) {
    const bool dual=tuning::usesDualHdrGroup(seamlessMode,previewHdrVersion);
    const auto* row=tuning::findHdrRow(lens,dual,hdrVariant(mode,dual));
    if(!row)throw std::invalid_argument("NICE variant requires unavailable initialized defaults");
    return packNiceFrameInfo(*row,mode,imageEcho,moreEv0Frames);
}
} // namespace vivo_nice
