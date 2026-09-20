#pragma once
#include "vivo-nice-preprocess.h"
#include "vivo-nice-profile.h"
#include "vivo-nice-homography.h"
#include "vivo-nice-ae.h"
#include <fstream>
#include <functional>
#include <sys/mman.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <cstring>
#include <limits>

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
struct Burst {
    SceneMetadata scene;
    std::array<NiceAe,7> ae{};
    int w=0,h=0,cfa=0;
    int noiseReferenceSlot=0; // v1-v3 noise belongs to N; v4 belongs to L
    float white=0;
    NiceNoise noise{},normalNoise{}; bool hasNormalNoise=false; bool cameraNoise=false,diagnostics=false,canonicalRggb=false;
    std::array<float,4> black{};
    std::array<float,7> exposure{}; // sensor exposure products relative to N ref
    std::array<unsigned,7> iso{};
    std::array<const uint16_t*,7> raw{};
    int color(int x,int y) const {
        const int phase=((y&1)<<1)|(x&1);
        const int red=canonicalRggb?0:cfa;
        return phase==red?0:phase==(red^3)?2:1;
    }
    float sample(int f,int x,int y) const {
        // CRE UnpackAndToRGGB translates the sensor CFA before the network.
        // Keep this a view: seven additional full-frame copies are unnecessary.
        if(canonicalRggb){x=reflectCfa(x+(cfa&1),w);y=reflectCfa(y+(cfa>>1),h);}
        const float b=black[((y&1)<<1)|(x&1)];
        return std::clamp((float(raw[f][size_t(y)*w+x])-b)/(white-b),0.0f,1.0f);
    }
};
struct MappedNiceBurst {
    void* address=MAP_FAILED;size_t length=0;Burst burst;
    explicit MappedNiceBurst(const std::string& path) {
        int fd=open(path.c_str(),O_RDONLY|O_CLOEXEC);
        if(fd<0)throw std::runtime_error("Cannot open NICE burst");
        struct stat st{};
        if(fstat(fd,&st)||st.st_size<128||uint64_t(st.st_size)>160+7*NiceAe::transportBytes+16000000ULL*14){close(fd);throw std::runtime_error("Invalid NICE file size");}
        length=size_t(st.st_size);address=mmap(nullptr,length,PROT_READ,MAP_PRIVATE,fd,0);close(fd);
        if(address==MAP_FAILED)throw std::runtime_error("Cannot map NICE burst");
        try {
            uint32_t h[32];std::memcpy(h,address,128);
            if(h[0]!=0x3143484e || (h[1]<1 || h[1]>7) || h[2]<64 || h[3]<64 || h[2]%2 || h[3]%2 ||
               uint64_t(h[2])*h[3]>16000000 || h[4]>3 || h[5]!=7)
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
            for(int i=h[1]>=5?30:h[1]>=2?28:25;i<32;++i)if(h[i])throw std::runtime_error("NICE reserved header");
            if(!std::isfinite(burst.white)||burst.white>65535)throw std::runtime_error("NICE white level");
            for(float b:burst.black)if(!std::isfinite(b)||b<0||b+1>=burst.white)throw std::runtime_error("NICE black level");
            for(int i=0;i<7;++i) {
                if(!std::isfinite(burst.exposure[i])||burst.exposure[i]<1.0f/256||burst.exposure[i]>256||burst.iso[i]==0||(!burst.cameraNoise&&(burst.iso[i]<50||burst.iso[i]>12800)))
                    throw std::runtime_error("NICE exposure or ISO outside calibrated range");
            }
            if(std::abs(burst.exposure[h[1]<3?3:forwardReferenceSlot]-1)>1e-5f)throw std::runtime_error("NICE reference exposure mismatch");
            size_t pixels=size_t(burst.w)*burst.h;
            const size_t headerBytes=h[1]>=7?160+7*NiceAe::transportBytes:h[1]>=6?160:128;
            if(length!=headerBytes+pixels*14)throw std::runtime_error("Truncated NICE RAW burst");
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
            auto data=reinterpret_cast<const uint16_t*>(static_cast<const uint8_t*>(address)+headerBytes);
            for(int f=0;f<7;++f)burst.raw[f]=data+f*pixels;
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
inline int reflectCfa(int x,int size) {
    // Even extension retains the parity/color of each extrapolated RAW site.
    while(x<0||x>=size){if(x<0)x=-x;else x=2*(size-1)-x;}
    return x;
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
                                      const NiceAlignment& alignment={}) {
    Burst b=sensor;b.canonicalRggb=true;
    constexpr int tile=forwardTileSize;
    std::array<Warp,7> warp;
    std::array<BackwardHomography,7> projective;
    if(alignment) {
        projective=alignment(b);
        for(const auto& h:projective)h.validate();
    } else {
        auto ref=guides(b,forwardReferenceSlot);
        for(int f=0;f<7;++f)warp[f]=align(b,ref,f);
    }
    std::array<std::vector<uint16_t>,7> luts;
    const auto domains=forwardExposureDomains(b.exposure);
    if(b.cameraNoise && b.iso[b.noiseReferenceSlot]!=b.iso[4])
        throw std::runtime_error("Legacy NICE capture lacks the long-reference noise profile; NCH v4 required");
    const auto n=b.cameraNoise?b.noise:imx06cHdrNoise(b.iso[4]);
    // The bundled graph was trained with a fixed ISO-50 normalization.
    // Camera2 noise may describe the frame but must not change the network's
    // tensor scale on every shot. Use the same normalization for VST and IVST.
    const auto baseline=imx06cHdrNoise(50);
    // ISO-50 norm is fixed; ref/refn noise and refNEV belong to L. The
    // output remains normal-reference linear RGB for the downstream adapter.
    float norm=forwardNormCoefficient*(2*std::sqrt(1.0f/baseline.slope+float(double(baseline.offset)/(double(baseline.slope)*baseline.slope)+.375)));
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
    report("NICE forward profile: VST norm coefficient=1.1; edge-anchored 544 input tiles, 512 work step, context=16, overlapFusion=0");
    std::array<std::vector<uint16_t>,7> packedRaw;
    for(int f=0;f<7;++f)packedRaw[f].resize(tile*tile*(f>=5?3:1));
    std::array<TaggedFrame,7> frames;
    std::vector<float> result(size_t(b.w)*b.h*3,0),output(tile*tile*3);
    const auto xs=forwardTileAxis(b.w),ys=forwardTileAxis(b.h);
    int finished=0;
    for(const auto& ty:ys)for(const auto& tx:xs){
        for(int f=0;f<7;++f){
            for(int y=0;y<tile;++y)for(int x=0;x<tile;++x){
                int px=reflectCfa(tx.inputOrigin+x,b.w),py=reflectCfa(ty.inputOrigin+y,b.h);
                const size_t pos=size_t(y)*tile+x;
                if(f>=5){
                    const auto rgb=alignment?warpShortRgbProjective(b,f,px,py,projective[f]):warpShortRgb(b,f,px,py,warp[f].at(px,py));
                    for(int c=0;c<3;++c)packedRaw[f][pos+c*tile*tile]=rgb[c];
                } else {
                    packedRaw[f][pos]=alignment?warpOrderBayerProjective(b,f,px,py,projective[f]):warpOrderBayer(b,f,px,py,warp[f].at(px&~1,py&~1));
                }
            }
            frames[f]={packedRaw[f].data(),packedRaw[f].size(),tile,0,
                    f>=5?size_t(tile*tile):0,f>=5?size_t(2*tile*tile):0,luts[f].data(),luts[f].size()};
        }
        auto input=packSevenFrames(frames,tile,tile,16383,0,sqrtEV/65535,mask,std::numeric_limits<float>::max());
        if(snapshot && b.diagnostics && finished==0){
            std::vector<float> channels(size_t(tile)*tile*3);
            for(int slot:{forwardReferenceSlot,5}){
                for(size_t i=0;i<channels.size()/3;++i)for(int c=0;c<3;++c)
                    channels[i*3+c]=input[i*22+slot*3+c];
                snapshot(slot==forwardReferenceSlot?"nice-diag-input-N-ref":"nice-diag-input-S",channels,tile,tile);
            }
        }
        std::fill(output.begin(),output.end(),std::numeric_limits<float>::quiet_NaN());
        execute(input,output);
        if(output.size()!=size_t(tile)*tile*3)throw std::runtime_error("NICE tile output shape changed");
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
    restoreSensorOrigin(result,b.w,b.h,b.cfa);
    return result;
}
} // namespace vivo_nice
