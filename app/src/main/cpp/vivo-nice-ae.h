#pragma once
#include <array>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <stdexcept>

namespace vivo_nice {
// NCH v7 per-frame snapshot. Never reinterpret Camera2 numeric tags as VAF IDs.
struct NiceAe {
    static constexpr size_t transportBytes = 176;
    uint64_t timestamp=0;
    uint32_t flags=0;
    int32_t seamlessMode=0;
    std::array<float,35> aec{};
    float hdrDrc=0, captureAdrc=0;
    bool hasAec() const { return flags&1; }
    bool hasHdrDrc() const { return flags&4; }
    void read(const uint8_t* p) {
        std::memcpy(&timestamp,p,8);std::memcpy(&flags,p+8,4);
        std::memcpy(&seamlessMode,p+12,4);std::memcpy(aec.data(),p+16,140);
        std::memcpy(&hdrDrc,p+156,4);std::memcpy(&captureAdrc,p+160,4);
        bool valid=timestamp>0 && timestamp<=INT64_MAX && !(flags&~255u);
        for(unsigned pair:{3u,12u,48u,192u})valid &= (flags&pair)!=pair;
        for(size_t i=164;i<176;++i)valid &= p[i]==0;
        if(hasAec()) {
            for(int i:{0,2,6,13,14})valid &= std::isfinite(aec[i]) && (i==0 || aec[i]>0.f);
        } else for(float f:aec)valid &= f==0.f;
        valid &= hasHdrDrc()?std::isfinite(hdrDrc):hdrDrc==0.f;
        valid &= (flags&16)?std::isfinite(captureAdrc):captureAdrc==0.f;
        valid &= (flags&64) || seamlessMode==0;
        if(!valid)throw std::runtime_error("Invalid NICE per-frame AE snapshot");
    }
    // VCF dceac..dd030 -> cb41c..cbabc -> VAF 33c310..33d14c.
    // Native short exposure is milliseconds: original float division by 1e6.
    struct Fields { float lux,shortGain,digitalGain,analogGain,exposureMs,aeDrc; };
    Fields fields() const {
        if(!hasAec())throw std::runtime_error("NICE vendor AE unavailable");
        return {aec[0],aec[2],aec[13],aec[2]/aec[13],aec[14]/1000000.f,aec[6]};
    }
    float drcGain(bool hdr) const {
        if(!hdr) {
            if(!(flags&64))throw std::runtime_error("NICE AE DRC mode unavailable");
            float drc=fields().aeDrc;
            // Read-only mode metadata; no sensor-mode change is requested.
            if(seamlessMode==4 || seamlessMode==0x500) {
                if(flags&32)throw std::runtime_error("Invalid NICE capture ADRC");
                if((flags&16) && captureAdrc>0.f)drc=captureAdrc;
            }
            return drc;
        }
        if(!hasHdrDrc())throw std::runtime_error("NICE raw HDR DRC unavailable");
        return std::fmax(hdrDrc,1.f);
    }
};
} // namespace vivo_nice
