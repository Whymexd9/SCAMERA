#pragma once
#include "vivo-nice-preprocess.h"
#include "vivo-nice-profile.h"
#include "vivo-nice-homography.h"
#include "vivo-nice-ae.h"
#include "vivo-nice-lumachroma.h"
#include "vivo-nice-merge.h"
#include "vivo-nice-superres.h"
#include "vivo-nice-superres-gpu.h"
#include "vivo-nice-portable.h"
#include <fstream>
#include <functional>
#include <sys/mman.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <cstring>
#include <limits>
#include <chrono>
#include <atomic>
#include <future>

namespace vivo_nice {
// NCH scene snapshot, deliberately separate from TCE's internal exposure
// fields: an ADRC result tag is not yet proven to equal CRE's DRC gain.
struct SceneMetadata {
    uint64_t timestamp=0;
    float lux=0,adrc=0;
    uint32_t flags=0,luxSource=0;
    bool hasLux() const {return flags&1;}
    bool hasAdrc() const {return flags&2;}
};
inline int reflectCfa(int x,int size);
// Camera2 adaptation around the recovered CRE tensor/VST contract. Motion estimation
// and sub-tile image padding remain SCAMERA adaptations, not stock MEE.
inline int reflectCfa(int x,int size) {
    // Even extension retains the parity/color of each extrapolated RAW site.
    while(x<0||x>=size){if(x<0)x=-x;else x=2*(size-1)-x;}
    return x;
}
struct Burst {
    SceneMetadata scene;
    std::array<NiceAe,7> ae{};
    int w=0,h=0,cfa=0;
    int noiseReferenceSlot=0; // v1-v3 noise belongs to N; v4 belongs to L
    float white=0;
    float normCoefficient=forwardNormCoefficient, noiseScale=1.f;
    NiceLumaChroma lumaChroma{}; // NCH v9 tuning block
    NiceNoise noise{},normalNoise{}; bool hasNormalNoise=false; bool cameraNoise=false,diagnostics=false,canonicalRggb=false;
    std::array<float,4> black{};
    std::array<float,7> exposure{}; // sensor exposure products relative to N ref
    std::array<unsigned,7> iso{};
    std::array<const uint16_t*,7> raw{};
    // Extra ZSL N frames (same exposure as the N reference), merged into the
    // four N slots before the network; the graph itself stays 4N+L+S+ES.
    std::vector<const uint16_t*> extraNormals;
    // Also produce the whole-burst merged Bayer RAW for the DNG (like the merged
    // DNG of GCam/LMC), instead of the single reference frame.
    bool mergedDng=false;
    // >0: no L was captured; the worker builds L from all N frames merged in
    // reference geometry, times this exposure ratio (slot 4 carries N).
    float syntheticLong=0;
    int color(int x,int y) const {
        const int phase=((y&1)<<1)|(x&1);
        const int red=canonicalRggb?0:cfa;
        return phase==red?0:phase==(red^3)?2:1;
    }
    float sample(int f,int x,int y) const {return sampleRaw(raw[f],x,y);}
    float sampleRaw(const uint16_t* data,int x,int y) const {
        // CRE UnpackAndToRGGB translates the sensor CFA before the network.
        // Keep this a view: seven additional full-frame copies are unnecessary.
        if(canonicalRggb){
            x+=cfa&1;y+=cfa>>1;
            // Border reflection only off the frame (the per-sample loop was ~4% of NICE).
            if(__builtin_expect(unsigned(x)>=unsigned(w)||unsigned(y)>=unsigned(h),0)){x=reflectCfa(x,w);y=reflectCfa(y,h);}
        }
        const float b=black[((y&1)<<1)|(x&1)];
        return std::clamp((float(data[size_t(y)*w+x])-b)/(white-b),0.0f,1.0f);
    }
};
// "fd:N": a descriptor inherited from the app (memfd, worker without root)
// instead of a path; the app sandbox cannot reopen it through /proc.
inline int openArgument(const std::string& path,int flags) {
    if(path.rfind("fd:",0)==0) {
        const int fd=std::atoi(path.c_str()+3);
        return fd>2?fcntl(fd,F_DUPFD_CLOEXEC,3):-1;
    }
    return open(path.c_str(),flags|O_CLOEXEC,0600);
}
struct MappedNiceBurst {
    void* address=MAP_FAILED;size_t length=0;Burst burst;
    explicit MappedNiceBurst(const std::string& path) {
        int fd=openArgument(path,O_RDONLY);
        if(fd<0)throw std::runtime_error("Cannot open NICE burst");
        struct stat st{};
        if(fstat(fd,&st)||st.st_size<128||uint64_t(st.st_size)>160+7*NiceAe::transportBytes+32+16000000ULL*2*(7+46)){close(fd);throw std::runtime_error("Invalid NICE file size");}
        length=size_t(st.st_size);address=mmap(nullptr,length,PROT_READ,MAP_PRIVATE,fd,0);close(fd);
        if(address==MAP_FAILED)throw std::runtime_error("Cannot map NICE burst");
        try {
            uint32_t h[32];std::memcpy(h,address,128);
            if(h[0]!=0x3143484e || (h[1]<1 || h[1]>9) || h[2]<64 || h[3]<64 || h[2]%2 || h[3]%2 ||
               uint64_t(h[2])*h[3]>16000000 || h[4]>3 || h[5]<7 || h[5]>7+46 || (h[5]>7 && h[1]<9))
                throw std::runtime_error("Unsupported NICE dimensions/CFA/header");
            burst.w=int(h[2]);burst.h=int(h[3]);burst.cfa=int(h[4]);
            std::memcpy(&burst.white,h+6,4);std::memcpy(burst.black.data(),h+7,16);
            std::memcpy(burst.exposure.data(),h+11,28);std::memcpy(burst.iso.data(),h+18,28);
            if(h[1]>=2) {
                std::memcpy(&burst.noise.slope,h+25,4);std::memcpy(&burst.noise.offset,h+26,4);
                if(!std::isfinite(burst.noise.slope)||burst.noise.slope<=0||!std::isfinite(burst.noise.offset)||burst.noise.offset<0||h[27]>1)
                    throw std::runtime_error("Invalid Camera2 noise profile/diagnostic flags");
                burst.cameraNoise=true;burst.diagnostics=h[27]!=0;
                burst.noiseReferenceSlot=h[1]>=4?4:0;
            }
            if(h[1]>=5) {
                std::memcpy(&burst.normalNoise.slope,h+28,4);
                std::memcpy(&burst.normalNoise.offset,h+29,4);
                if(!std::isfinite(burst.normalNoise.slope)||burst.normalNoise.slope<=0||
                   !std::isfinite(burst.normalNoise.offset)||burst.normalNoise.offset<0)
                    throw std::runtime_error("Invalid normal-reference noise profile");
                burst.hasNormalNoise=true;
            }
            if(h[1]>=8) {
                std::memcpy(&burst.normCoefficient,h+30,4);
                std::memcpy(&burst.noiseScale,h+31,4);
                if(!std::isfinite(burst.normCoefficient)||burst.normCoefficient<.55f||burst.normCoefficient>2.2f||
                   !std::isfinite(burst.noiseScale)||burst.noiseScale<.25f||burst.noiseScale>4.f)
                    throw std::runtime_error("Invalid NICE internal VST tuning");
            }
            for(int i=h[1]>=8?32:h[1]>=5?30:h[1]>=2?28:25;i<32;++i)if(h[i])throw std::runtime_error("NICE reserved header");
            if(!std::isfinite(burst.white)||burst.white>65535)throw std::runtime_error("NICE white level");
            for(float b:burst.black)if(!std::isfinite(b)||b<0||b+1>=burst.white)throw std::runtime_error("NICE black level");
            for(int i=0;i<7;++i) {
                if(!std::isfinite(burst.exposure[i])||burst.exposure[i]<1.0f/256||burst.exposure[i]>256||burst.iso[i]==0||(!burst.cameraNoise&&(burst.iso[i]<50||burst.iso[i]>12800)))
                    throw std::runtime_error("NICE exposure or ISO outside calibrated range");
            }
            if(std::abs(burst.exposure[h[1]<3?3:forwardReferenceSlot]-1)>1e-5f)throw std::runtime_error("NICE reference exposure mismatch");
            size_t pixels=size_t(burst.w)*burst.h;
            const size_t headerBytes=h[1]>=9?160+7*NiceAe::transportBytes+32:h[1]>=7?160+7*NiceAe::transportBytes:h[1]>=6?160:128;
            if(length!=headerBytes+pixels*2*h[5])throw std::runtime_error("Truncated NICE RAW burst");
            if(h[1]>=6) {
                const auto* extension=static_cast<const uint8_t*>(address)+128;
                auto& s=burst.scene;
                std::memcpy(&s.timestamp,extension,8);
                std::memcpy(&s.lux,extension+8,4);std::memcpy(&s.adrc,extension+12,4);
                std::memcpy(&s.flags,extension+16,4);std::memcpy(&s.luxSource,extension+20,4);
                uint64_t reserved;std::memcpy(&reserved,extension+24,8);
                if(!s.timestamp || reserved || (s.flags&~15u) || s.luxSource>2 ||
                   ((s.flags&5)==5) || ((s.flags&10)==10) ||
                   ((s.flags&5)!=0)!=(s.luxSource!=0) ||
                   (s.hasLux()? !std::isfinite(s.lux) : s.lux!=0.f) ||
                   (s.hasAdrc()? (!std::isfinite(s.adrc)||s.adrc<=0.f) : s.adrc!=0.f))
                    throw std::runtime_error("Invalid NICE scene snapshot");
            }
            if(h[1]>=7) {
                const auto* records=static_cast<const uint8_t*>(address)+160;
                for(size_t i=0;i<7;++i)burst.ae[i].read(records+i*NiceAe::transportBytes);
                if(burst.ae[0].timestamp!=burst.scene.timestamp)
                    throw std::runtime_error("NICE AE reference timestamp mismatch");
            }
            if(h[1]>=9) {
                // Luma/chroma strengths (0..2) and radii; four reserved zero floats.
                float t[8];std::memcpy(t,static_cast<const uint8_t*>(address)+160+7*NiceAe::transportBytes,32);
                for(float v:t)if(!std::isfinite(v))throw std::runtime_error("NICE luma/chroma tuning");
                // t[4]: burst-merge reduction (0 = all frames, 1 = reference only);
                // t[5]: 1 = also return the merged Bayer RAW for the DNG;
                // t[6]: L exposure ratio for an L built from the N frames (0 = captured L).
                if(t[0]<0||t[0]>2||t[1]<0||t[1]>2||t[2]<1||t[2]>4||t[3]<1||t[3]>12||t[4]<0||t[4]>1
                        ||(t[5]!=0&&t[5]!=1)||(t[6]!=0&&(t[6]<1.f||t[6]>64.f))||t[7])
                    throw std::runtime_error("NICE luma/chroma tuning range");
                burst.lumaChroma={t[0],t[1],t[2],t[3],1.f-t[4]};
                burst.mergedDng=t[5]==1;
                burst.syntheticLong=t[6];
            }
            auto data=reinterpret_cast<const uint16_t*>(static_cast<const uint8_t*>(address)+headerBytes);
            for(int f=0;f<7;++f)burst.raw[f]=data+f*pixels;
            for(uint32_t f=7;f<h[5];++f)burst.extraNormals.push_back(data+f*pixels);
            if(h[1]<3) {
                // Preserve old diagnostic burst replay while correcting its
                // obsolete reference-at-slot-3 transport convention.
                std::rotate(burst.raw.begin(),burst.raw.begin()+3,burst.raw.begin()+4);
                std::rotate(burst.exposure.begin(),burst.exposure.begin()+3,burst.exposure.begin()+4);
                std::rotate(burst.iso.begin(),burst.iso.begin()+3,burst.iso.begin()+4);
            }
        }catch(...){munmap(address,length);address=MAP_FAILED;throw;}
    }
    MappedNiceBurst(const MappedNiceBurst&)=delete;
    ~MappedNiceBurst(){if(address!=MAP_FAILED)munmap(address,length);}
};
struct Guide {
    int w=0,h=0;std::vector<float> v;
    float at(int x,int y)const{return v[size_t(y)*w+x];}
};
inline std::vector<Guide> guides(const Burst& b,int f) {
    Guide g{b.w/4,b.h/4,{}};g.v.resize(size_t(g.w)*g.h);
    for(int y=0;y<g.h;++y)for(int x=0;x<g.w;++x){
        float sum=0;int count=0;
        for(int dy=0;dy<4;++dy)for(int dx=0;dx<4;++dx)
            if(b.color(4*x+dx,4*y+dy)==1){sum+=b.sample(f,4*x+dx,4*y+dy);++count;}
        g.v[size_t(y)*g.w+x]=sum/count;
    }
    std::vector<Guide> result;result.push_back(std::move(g));
    while(result.back().w>=64 && result.back().h>=64) {
        const auto& prev=result.back();Guide next{prev.w/2,prev.h/2,{}};next.v.resize(size_t(next.w)*next.h);
        for(int y=0;y<next.h;++y)for(int x=0;x<next.w;++x)
            next.v[size_t(y)*next.w+x]=(prev.at(x*2,y*2)+prev.at(x*2+1,y*2)+prev.at(x*2,y*2+1)+prev.at(x*2+1,y*2+1))*.25f;
        result.push_back(std::move(next));
    }
    return result;
}
struct Shift { float x=0,y=0; };
inline float matchScore(const Guide& r,const Guide& d,int dx,int dy,float ev,int cx,int cy,int radius,int step) {
    double sum=0;int n=0;
    for(int y=std::max(0,cy-radius);y<std::min(r.h,cy+radius+1);y+=step)
        for(int x=std::max(0,cx-radius);x<std::min(r.w,cx+radius+1);x+=step){
            int sx=x+dx,sy=y+dy;if(sx<0||sy<0||sx>=d.w||sy>=d.h)continue;
            float a=r.at(x,y),v=d.at(sx,sy);
            if(a>.97f||v>.97f||a<.002f||v<.002f)continue;
            // Bounded relative residual avoids bright areas dominating shadows.
            float residual=std::abs(a-v/ev)/(0.02f+a);
            sum+=std::min(residual,.5f);++n;
        }
    return n>=16?float(sum/n):INFINITY;
}
inline Shift globalShift(const std::vector<Guide>& ref,const std::vector<Guide>& donor,float ev) {
    int dx=0,dy=0;
    for(int l=int(ref.size())-1;l>=0;--l) {
        if(l!=int(ref.size())-1){dx*=2;dy*=2;}
        int range=l==int(ref.size())-1?6:2,bx=dx,by=dy;
        float best=matchScore(ref[l],donor[l],dx,dy,ev,ref[l].w/2,ref[l].h/2,std::max(ref[l].w,ref[l].h),std::max(1,ref[l].w/96));
        for(int y=dy-range;y<=dy+range;++y)for(int x=dx-range;x<=dx+range;++x){
            float error=matchScore(ref[l],donor[l],x,y,ev,ref[l].w/2,ref[l].h/2,std::max(ref[l].w,ref[l].h),std::max(1,ref[l].w/96));
            if(error<best){best=error;bx=x;by=y;}
        }
        dx=bx;dy=by;
    }
    return {float(dx*4),float(dy*4)};
}
struct Warp {
    int nx=0,ny=0;std::vector<Shift> shifts;
    Shift at(int x,int y)const {
        float gx=float(x)/64,gy=float(y)/64;int ix=std::min(int(gx),nx-1),iy=std::min(int(gy),ny-1);
        int jx=std::min(ix+1,nx-1),jy=std::min(iy+1,ny-1);float tx=gx-ix,ty=gy-iy;
        Shift out;
        for(int j=0;j<2;++j)for(int i=0;i<2;++i){float w=(i?tx:1-tx)*(j?ty:1-ty);const auto& p=shifts[size_t(j?jy:iy)*nx+(i?jx:ix)];out.x+=p.x*w;out.y+=p.y*w;}
        return out;
    }
};
inline Warp align(const Burst& b,const std::vector<Guide>& ref,int f) {
    Warp warp{(b.w+63)/64+1,(b.h+63)/64+1,{}};warp.shifts.resize(size_t(warp.nx)*warp.ny);
    if(f==forwardReferenceSlot)return warp;
    auto donor=guides(b,f);Shift global=globalShift(ref,donor,b.exposure[f]);
    for(int gy=0;gy<warp.ny;++gy)for(int gx=0;gx<warp.nx;++gx){
        int dx=int(global.x/4),dy=int(global.y/4),bx=dx,by=dy;
        float best=matchScore(ref[0],donor[0],dx,dy,b.exposure[f],gx*16,gy*16,8,1);
        for(int y=dy-2;y<=dy+2;++y)for(int x=dx-2;x<=dx+2;++x){
            float e=matchScore(ref[0],donor[0],x,y,b.exposure[f],gx*16,gy*16,8,1);
            // Require material improvement over the global fit in flat/noisy patches.
            if(e+.003f<best){best=e;bx=x;by=y;}
        }
        warp.shifts[size_t(gy)*warp.nx+gx]={float(bx*4),float(by*4)};
    }
    return warp;
}

using NiceExecute=std::function<void(const std::vector<float>&,std::vector<float>&)>;
// CRE warp=2: transform a 2x2 cell's origin once, then copy all four sites
// with their DONOR tags. This is neither independent per-pixel rounding nor
// interpolation on a reference-colour sublattice.
inline int donorBorder(int x,int size) {
    if(x<0)x=-x;
    if(x>size-1)x=2*(size-1)-x;
    return std::clamp(x,0,size-1);
}
inline uint16_t raw14(const Burst& b,int f,int x,int y) {
    return uint16_t(std::min(16383.f,std::floor(b.sample(f,x,y)*16383.f+.5f)));
}
inline uint16_t warpOrderBayerAt(const Burst& b,int f,int x,int y,DonorPoint point) {
    const int sx=donorBorder(int(point.x+.5f)+(x&1),b.w);
    const int sy=donorBorder(int(point.y+.5f)+(y&1),b.h);
    return raw14(b,f,sx,sy)|uint16_t(b.color(sx,sy)<<14);
}
inline uint16_t warpOrderBayer(const Burst& b,int f,int x,int y,Shift shift) {
    return warpOrderBayerAt(b,f,x,y,{float(x&~1)+shift.x,float(y&~1)+shift.y});
}
inline uint16_t warpOrderBayerProjective(const Burst& b,int f,int x,int y,const BackwardHomography& h) {
    return warpOrderBayerAt(b,f,x,y,h.bayerOrigin(x,y));
}
// CRE swarp=6: three dense tagged planes, using the per-channel interpolation
// kernel's RGGB cell addressing and half-pixel convention.
inline std::array<uint16_t,3> warpShortRgbAt(const Burst& b,int f,DonorPoint point) {
    float fx=point.x,fy=point.y;
    if(fx<0)fx=-fx;
    if(fy<0)fy=-fy;
    if(fx>b.w-1)fx=2*(b.w-1)-fx;
    if(fy>b.h-1)fy=2*(b.h-1)-fy;
    const int ix=std::clamp(int(fx),0,b.w-1),iy=std::clamp(int(fy),0,b.h-1);
    const int xs[]={ix,std::min(ix+1,b.w-1)},ys[]={iy,std::min(iy+1,b.h-1)};
    const float rx=fx-ix,ry=fy-iy;
    const float weights[2][2]={{(1-rx)*(1-ry),rx*(1-ry)},{(1-rx)*ry,rx*ry}};
    std::array<uint16_t,3> result{};
    for(int c=0;c<3;++c){
        float sum=0;
        for(int j=0;j<2;++j)for(int i=0;i<2;++i){
            const int px=(xs[i]&~1)+(c==0?0:c==2?1:1-(ys[j]&1));
            const int py=(ys[j]&~1)+(c==0?0:c==2?1:ys[j]&1);
            sum+=raw14(b,f,px,py)*weights[j][i];
        }
        result[c]=uint16_t(std::clamp(int(sum+.5f),0,16383))|uint16_t(c<<14);
    }
    return result;
}
inline std::array<uint16_t,3> warpShortRgb(const Burst& b,int f,int x,int y,Shift shift) {
    return warpShortRgbAt(b,f,{x+shift.x+.5f,y+shift.y+.5f});
}
inline std::array<uint16_t,3> warpShortRgbProjective(const Burst& b,int f,int x,int y,const BackwardHomography& h) {
    return warpShortRgbAt(b,f,h.shortPosition(x,y));
}
inline void restoreSensorOrigin(std::vector<float>& rgb,int w,int h,int cfa) {
    // Same direction as CRE pixelShiftf32: dst reads max(dst-offset, 0).
    // Reverse traversal permits overlap, without another full-resolution RGB.
    for(int y=h-1;y>=0;--y)for(int x=w-1;x>=0;--x){
        const size_t src=(size_t(std::max(0,y-(cfa>>1)))*w+std::max(0,x-(cfa&1)))*3;
        const size_t dst=(size_t(y)*w+x)*3;
        for(int c=0;c<3;++c)rgb[dst+c]=rgb[src+c];
    }
}
using NiceAlignment = std::function<std::array<BackwardHomography,7>(Burst&)>;
inline std::vector<float> reconstruct(const Burst& sensor,const NiceExecute& execute,
                                      const std::function<void(const std::string&)>& report,
                                      const std::function<void(const std::string&,const std::vector<float>&,int,int)>& snapshot={},
                                      const NiceAlignment& alignment={},
                                      std::vector<uint16_t>* mergedDng=nullptr,
                                      std::vector<uint8_t>* effMap=nullptr) {
    using Clock=std::chrono::steady_clock;
    const auto started=Clock::now();
    auto millis=[](auto duration){return std::chrono::duration<double,std::milli>(duration).count();};
    Burst b=sensor;b.canonicalRggb=true;
    if(!std::isfinite(b.normCoefficient)||b.normCoefficient<.55f||b.normCoefficient>2.2f||
       !std::isfinite(b.noiseScale)||b.noiseScale<.25f||b.noiseScale>4.f)
        throw std::runtime_error("Invalid NICE internal VST tuning");
    constexpr int tile=forwardTileSize;
    std::array<Warp,7> warp;
    std::array<BackwardHomography,7> projective;
    // L built from the N frames: slot 4 is the reference until then (not tracked).
    if(b.syntheticLong>0){b.raw[4]=b.raw[forwardReferenceSlot];b.exposure[4]=1;}
    if(alignment) {
        projective=alignment(b);
        for(const auto& h:projective)h.validate();
    } else {
        auto ref=guides(b,forwardReferenceSlot);
        for(int f=0;f<7;++f)warp[f]=align(b,ref,f);
    }
    // Extra N frames: aligned to the reference like slots 1..3, then assigned
    // round-robin to the four N slots. Each slot input becomes a robust average
    // (same-colour sub-pixel donors in reference geometry; a donor that differs
    // from the slot's own frame by more than ~3 sigma of the difference is
    // rejected, so motion keeps the slot frame). 4 frames: unchanged path.
    const int extras=int(b.extraNormals.size());
    std::vector<std::function<DonorPoint(int,int)>> extraOrigin;
    std::array<std::vector<uint16_t>,4> mergedSlot;
    std::array<float,5> slotFrames{1,1,1,1,1};
    std::vector<Warp> extraWarp(extras);
    std::vector<BackwardHomography> extraProjective(extras);
    if(extras>0) {
        for(int first=0;first<extras;first+=3) {
            Burst group=b;
            // Only the extras need tracking here: other slots point at the reference
            // (skipped by align), instead of re-aligning N/L/S/ES for every group.
            for(int k=1;k<7;++k){group.raw[k]=group.raw[0];group.exposure[k]=1;}
            group.exposure[0]=b.exposure[0];
            for(int j=0;j<3&&first+j<extras;++j){group.raw[1+j]=b.extraNormals[first+j];group.exposure[1+j]=1;}
            if(alignment) {
                const auto h=alignment(group);
                for(int j=0;j<3&&first+j<extras;++j){h[1+j].validate();extraProjective[first+j]=h[1+j];}
            } else {
                const auto ref=guides(b,forwardReferenceSlot);
                for(int j=0;j<3&&first+j<extras;++j)extraWarp[first+j]=align(group,ref,1+j);
            }
        }
        const auto extrasAligned=Clock::now();
        auto slotOrigin=[&](int f,int x,int y)->DonorPoint{
            if(f==forwardReferenceSlot)return {float(x&~1),float(y&~1)};
            if(alignment)return projective[f].bayerOrigin(x,y);
            const Shift s=warp[f].at(x&~1,y&~1);return {float(x&~1)+s.x,float(y&~1)+s.y};
        };
        auto sameColour=[&](const uint16_t* data,DonorPoint origin,int x,int y){
            const float qx=std::max(0.f,origin.x*.5f),qy=std::max(0.f,origin.y*.5f);
            const int ix=int(qx),iy=int(qy);const float fx=qx-ix,fy=qy-iy;
            auto at=[&](int cx,int cy){
                int sx=2*cx+(x&1),sy=2*cy+(y&1);
                while(sx>b.w-1)sx-=2;while(sy>b.h-1)sy-=2;
                return b.sampleRaw(data,sx,sy);
            };
            return (at(ix,iy)*(1-fx)+at(ix+1,iy)*fx)*(1-fy)+(at(ix,iy+1)*(1-fx)+at(ix+1,iy+1)*fx)*fy;
        };
        const NiceNoise slotNoise=b.hasNormalNoise?b.normalNoise:b.cameraNoise?b.noise:imx06cHdrNoise(b.iso[forwardReferenceSlot]);
        std::array<std::vector<int>,4> members;
        for(int e=0;e<extras;++e)members[e%4].push_back(e);
        std::array<double,4> accepted{};
        // GPU first (same arithmetic; ~2.3 s on the CPU); CPU loop is the fallback.
        bool gpuSlots=false;
        if(alignment) {
            try {
                SuperResGpu gpu;
                for(int s=0;s<4;++s) {
                    if(members[s].empty())continue;
                    SuperResGpuInput in;
                    in.w=b.w;in.h=b.h;in.cfa=b.cfa;
                    for(int k=0;k<4;++k){in.black[k]=b.black[k];in.inv[k]=1.f/(b.white-b.black[k]);}
                    in.frames={b.raw[s]};
                    in.homography={s==forwardReferenceSlot?BackwardHomography{}:projective[s]};
                    for(int e:members[s]){in.frames.push_back(b.extraNormals[e]);in.homography.push_back(extraProjective[e]);}
                    accepted[s]=gpu.slotMerge(in,slotNoise.slope,slotNoise.offset,mergedSlot[s]);
                    slotFrames[s]=1+float(accepted[s]/(double(b.w)*b.h));
                }
                gpuSlots=true;
                report("NICE EXTRA N GPU: "+gpu.renderer);
            } catch(const std::exception& e) {
                report(std::string("NICE EXTRA N GPU unavailable, CPU merge: ")+e.what());
                accepted={};
            }
        }
        for(int s=0;s<4 && !gpuSlots;++s) {
            if(members[s].empty())continue;
            mergedSlot[s].resize(size_t(b.w)*b.h);
            std::mutex lock;
            mergeRowBands(b.h,[&](int y0,int y1){
                double used=0;
                for(int y=y0;y<y1;++y)for(int x=0;x<b.w;++x){
                    const float own=s==forwardReferenceSlot?b.sample(s,x,y):sameColour(b.raw[s],slotOrigin(s,x,y),x,y);
                    // Difference of two frames: twice the single-frame variance.
                    const float sigma=std::sqrt(std::max(2*(slotNoise.slope*own+slotNoise.offset),1e-12f));
                    float sum=own,weight=1;
                    for(int e:members[s]){
                        const DonorPoint o=alignment?extraProjective[e].bayerOrigin(x,y)
                            :DonorPoint{float(x&~1)+extraWarp[e].at(x&~1,y&~1).x,float(y&~1)+extraWarp[e].at(x&~1,y&~1).y};
                        const float v=sameColour(b.extraNormals[e],o,x,y);
                        if(v>=.95f||own>=.95f)continue;
                        const float d=(v-own)/(3*sigma);
                        const float w=fastNegExp(d*d);
                        sum+=w*v;weight+=w;used+=w;
                    }
                    mergedSlot[s][size_t(y)*b.w+x]=uint16_t(std::min(16383.f,std::floor(sum/weight*16383.f+.5f)));
                }
                std::lock_guard<std::mutex> guard(lock);accepted[s]+=used;
            });
            slotFrames[s]=1+float(accepted[s]/(double(b.w)*b.h));
        }
        report("NICE EXTRA N TIMING ms: align="+std::to_string(millis(extrasAligned-started))
            +" slotMerge="+std::to_string(millis(Clock::now()-extrasAligned)));
        report("NICE EXTRA N: "+std::to_string(extras)+" extra ZSL frames merged into slots; effective frames per slot N0..N3="
            +std::to_string(slotFrames[0])+","+std::to_string(slotFrames[1])+","+std::to_string(slotFrames[2])+","+std::to_string(slotFrames[3]));
    }
    // Every N frame (reference, the other three slots, all extras) merged on the
    // GPU with the motion-safe noise weights of the slot merge, in canonical
    // reference geometry. Shared by the synthetic L and the merged DNG.
    std::vector<uint16_t> canonical;double allAccepted=0;
    const NiceNoise normalBase=b.hasNormalNoise?b.normalNoise:b.cameraNoise?b.noise:imx06cHdrNoise(b.iso[forwardReferenceSlot]);
    auto mergeAll=[&]()->bool{
        if(!canonical.empty())return true;
        if(!alignment)return false;
        const auto mergeStarted=Clock::now();
        try {
            SuperResGpu gpu;
            SuperResGpuInput in;
            in.w=b.w;in.h=b.h;in.cfa=b.cfa;
            for(int k=0;k<4;++k){in.black[k]=b.black[k];in.inv[k]=1.f/(b.white-b.black[k]);}
            in.frames={b.raw[forwardReferenceSlot]};in.homography={BackwardHomography{}};
            for(int s=0;s<4;++s)if(s!=forwardReferenceSlot){in.frames.push_back(b.raw[s]);in.homography.push_back(projective[s]);}
            for(int e=0;e<extras;++e){in.frames.push_back(b.extraNormals[e]);in.homography.push_back(extraProjective[e]);}
            allAccepted=gpu.slotMerge(in,normalBase.slope,normalBase.offset,canonical);
            report("NICE ALL-N MERGE: frames="+std::to_string(in.frames.size())+" effective="
                +std::to_string(1+allAccepted/(double(b.w)*b.h))+" ms="+std::to_string(millis(Clock::now()-mergeStarted)));
            return true;
        } catch(const std::exception& e) {
            canonical.clear();
            report(std::string("NICE ALL-N MERGE unavailable: ")+e.what());
            return false;
        }
    };
    // Synthetic L: the merged N (sqrt(frames) better SNR than one N; 20 frames of
    // 30 ms hold more light than one 125 ms L) times the planned ratio, clipped at
    // the sensor white like a real L. Written in sensor layout; its noise model is
    // the merged N noise scaled into L units. Saves the ~0.25 s L frame per shot.
    std::vector<uint16_t> syntheticLong;
    if(b.syntheticLong>0) {
        const auto longStarted=Clock::now();
        const float r=b.syntheticLong;
        const bool merged=mergeAll();
        const double effective=merged?std::max(1.0,.75*(1+allAccepted/(double(b.w)*b.h))):1.0;
        const int dx=b.cfa&1,dy=b.cfa>>1;
        const uint16_t* reference=b.raw[forwardReferenceSlot];
        syntheticLong.assign(size_t(b.w)*b.h,0);
        mergeRowBands(b.h,[&](int y0,int y1){
            for(int y=y0;y<y1;++y)for(int x=0;x<b.w;++x){
                const float black=b.black[((y&1)<<1)|(x&1)];
                float m;
                if(merged) {
                    int cx=x-dx,cy=y-dy;if(cx<0)cx+=2;if(cy<0)cy+=2;
                    m=canonical[size_t(cy)*b.w+cx]*(1.f/16383.f);
                } else m=std::max(0.f,(float(reference[size_t(y)*b.w+x])-black)/(b.white-black));
                syntheticLong[size_t(y)*b.w+x]=uint16_t(std::lround(black+std::min(m*r,1.f)*(b.white-black)));
            }
        });
        b.normalNoise=normalBase;b.hasNormalNoise=true;
        b.raw[4]=syntheticLong.data();b.exposure[4]=r;b.iso[4]=b.iso[forwardReferenceSlot];
        b.noise={float(r*normalBase.slope/effective),float(double(r)*r*normalBase.offset/effective)};
        b.cameraNoise=true;b.noiseReferenceSlot=4;
        report("NICE SYNTHETIC L: ratio="+std::to_string(r)+" source="+(merged?"all N merged":"reference N")
            +" effectiveFrames="+std::to_string(effective)+" noiseSlope="+std::to_string(b.noise.slope)
            +" noiseOffset="+std::to_string(b.noise.offset)+" ms="+std::to_string(millis(Clock::now()-longStarted)));
    }
    // Merged DNG: the all-N merge written back in sensor coordinates and 14-bit
    // sensor levels (value = (black + m*(white-black)) * 16383/white).
    if(mergedDng && b.mergedDng && alignment) {
        const auto dngStarted=Clock::now();
        try {
            if(!mergeAll())throw std::runtime_error("GPU merge failed");
            const double accepted=allAccepted;
            const size_t frameCount=size_t(4+extras);
            const float k=16383.f/b.white;
            const int dx=b.cfa&1,dy=b.cfa>>1;
            mergedDng->assign(size_t(b.w)*b.h,0);
            mergeRowBands(b.h,[&](int y0,int y1){
                for(int y=y0;y<y1;++y)for(int x=0;x<b.w;++x){
                    // Sensor (x,y) is canonical (x-dx,y-dy); the first row/column
                    // takes the same-colour site two pixels in.
                    int cx=x-dx,cy=y-dy;if(cx<0)cx+=2;if(cy<0)cy+=2;
                    const float m=canonical[size_t(cy)*b.w+cx]*(1.f/16383.f);
                    const float black=b.black[((y&1)<<1)|(x&1)];
                    (*mergedDng)[size_t(y)*b.w+x]=uint16_t(std::clamp(std::lround((black+m*(b.white-black))*k),0L,16383L));
                }
            });
            report("NICE MERGED DNG: frames="+std::to_string(frameCount)+" effective="
                +std::to_string(1+accepted/(double(b.w)*b.h))+" ms="+std::to_string(millis(Clock::now()-dngStarted)));
        } catch(const std::exception& e) {
            mergedDng->clear();
            report(std::string("NICE MERGED DNG unavailable (reference RAW kept): ")+e.what());
        }
    }
    const auto motionFinished=Clock::now();
    if(!execute) {
        // No neural model on this SoC: portable HDR reconstruction (see vivo-nice-portable.h).
        const auto portableStarted=Clock::now();
        const bool haveMerged=mergeAll();
        const float rS=b.exposure[5],rE=b.exposure[6];
        auto donor=[&](int f,int x,int y)->float{
            DonorPoint origin;
            if(alignment) origin=projective[f].bayerOrigin(x,y);
            else {const Shift sh=warp[f].at(x&~1,y&~1);origin={float(x&~1)+sh.x,float(y&~1)+sh.y};}
            const float qx=std::max(0.f,origin.x*.5f),qy=std::max(0.f,origin.y*.5f);
            const int ix=int(qx),iy=int(qy);const float fx=qx-ix,fy=qy-iy;
            auto at=[&](int cx,int cy){
                int sx=2*cx+(x&1),sy=2*cy+(y&1);
                while(sx>b.w-1)sx-=2;while(sy>b.h-1)sy-=2;
                return b.sample(f,sx,sy);
            };
            return (at(ix,iy)*(1-fx)+at(ix+1,iy)*fx)*(1-fy)+(at(ix,iy+1)*(1-fx)+at(ix+1,iy+1)*fx)*fy;
        };
        std::vector<float> hdr(size_t(b.w)*b.h);
        mergeRowBands(b.h,[&](int y0,int y1){
            for(int y=y0;y<y1;++y)for(int x=0;x<b.w;++x){
                const float n=haveMerged?canonical[size_t(y)*b.w+x]*(1.f/16383.f):b.sample(forwardReferenceSlot,x,y);
                float v=n;
                if(n>.70f) {
                    // N is (nearly) clipped: rebuild the level from S, and from ES where S clips too.
                    const float t=portableSmooth(.70f,.94f,n);
                    const float s=donor(5,x,y);
                    float rebuilt=s/rS;
                    if(s>.88f){const float te=portableSmooth(.88f,.97f,s);rebuilt=rebuilt*(1-te)+donor(6,x,y)/rE*te;}
                    v=n*(1-t)+std::max(rebuilt,n)*t;
                }
                hdr[size_t(y)*b.w+x]=v;
            }
        });
        std::vector<float> rgb;
        portableDemosaic(hdr,b.w,b.h,[&](int x,int y){return b.color(x,y);},[](int x,int size){return reflectCfa(x,size);},
            [](int rows,const std::function<void(int,int)>& body){mergeRowBands(rows,body);},rgb);
        restoreSensorOrigin(rgb,b.w,b.h,b.cfa);
        report("SCAM HDR PORTABLE: HDR merge + demosaic (no neural model) merged="+std::string(haveMerged?"all N GPU":"reference N")
            +" S="+std::to_string(rS)+" ES="+std::to_string(rE)
            +" ms="+std::to_string(millis(Clock::now()-portableStarted))+" total="+std::to_string(millis(Clock::now()-started)));
        return rgb;
    }
    double inferenceMs=0;
    std::array<std::vector<uint16_t>,7> luts;
    const auto domains=forwardExposureDomains(b.exposure);
    if(b.cameraNoise && b.iso[b.noiseReferenceSlot]!=b.iso[4])
        throw std::runtime_error("Legacy NICE capture lacks the long-reference noise profile; NCH v4 required");
    auto n=b.cameraNoise?b.noise:imx06cHdrNoise(b.iso[4]);
    const auto calibratedNoise=n;
    n.slope*=b.noiseScale;n.offset*=b.noiseScale;
    report("NICE INTERNAL TUNING: normCoefficient="+std::to_string(b.normCoefficient)
        +" noiseVarianceScale="+std::to_string(b.noiseScale)
        +" calibratedSlope="+std::to_string(calibratedNoise.slope)
        +" calibratedOffset="+std::to_string(calibratedNoise.offset)
        +" effectiveSlope="+std::to_string(n.slope)+" effectiveOffset="+std::to_string(n.offset)
        +" learnedDenoise=fixed_weights learnedSharpen=fixed_weights TCE=not_connected");
    // The bundled graph was trained with a fixed ISO-50 normalization.
    // Camera2 noise may describe the frame but must not change the network's
    // tensor scale on every shot. Use the same normalization for VST and IVST.
    const auto baseline=imx06cHdrNoise(50);
    // ISO-50 norm is fixed; ref/refn noise and refNEV belong to L. The
    // output remains normal-reference linear RGB for the downstream adapter.
    float norm=b.normCoefficient*(2*std::sqrt(1.0f/baseline.slope+float(double(baseline.offset)/(double(baseline.slope)*baseline.slope)+.375)));
    const float range=domains.normalEV;
    const float sqrtEV=std::sqrt(domains.normalizationEV);
    VstMode2 p{0,n.slope,n.slope,n.offset,1,domains.normalizationEV,norm,1,{1,1,1},14,16};
    for(int f=0;f<7;++f){p.frameExposureRatio=domains.frameEV[f];luts[f]=makeVstMode2(p);}
    p.frameExposureRatio=1;
    auto inverse=makeInverseVstMode2(p,16,1.0f/65535,0);
    const float offset=float((double(n.offset)/(double(n.slope)*n.slope)+.375)/domains.normalizationEV);
    const float maskSignal=std::min(1.f/range,1.f)/n.slope;
    float vstMask=std::min(2*std::sqrt(maskSignal+offset)/norm,1.f);
    uint16_t mask=uint16_t(vstMask*65535);
    report(std::string("NICE calibration source=")+(b.cameraNoise?"transport noise profile":"legacy IMX06C")+" ISO="+std::to_string(b.iso[4])+" slope="+std::to_string(n.slope)+" normalizationISO=50 norm="+std::to_string(norm)+" mask="+std::to_string(vstMask)+" normalEV="+std::to_string(range)+" refNEV="+std::to_string(domains.normalizationEV));
    report("NICE input: reference in slot 0; canonical RGGB; N/L warp=2 ordered Bayer; S/ES swarp=6 planar RGB; output RGB with sensor-origin restoration");
    report("NICE forward profile: edge-anchored 544 input tiles, 512 work step, context=16, overlapFusion=0");
    // Two packed-RAW buffers: the next tile is warped and packed on the CPU cores
    // while the NPU runs the current one. Same arithmetic as the serial loop.
    std::array<std::array<std::vector<uint16_t>,7>,2> packedRaw;
    for(auto& set:packedRaw)for(int f=0;f<7;++f)set[f].resize(tile*tile*(f>=5?3:1));
    std::vector<float> result(size_t(b.w)*b.h*3,0),output(tile*tile*3);
    const auto xs=forwardTileAxis(b.w),ys=forwardTileAxis(b.h);
    std::vector<std::pair<size_t,size_t>> order;
    for(size_t iy=0;iy<ys.size();++iy)for(size_t ix=0;ix<xs.size();++ix)order.push_back({iy,ix});
    auto prepare=[&](size_t index){
        const auto& ty=ys[order[index].first];const auto& tx=xs[order[index].second];
        auto& raw=packedRaw[index&1];
        mergeRowBands(tile,[&](int y0,int y1){
            for(int f=0;f<7;++f)for(int y=y0;y<y1;++y)for(int x=0;x<tile;++x){
                int px=reflectCfa(tx.inputOrigin+x,b.w),py=reflectCfa(ty.inputOrigin+y,b.h);
                const size_t pos=size_t(y)*tile+x;
                if(f>=5){
                    const auto rgb=alignment?warpShortRgbProjective(b,f,px,py,projective[f]):warpShortRgb(b,f,px,py,warp[f].at(px,py));
                    for(int c=0;c<3;++c)raw[f][pos+c*tile*tile]=rgb[c];
                } else if(f<4 && !mergedSlot[f].empty()) {
                    // Pre-merged in reference geometry: reference-site tags.
                    raw[f][pos]=mergedSlot[f][size_t(py)*b.w+px]|uint16_t(b.color(px,py)<<14);
                } else {
                    raw[f][pos]=alignment?warpOrderBayerProjective(b,f,px,py,projective[f]):warpOrderBayer(b,f,px,py,warp[f].at(px&~1,py&~1));
                }
            }
        });
        std::array<TaggedFrame,7> frames;
        for(int f=0;f<7;++f)
            frames[f]={raw[f].data(),raw[f].size(),tile,0,
                    f>=5?size_t(tile*tile):0,f>=5?size_t(2*tile*tile):0,luts[f].data(),luts[f].size()};
        return packSevenFrames(frames,tile,tile,16383,0,sqrtEV/65535,mask,std::numeric_limits<float>::max());
    };
    int finished=0;
    // Reference tiles for weight extraction: raw network input (22 channels) and output (3).
    std::ofstream dumpIn,dumpOut;
    if(const char* dumpDir=std::getenv("SCAM_DUMP_FORWARD")) {
        dumpIn.open(std::string(dumpDir)+"/fwd-in.f32",std::ios::binary|std::ios::app);
        dumpOut.open(std::string(dumpDir)+"/fwd-out.f32",std::ios::binary|std::ios::app);
    }
    std::future<std::vector<float>> pending=std::async(std::launch::async,prepare,size_t(0));
    for(size_t index=0;index<order.size();++index){
        const auto& ty=ys[order[index].first];const auto& tx=xs[order[index].second];
        auto input=pending.get();
        if(index+1<order.size())pending=std::async(std::launch::async,prepare,index+1);
        if(snapshot && b.diagnostics && finished==0){
            std::vector<float> channels(size_t(tile)*tile*3);
            for(int slot:{forwardReferenceSlot,5}){
                for(size_t i=0;i<channels.size()/3;++i)for(int c=0;c<3;++c)
                    channels[i*3+c]=input[i*22+slot*3+c];
                snapshot(slot==forwardReferenceSlot?"nice-diag-input-N-ref":"nice-diag-input-S",channels,tile,tile);
            }
        }
        std::fill(output.begin(),output.end(),std::numeric_limits<float>::quiet_NaN());
        const auto inferenceStarted=Clock::now();
        execute(input,output);
        inferenceMs+=millis(Clock::now()-inferenceStarted);
        if(output.size()!=size_t(tile)*tile*3)throw std::runtime_error("NICE tile output shape changed");
        if(dumpIn && dumpOut && index%4==0) {
            dumpIn.write(reinterpret_cast<const char*>(input.data()),std::streamsize(input.size()*4));
            dumpOut.write(reinterpret_cast<const char*>(output.data()),std::streamsize(output.size()*4));
        }
        if(snapshot && (finished==0 || finished==int(xs.size()*ys.size()/2)))
            snapshot("nice-diag-model-tile-"+std::to_string(finished),output,tile,tile);
        for(int y=0;y<ty.outputSize;++y)for(int x=0;x<tx.outputSize;++x){
            size_t dst=size_t(ty.outputOrigin+y)*b.w+tx.outputOrigin+x;
            size_t src=size_t(y+ty.crop)*tile+x+tx.crop;
            for(int c=0;c<3;++c){float value=output[src*3+c];if(!std::isfinite(value))throw std::runtime_error("NICE tile has nonfinite/unwritten pixels");
                unsigned idx=unsigned(std::clamp(value*(65535.f/sqrtEV),0.f,65535.f));result[dst*3+c]=inverse[c][idx]*range;}
        }
        report("NICE TILE "+std::to_string(++finished)+"/"+std::to_string(xs.size()*ys.size()));
    }
    const auto lumaChromaStarted=Clock::now();
    // Luma/Chroma blend partner: the temporal merge of the aligned N frames and L,
    // not the single N reference, so lowering the model's denoise brings back the
    // burst's detail at the burst's noise level instead of one frame's noise.
    std::vector<float> mergedReference,superReference;
    float scale=1;
    SuperResStats stats;
    NiceNoise lumaChromaNoise{0,0};
    // Many N frames (extra ZSL): all N frames are combined at their own RAW sites
    // with sub-pixel weights instead of the bilinear slot merge (see superres.h).
    // Reduced burst merge needs the per-frame weights of the super-resolution merge.
    const bool superRes=b.lumaChroma.active() && (extras>0 || b.lumaChroma.merge<1.f);
    if(b.lumaChroma.active()) {
        const NiceNoise refNoise=b.hasNormalNoise?b.normalNoise:b.cameraNoise?b.noise:imx06cHdrNoise(b.iso[forwardReferenceSlot]);
        lumaChromaNoise=refNoise;
        // Model output / RAW scale, from green midtone pixels (same measure as applyLumaChroma).
        std::vector<float> ratios;
        for(int y=8;y<b.h-8;y+=16)for(int x=8;x<b.w-8;x+=16){
            if(b.color(x,y)!=1)continue;
            const float r=b.sample(forwardReferenceSlot,x,y),g=result[(size_t(y)*b.w+x)*3+1];
            if(r>.02f&&r<.7f&&g>0)ratios.push_back(g/r);
        }
        scale=medianOf(ratios);
        if(!std::isfinite(scale)||scale<.25f||scale>4.f)scale=1;
        if(superRes) {
            const auto started=Clock::now();
            std::vector<const uint16_t*> frames{b.raw[forwardReferenceSlot]};
            std::vector<int> slotOf{forwardReferenceSlot};
            for(int f=0;f<4;++f)if(f!=forwardReferenceSlot){frames.push_back(b.raw[f]);slotOf.push_back(f);}
            for(int e=0;e<extras;++e){frames.push_back(b.extraNormals[e]);slotOf.push_back(-1-e);}
            superReference=superResolveReference(b.w,b.h,int(frames.size()),
                [&,inv=std::array<float,4>{1/(b.white-b.black[0]),1/(b.white-b.black[1]),1/(b.white-b.black[2]),1/(b.white-b.black[3])}]
                (int f,int x,int y){
                    // b.sampleRaw with the canonical shift, border reflection only when needed.
                    x+=b.cfa&1;y+=b.cfa>>1;
                    if(x<0||y<0||x>=b.w||y>=b.h){x=reflectCfa(x,b.w);y=reflectCfa(y,b.h);}
                    const int phase=((y&1)<<1)|(x&1);
                    // Signed below black, as on the GPU (no clipping bias in the average).
                    return std::clamp((float(frames[f][size_t(y)*b.w+x])-b.black[phase])*inv[phase],-.25f,1.f);
                },
                [&](int f,int x,int y)->DonorPoint{
                    const int slot=slotOf[f];
                    if(alignment){
                        const BackwardHomography& hm=slot>=0?projective[slot]:extraProjective[-1-slot];
                        DonorPoint p=hm.project(x,y);if(hm.upRatio!=1){p.x/=hm.upRatio;p.y/=hm.upRatio;}return p;
                    }
                    const Shift s=slot>=0?warp[slot].at(x&~1,y&~1):extraWarp[-1-slot].at(x&~1,y&~1);
                    return {float(x)+s.x,float(y)+s.y};
                },
                [&](int x,int y){return b.color(x,y);},result,scale,refNoise,stats,b.lumaChroma.merge,
                // Merge on the GPU when the frames are aligned by homographies (stock
                // CRE); any GPU failure falls back to the identical CPU merge.
                [&](std::vector<float>& out,std::vector<float>& effective,std::vector<double>& share)->bool{
                    if(!alignment||frames.size()>32)return false;
                    try{
                        SuperResGpuInput in;
                        in.w=b.w;in.h=b.h;in.cfa=b.cfa;
                        for(int k=0;k<4;++k){in.black[k]=b.black[k];in.inv[k]=1.f/(b.white-b.black[k]);in.phaseColor[k]=b.color(k&1,k>>1);}
                        in.frames=frames;in.model=&result;in.invScale=1.f/scale;
                        in.homography.resize(frames.size());
                        for(size_t f=1;f<frames.size();++f){const int slot=slotOf[f];in.homography[f]=slot>=0?projective[slot]:extraProjective[-1-slot];}
                        SuperResGpu gpu;
                        const SuperResTuning tune=loadSuperResTuning(report);
                        gpu.merge(in,refNoise.slope,refNoise.offset,b.lumaChroma.merge,out,effective,share,tune);
                        report("NICE SUPERRES GPU: "+gpu.renderer);
                        return true;
                    }catch(const std::exception& e){
                        report(std::string("NICE SUPERRES GPU unavailable, CPU merge: ")+e.what());
                        return false;
                    }
                });
            std::string robust;
            for(size_t f=1;f<stats.robust.size();++f)robust+=(f>1?",":"")+std::to_string(int(stats.robust[f]*100));
            report("NICE SUPERRES REF: frames="+std::to_string(frames.size())+" merge="+std::to_string(b.lumaChroma.merge)+" robust%="+robust
                +" meanWeight="+std::to_string(stats.coverage)+" ms="+std::to_string(millis(Clock::now()-started))
                +" (robust="+std::to_string(int(stats.robustMs))+" merge="+std::to_string(int(stats.mergeMs))+(stats.gpu?" GPU":" CPU")+")");
        } else
        mergedReference=mergeNormalAndLong(b,refNoise,[&](int f,int x,int y){
            if(f<4 && !mergedSlot[f].empty())return float(mergedSlot[f][size_t(y)*b.w+x])/16383.f;
            if(f==forwardReferenceSlot)return b.sample(f,x,y);
            // Same-colour donor with sub-pixel accuracy: bilinear interpolation on
            // the reference pixel's CFA plane (stride 2), so the donor keeps the CFA
            // phase (the model's tagged sampling may cross phases; a merge cannot)
            // without the up-to-1px misregistration of snapping to a cell.
            DonorPoint origin;
            if(alignment) origin=projective[f].bayerOrigin(x,y);
            else {const Shift s=warp[f].at(x&~1,y&~1);origin={float(x&~1)+s.x,float(y&~1)+s.y};}
            const float qx=std::max(0.f,origin.x*.5f),qy=std::max(0.f,origin.y*.5f);
            const int ix=int(qx),iy=int(qy);const float fx=qx-ix,fy=qy-iy;
            auto at=[&](int cx,int cy){
                int sx=2*cx+(x&1),sy=2*cy+(y&1);
                while(sx>b.w-1)sx-=2;while(sy>b.h-1)sy-=2;
                return b.sample(f,sx,sy);
            };
            return (at(ix,iy)*(1-fx)+at(ix+1,iy)*fx)*(1-fy)+(at(ix,iy+1)*(1-fx)+at(ix+1,iy+1)*fx)*fy;
        },[&](int x,int y){
            return result[(size_t(y)*b.w+x)*3+b.color(x,y)]/scale;
        },report,slotFrames);
    }
    applyLumaChroma(result,b.w,b.h,b.lumaChroma,[&](int x,int y)->std::array<float,3>{
        if(!superReference.empty()){
            const float* p=&superReference[(size_t(y)*b.w+x)*3];return {p[0],p[1],p[2]};
        }
        // Model-guided reconstruction of the merged reference: the measured site
        // keeps its merged RAW value; the two missing colours are the model output
        // plus the merged-minus-model residual measured at neighbouring sites of
        // that colour. A plain bilinear demosaic here cost most of the resolution
        // whenever luma/chroma leaned toward the reference (soft text at luma 0).
        std::array<float,3> sum{0,0,0},weight{0,0,0},out;
        for(int j=-1;j<=1;++j)for(int i=-1;i<=1;++i){
            const int px=reflectCfa(x+i,b.w),py=reflectCfa(y+j,b.h),c=b.color(px,py);
            const float wt=float((i?1:2)*(j?1:2));
            const float residual=mergedReference[size_t(py)*b.w+px]-result[(size_t(py)*b.w+px)*3+c]/scale;
            sum[c]+=wt*residual;weight[c]+=wt;
        }
        for(int c=0;c<3;++c)out[c]=result[(size_t(y)*b.w+x)*3+c]/scale+(weight[c]>0?sum[c]/weight[c]:0.f);
        out[b.color(x,y)]=mergedReference[size_t(y)*b.w+x];
        return out;
    },report,lumaChromaNoise.slope,lumaChromaNoise.offset,[&](int x,int y)->float{
        // Frames behind the reference: per pixel for the burst merge, else the
        // slot merge's effective count (4 N slots with their extra frames).
        if(!stats.effectiveFrames.empty())return stats.effectiveFrames[size_t(y)*b.w+x];
        return slotFrames[0]+slotFrames[1]+slotFrames[2]+slotFrames[3];
    });
    const double lumaChromaMs=millis(Clock::now()-lumaChromaStarted);
    // The network saturates where the merged N does (its output plateaus at the N white), so
    // windows and lamps come out as one flat level. The short frames hold that range: rebuild
    // those pixels from S (and ES where S clips) exactly as the portable path does and blend
    // them in, in the network's own units.
    if(alignment) {
        const auto rebuildStarted=Clock::now();
        const bool haveMerged=mergeAll();
        const float rS=b.exposure[5],rE=b.exposure[6];
        auto donor=[&](int f,int x,int y)->float{
            const DonorPoint origin=projective[f].bayerOrigin(x,y);
            const float qx=std::max(0.f,origin.x*.5f),qy=std::max(0.f,origin.y*.5f);
            const int ix=int(qx),iy=int(qy);const float fx=qx-ix,fy=qy-iy;
            auto at=[&](int cx,int cy){
                int sx=2*cx+(x&1),sy=2*cy+(y&1);
                while(sx>b.w-1)sx-=2;while(sy>b.h-1)sy-=2;
                return b.sample(f,sx,sy);
            };
            return (at(ix,iy)*(1-fx)+at(ix+1,iy)*fx)*(1-fy)+(at(ix,iy+1)*(1-fx)+at(ix+1,iy+1)*fx)*fy;
        };
        std::vector<float> hdr(size_t(b.w)*b.h),weight(size_t(b.w)*b.h,0.f);
        std::atomic<long> rebuilt{0};
        mergeRowBands(b.h,[&](int y0,int y1){
            long local=0;
            for(int y=y0;y<y1;++y)for(int x=0;x<b.w;++x){
                const float n=haveMerged?canonical[size_t(y)*b.w+x]*(1.f/16383.f):b.sample(forwardReferenceSlot,x,y);
                float v=n;
                if(n>.70f) {
                    const float t=portableSmooth(.70f,.94f,n);
                    const float s=donor(5,x,y);
                    float value=s/rS;
                    if(s>.88f){const float te=portableSmooth(.88f,.97f,s);value=value*(1-te)+donor(6,x,y)/rE*te;}
                    v=n*(1-t)+std::max(value,n)*t;
                    weight[size_t(y)*b.w+x]=t;++local;
                }
                hdr[size_t(y)*b.w+x]=v;
            }
            rebuilt+=local;
        });
        if(rebuilt>0) {
            std::vector<float> portable;
            portableDemosaic(hdr,b.w,b.h,[&](int x,int y){return b.color(x,y);},[](int x,int size){return reflectCfa(x,size);},
                [](int rows,const std::function<void(int,int)>& body){mergeRowBands(rows,body);},portable);
            mergeRowBands(b.h,[&](int y0,int y1){
                for(int y=y0;y<y1;++y)for(int x=0;x<b.w;++x){
                    const float t=weight[size_t(y)*b.w+x];
                    if(t<=0.f)continue;
                    for(int c=0;c<3;++c){
                        float& o=result[(size_t(y)*b.w+x)*3+c];
                        o=o*(1-t)+std::max(portable[(size_t(y)*b.w+x)*3+c]*scale,o)*t;
                    }
                }
            });
        }
        report("NICE HIGHLIGHT REBUILD: pixels="+std::to_string(long(rebuilt))+" S="+std::to_string(rS)+" ES="+std::to_string(rE)
            +" ms="+std::to_string(millis(Clock::now()-rebuildStarted)));
    }
    restoreSensorOrigin(result,b.w,b.h,b.cfa);
    // Effective number of merged frames per pixel (1/8 frame steps, 0 = unknown) for the post
    // denoise: its strength follows the noise each pixel really has (rejected areas are noisier).
    if(effMap&&!stats.effectiveFrames.empty()&&stats.effectiveFrames.size()==size_t(b.w)*b.h){
        // The scale is set so that the median pixel gets code 64 (8.0 in 1/8 steps): the filters only use ratios to the
        // median, and with kernels that widen in flat areas the counts run far past 32 (the former fixed 1/8 scale saturated).
        std::vector<float> sample;
        for(size_t i=0;i<stats.effectiveFrames.size();i+=7)sample.push_back(stats.effectiveFrames[i]);
        float median=1.f;
        if(!sample.empty()){std::nth_element(sample.begin(),sample.begin()+sample.size()/2,sample.end());median=std::max(sample[sample.size()/2],1.f);}
        const float codeScale=64.f/median;
        effMap->assign(size_t(b.w)*b.h,0);
        for(int y=0;y<b.h;++y)for(int x=0;x<b.w;++x){
            const size_t src=size_t(std::max(0,y-(b.cfa>>1)))*b.w+std::max(0,x-(b.cfa&1));
            (*effMap)[size_t(y)*b.w+x]=uint8_t(std::clamp(std::lround(stats.effectiveFrames[src]*codeScale),1L,255L));
        }
        report("NICE EFFECTIVE MAP: median samples="+std::to_string(median)+" code scale="+std::to_string(codeScale));
    }
    report("NICE STAGES ms: lumaChroma="+std::to_string(lumaChromaMs)+"  alignment="+std::to_string(millis(motionFinished-started))
        +" inference="+std::to_string(inferenceMs)
        +" totalReconstruction="+std::to_string(millis(Clock::now()-started))
        +" tiles="+std::to_string(finished)+" model=nice-main-forward-v79.bin");
    return result;
}
} // namespace vivo_nice
