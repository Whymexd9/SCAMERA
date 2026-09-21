#pragma once
#include "vivo-aec-short-plan.h"
#include "vivo-aec-long.h"
#include <array>
#include <cstring>

namespace vivo_aec {
// Owned, pointer-free copies from one native AE invocation. Never dereference
// copied vendor addresses or combine fields from different preview frames.
template<size_t N> struct NativeBytes {
    std::array<uint8_t,N> bytes{};
    template<class T> T at(size_t off) const {
        if(off>N || sizeof(T)>N-off)throw std::invalid_argument("Native snapshot extent");
        T value;std::memcpy(&value,bytes.data()+off,sizeof(value));return value;
    }
    float f(size_t off)const{return at<float>(off);}
    uint32_t u(size_t off)const{return at<uint32_t>(off);}
    uint64_t q(size_t off)const{return at<uint64_t>(off);}
    bool b(size_t off)const{return at<uint8_t>(off)!=0;}
};
struct TuningTable { float divisor,bandingTolerance;std::vector<TableRow> rows; };
struct TuningBank { std::vector<TuningTable> tables;std::vector<BlurRow> blur; };
struct SolverSnapshot {
    NativeBytes<0xe8> params;
    NativeBytes<0xb8> common;
    NativeBytes<0x74> calculator;
    NativeBytes<0x930> state;
    NativeBytes<0x440> tuning;
    NativeBytes<12> sensor;
    NativeBytes<8> camera;
    NativeBytes<0xb0> debugFlags;
    NativeBytes<0x44> debugEv;
    NativeBytes<0x65c> motion;
    int sensorType;
    TuningBank primary,alternate;
};
struct SolverPlan {
    Exposure normal,shortFrame,extraShortFrame,longFrame;
    bool shortEnabled;
    uint32_t tableType,tableId,hdrFlags;
    int32_t ratioMilli,evMilli,retainedCorrectionMilli;
    float targetRatio,correctionMilli,deltaEv,deltaMultiplier;
};
inline SolverPlan solveExposures(const SolverSnapshot& s) {
    const auto& p=s.params;const auto& c=s.common;const auto& a=s.calculator;
    const auto& state=s.state;const auto& t=s.tuning;
    const int mode=int(p.u(0xc4));
    const NiceCaptureFlags flags{a.u(0x54),a.u(0x58),a.u(0x5c),a.u(0x60),a.u(0x64),a.u(0x68),a.u(0x50)};
    if(hdrRunMode(int64_t(state.q(0xb8)),flags.modeOverride)!=mode)
        throw std::invalid_argument("Native scene/run mode mismatch");
    const uint32_t type=exposureTableType(mode,int(a.u(0x3c)),flags);
    const auto selection=hdrModeAndTableId(mode,type);
    const bool nice=usesAlternateTuningBank(mode);
    const auto& bank=nice?s.alternate:s.primary;
    if(selection.tableId>=bank.tables.size())throw std::invalid_argument("Selected tuning table missing");
    const auto& table=bank.tables[selection.tableId];const auto& rows=table.rows;
    if(rows.size()<2)throw std::invalid_argument("Missing tuning rows");
    const bool debug=s.debugFlags.u(0xac)==1;
    float delta=p.f(0x60);
    if(debug && std::abs(s.debugEv.f(0x3c))>=1.e-6f)delta=s.debugEv.f(0x3c);
    const float multiplier=std::exp2(delta);
    const size_t gapOffset=state.u(0)==10?0x8c:0x80;
    const auto gaps=exposureGaps(c.f(gapOffset),c.f(gapOffset+4),c.f(gapOffset+8));
    auto decrease=decreaseExposure({mode,flags,t.u(0x108),p.f(12),p.f(0x5c),delta,multiplier,
        p.f(0x78),s.motion.f(0x658),p.f(0x8c),p.f(0x90),p.u(0xc8)!=0,p.u(0xcc)!=0,s.debugEv.f(0x40),debug},
        {c.f(0x34),c.f(0x38),c.f(0x3c),c.f(0x40),c.f(0x44),c.f(0x48),
         c.f(0x54),c.f(0x58),c.f(0x5c),c.f(0x60),c.f(0x64),c.f(0x68),c.f(0x74),c.f(0xb0)});
    NormalAdjustment adjust{rows.front().gain,rows.back().gain,float(rows.front().shutter),float(rows.back().shutter),
        a.f(0),table.bandingTolerance,s.sensor.f(8),c.b(0x28),c.b(0x29)&&a.u(0x2c)==0,0};
    // These two addresses must refer to the same native source before copying.
    if(p.q(0xa0)!=a.q(8))throw std::invalid_argument("Motion snapshot source mismatch");
    auto base=plannedBaseExposure({p.q(0),p.f(8),p.f(12),p.f(0x5c),delta,multiplier,mode,flags,
        t.u(0x108),c.f(0xb0),c.f(nice?0x7c:0x78),t.f(0x410),a.u(0x38)==1,state.u(0x8c8)!=0,
        state.u(0x8b4)==1,state.u(0xf0)!=0,a.f(0x4c),int32_t(a.u(0x6c))},decrease,
        table.divisor,rows,adjust,s.primary.blur,s.motion.f(4));
    auto shortPlan=plannedShortExposures(base.normal,{mode,flags,p.f(0x5c),p.f(12),delta,base.decrease.ev,
        p.f(0x88),p.f(0x8c),c.f(0x2c),p.f(0xe0),t.f(0x410),c.f(0x78),c.f(0x7c),c.f(0x98),c.f(0x9c),c.f(0xb4),c.f(0xa0),
        c.b(0x28),a.u(0x38)==1,t.u(0x43c)!=0,state.u(0x870)!=0,s.sensorType,state.u(0x16c)==1,s.camera.u(4)!=0,gaps},
        {rows.front().gain,rows.front().shutter,s.sensor.f(8),a.f(0),0});
    auto longFrame=plannedLongExposure(base.normal,{p.q(0),p.f(8),p.f(12),p.f(0x38),p.f(0x5c),delta,multiplier,gaps.longEv,mode,flags},
        c.f(0x50),table.divisor,rows,adjust,s.primary.blur,s.motion.f(4));
    uint32_t hdr=selection.hdrFlags;if(hdr&0x5400)hdr|=2;
    return {base.normal,shortPlan.shortFrame,shortPlan.extraShortFrame,longFrame,shortPlan.enabled,
        type,selection.tableId,hdr,base.decrease.ratioMilli,base.decrease.evMilli,base.retainedCorrectionMilli,
        base.targetRatio,base.correctionMilli,delta,multiplier};
}
} // namespace vivo_aec
