#pragma once
#include "vivo-nice-ae.h"
#include <algorithm>
#include <array>
#include <vector>
#include <limits>

namespace vivo_raw {
enum class Role : uint32_t { Normal=0, Short=1, ExtraShort=2, Long=3 };
struct Frame {
    uint64_t captureId=0, generation=0, frameNumber=0, timestamp=0, streamId=0;
    uint32_t cameraId=0, width=0, height=0, cfa=0, iso=0;
    Role role=Role::Normal;
    bool reference=false;
    float white=0, noiseSlope=0, noiseOffset=0;
    std::array<float,4> black{};
    vivo_nice::NiceAe ae;
    std::vector<uint16_t> pixels;
    double exposureProduct() const { return double(ae.aec[14])*ae.aec[2]; }
};

// Internal handoff format, not a Vivo ABI. Producer must supply measured identity.
constexpr uint32_t HeaderBytes=292, MaxPayload=256u*1024*1024;
inline Frame decode(const uint8_t* bytes, size_t size) {
    if(!bytes || size<HeaderBytes)throw std::invalid_argument("Truncated RAW handoff header");
    auto u32=[&](size_t p) {
        return uint32_t(bytes[p]) | uint32_t(bytes[p+1])<<8 |
               uint32_t(bytes[p+2])<<16 | uint32_t(bytes[p+3])<<24;
    };
    auto u64=[&](size_t p) { return uint64_t(u32(p)) | uint64_t(u32(p+4))<<32; };
    auto f32=[&](size_t p) { uint32_t bits=u32(p);float value;std::memcpy(&value,&bits,4);return value; };
    if(u32(0)!=0x31465256 || u32(4)!=2 || u32(8)!=HeaderBytes)
        throw std::invalid_argument("Unsupported RAW handoff protocol");
    const uint32_t payload=u32(12), stride=u32(68);
    Frame f;
    f.captureId=u64(16);f.generation=u64(24);f.frameNumber=u64(32);f.timestamp=u64(40);
    f.cameraId=u32(48);const uint32_t role=u32(52);
    f.width=u32(56);f.height=u32(60);f.cfa=u32(64);
    f.white=f32(72);for(size_t i=0;i<4;i++)f.black[i]=f32(76+i*4);
    f.noiseSlope=f32(268);f.noiseOffset=f32(272);f.iso=u32(276);
    const uint32_t reference=u32(280);
    f.streamId=u64(284);
    if(!f.captureId || f.captureId>INT64_MAX || !f.generation || f.generation>INT64_MAX ||
       f.frameNumber>INT64_MAX || !f.timestamp || f.timestamp>INT64_MAX || f.cameraId>INT32_MAX ||
       !f.streamId || f.streamId>INT64_MAX ||
       role>3 || reference>1 || (reference && role!=0) || f.cfa>3)
        throw std::invalid_argument("Invalid RAW handoff identity or role");
    f.role=static_cast<Role>(role);f.reference=reference!=0;
    if(f.width<64 || f.height<64 || (f.width&1) || (f.height&1) ||
       uint64_t(f.width)*f.height>16000000 || stride<uint64_t(f.width)*2 || (stride&1) ||
       payload>MaxPayload || uint64_t(stride)*f.height!=payload ||
       size!=uint64_t(HeaderBytes)+payload)
        throw std::invalid_argument("Invalid RAW16 handoff extent");
    if(!std::isfinite(f.white) || f.white>65535 || !f.iso ||
       !std::isfinite(f.noiseSlope) || f.noiseSlope<=0 ||
       !std::isfinite(f.noiseOffset) || f.noiseOffset<0)
        throw std::invalid_argument("Invalid RAW calibration");
    for(float black:f.black)
        if(!std::isfinite(black) || black<0 || black+1>=f.white)
            throw std::invalid_argument("Invalid RAW black level");
    f.ae.read(bytes+92);
    if(!f.ae.hasAec() || f.ae.timestamp!=f.timestamp)
        throw std::invalid_argument("RAW handoff lacks timestamp-matched vendor AE");
    f.pixels.resize(size_t(f.width)*f.height);
    for(size_t y=0;y<f.height;y++)for(size_t x=0;x<f.width;x++) {
        const size_t p=HeaderBytes+y*stride+x*2;
        f.pixels[y*f.width+x]=uint16_t(bytes[p]) | uint16_t(bytes[p+1])<<8;
    }
    return f;
}

class Burst {
    uint64_t captureId_,generation_,normalStreamId_,shortStreamId_;
    uint32_t cameraId_;
    bool failed_=false,consumed_=false;
    std::vector<Frame> frames_;
public:
    Burst(uint64_t captureId,uint64_t generation,uint32_t cameraId,
          uint64_t normalStreamId,uint64_t shortStreamId)
        :captureId_(captureId),generation_(generation),normalStreamId_(normalStreamId),
         shortStreamId_(shortStreamId),cameraId_(cameraId) {
        if(!captureId || captureId>INT64_MAX || !generation || generation>INT64_MAX || cameraId>INT32_MAX ||
           !normalStreamId || normalStreamId>INT64_MAX || !shortStreamId || shortStreamId>INT64_MAX)
            throw std::invalid_argument("Invalid RAW transaction identity");
    }
    void cancel() { frames_.clear();failed_=true; }
    void accept(const uint8_t* bytes,size_t size) {
        if(failed_ || consumed_)throw std::logic_error("RAW transaction closed");
        try {
            Frame f=decode(bytes,size);
            if(f.captureId!=captureId_ || f.generation!=generation_ || f.cameraId!=cameraId_)
                throw std::invalid_argument("RAW belongs to another transaction");
            const bool shortFrame=f.role==Role::Short || f.role==Role::ExtraShort;
            if(f.streamId!=(shortFrame?shortStreamId_:normalStreamId_))
                throw std::invalid_argument("RAW stream does not match armed role");
            if(frames_.size()>=7)throw std::invalid_argument("Too many RAW frames");
            for(const Frame& old:frames_) {
                // Separate exposure streams may share sensor metadata. Keep
                // all four normals in the armed normal stream, never invent
                // another stream ID to bypass duplicate detection.
                if(old.streamId==f.streamId &&
                   (old.timestamp==f.timestamp || old.frameNumber==f.frameNumber))
                    throw std::invalid_argument("Duplicate RAW identity");
                if(old.width!=f.width || old.height!=f.height || old.cfa!=f.cfa ||
                   old.white!=f.white || old.black!=f.black)
                    throw std::invalid_argument("RAW geometry/calibration changed within burst");
            }
            frames_.push_back(std::move(f));
        } catch(...) {cancel();throw;}
    }
    // Returns graph order N-reference,N,N,N,L,S,ES only after a complete transaction.
    std::vector<Frame> finish() {
        if(failed_ || consumed_)throw std::logic_error("RAW transaction closed");
        try {
            std::array<size_t,4> counts{};size_t references=0;
            for(const Frame& f:frames_){counts[static_cast<size_t>(f.role)]++;references+=f.reference;}
            if(frames_.size()!=7 || counts!=std::array<size_t,4>{4,1,1,1} || references!=1)
                throw std::invalid_argument("Incomplete RAW 4+3 or missing reference identity");
            auto rank=[](const Frame& f) {
                if(f.reference)return 0;
                return f.role==Role::Normal?1:f.role==Role::Long?2:f.role==Role::Short?3:4;
            };
            std::sort(frames_.begin(),frames_.end(),[&](const Frame& a,const Frame& b) {
                return rank(a)!=rank(b)?rank(a)<rank(b):a.frameNumber<b.frameNumber;
            });
            const double n=frames_[0].exposureProduct(),l=frames_[4].exposureProduct();
            const double s=frames_[5].exposureProduct(),es=frames_[6].exposureProduct();
            if(!(es<s && s<n && n<=l))throw std::invalid_argument("Invalid measured ES/S/N/L exposure order");
            consumed_=true;return std::move(frames_);
        } catch(...) {cancel();throw;}
    }
};
} // namespace vivo_raw
