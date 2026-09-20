#pragma once
#include <algorithm>
#include <array>
#include <cmath>
#include <stdexcept>
#include <vector>

namespace vivo_nice {
// MainCamera/NiceCREConfigHdrForward.xml in the pinned PD2454 donor.
// CRE 0x36cbe4 selects the last nonnegative againList threshold satisfied by
// shortGain (mode 0) or analogGain (mode 1). Both forward coefficients are 1.1.
// CRE 0x2d8a30 applies this coefficient to the ISO-50 VST normalization.
constexpr float forwardNormCoefficient = 1.1f;
constexpr int forwardTileSize = 544;
constexpr int forwardOverlap = 16;
constexpr bool forwardOverlapFusion = false; // Overlap/useFusion defaults to 0

// CRE SelectFrame 0x3617e8..0x361830 moves the selected reference first.
// FrameTypeOrder ref/refn select exposure levels, not network slot numbers.
constexpr int forwardReferenceSlot = 0;

// Fixed forward graph: four N, one L, one S, one ES. The XML's ref/refn=3
// select the L exposure level in the ES/S/N/L level table, not input slot 3.
// CRE ExceptNode 0x2ddf14..0x2ddfc8 then rebases on S and sets ES EV to one.
struct ForwardExposureDomains {
    std::array<float,7> frameEV;
    float normalEV, normalizationEV;
};
inline ForwardExposureDomains forwardExposureDomains(const std::array<float,7>& exposure) {
    for(float value:exposure)
        if(!std::isfinite(value)||value<=0)
            throw std::invalid_argument("NICE exposure domain");
    ForwardExposureDomains d{};
    const float shortBase=exposure[5]/exposure[6];
    for(int f=0;f<7;++f)d.frameEV[f]=(exposure[f]/exposure[6])/shortBase;
    d.frameEV[6]=1.f;
    d.normalEV=d.frameEV[forwardReferenceSlot];
    d.normalizationEV=d.frameEV[4];
    return d;
}

struct TileAxis {
    int inputOrigin, outputOrigin, outputSize, crop;
};

// CRE BlockInit 0x2fe8f8 and block planner 0x3dba84, unit input/output scale.
// "Overlap" is inference context on either side of a 512-pixel work area.
// First/last tiles anchor to the actual image edge; their crops are asymmetric.
// The last work area may absorb up to 16 extra pixels instead of another tile.
inline std::vector<TileAxis> forwardTileAxis(int size) {
    if(size <= 0) throw std::invalid_argument("NICE tile axis size");
    constexpr int tile=forwardTileSize, delta=forwardOverlap, work=tile-2*delta;
    if(size<=tile) return {{0,0,size,0}};
    const int count=size/work+(size%work>delta?1:0);
    std::vector<TileAxis> out;
    for(int i=0;i<count;++i){
        const int dst=i*work;
        const int src=i==0?0:i==count-1?size-tile:dst-delta;
        out.push_back({src,dst,i==count-1?size-dst:work,dst-src});
    }
    return out;
}
} // namespace vivo_nice
