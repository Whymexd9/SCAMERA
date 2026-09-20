#pragma once
#include "vivo-nice-preprocess.h"
#include <fstream>
#include <functional>
#include <sys/mman.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <cstring>
#include <limits>

namespace vivo_nice {
// Camera2 adaptation around the recovered CRE tensor/VST contract. Alignment
// and tile padding are owned by SCAMERA; they are not claimed to be stock MEE.
struct Burst {
    int w=0,h=0,cfa=0;
    float white=0;
    NiceNoise noise{}; bool cameraNoise=false,diagnostics=false;
    std::array<float,4> black{};
    std::array<float,7> exposure{}; // sensor exposure products relative to N ref
    std::array<unsigned,7> iso{};
    std::array<const uint16_t*,7> raw{};
    int color(int x,int y) const {
        const int phase=((y&1)<<1)|(x&1);
        return phase==cfa?0:phase==(cfa^3)?2:1;
    }
    float sample(int f,int x,int y) const {
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
        if(fstat(fd,&st)||st.st_size<128||st.st_size>128+16000000LL*14){close(fd);throw std::runtime_error("Invalid NICE file size");}
        length=size_t(st.st_size);address=mmap(nullptr,length,PROT_READ,MAP_PRIVATE,fd,0);close(fd);
        if(address==MAP_FAILED)throw std::runtime_error("Cannot map NICE burst");
        try {
            uint32_t h[32];std::memcpy(h,address,128);
            if(h[0]!=0x3143484e || (h[1]!=1 && h[1]!=2) || h[2]<64 || h[3]<64 || h[2]%2 || h[3]%2 ||
               uint64_t(h[2])*h[3]>16000000 || h[4]>3 || h[5]!=7)
                throw std::runtime_error("Unsupported NICE dimensions/CFA/header");
            burst.w=int(h[2]);burst.h=int(h[3]);burst.cfa=int(h[4]);
            std::memcpy(&burst.white,h+6,4);std::memcpy(burst.black.data(),h+7,16);
            std::memcpy(burst.exposure.data(),h+11,28);std::memcpy(burst.iso.data(),h+18,28);
            if(h[1]==2) {
                std::memcpy(&burst.noise.slope,h+25,4);std::memcpy(&burst.noise.offset,h+26,4);
                if(!std::isfinite(burst.noise.slope)||burst.noise.slope<=0||!std::isfinite(burst.noise.offset)||burst.noise.offset<0||h[27]>1)
                    throw std::runtime_error("Invalid Camera2 noise profile/diagnostic flags");
                burst.cameraNoise=true;burst.diagnostics=h[27]!=0;
            }
            for(int i=h[1]==2?28:25;i<32;++i)if(h[i])throw std::runtime_error("NICE reserved header");
            if(!std::isfinite(burst.white)||burst.white>65535)throw std::runtime_error("NICE white level");
            for(float b:burst.black)if(!std::isfinite(b)||b<0||b+1>=burst.white)throw std::runtime_error("NICE black level");
            for(int i=0;i<7;++i) {
                if(!std::isfinite(burst.exposure[i])||burst.exposure[i]<1.0f/256||burst.exposure[i]>256||burst.iso[i]==0||(!burst.cameraNoise&&(burst.iso[i]<50||burst.iso[i]>12800)))
                    throw std::runtime_error("NICE exposure or ISO outside calibrated range");
            }
            if(std::abs(burst.exposure[3]-1)>1e-5f)throw std::runtime_error("NICE reference exposure mismatch");
            size_t pixels=size_t(burst.w)*burst.h;
            if(length!=128+pixels*14)throw std::runtime_error("Truncated NICE RAW burst");
            auto data=reinterpret_cast<const uint16_t*>(static_cast<const uint8_t*>(address)+128);
            for(int f=0;f<7;++f)burst.raw[f]=data+f*pixels;
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
    if(f==3)return warp;
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
inline uint16_t warpBayer(const Burst& b,int f,int x,int y,Shift shift) {
    // Interpolate on the reference site's 2x2 CFA sublattice. Rounding a
    // continuous displacement to sensor pixels switches R/G/B at every half
    // pixel contour of the warp field, corrupting the sparse network input.
    const float sx=x+shift.x,sy=y+shift.y;
    if(!std::isfinite(sx)||!std::isfinite(sy)||sx<0||sy<0||sx>b.w-1||sy>b.h-1)
        return 0xc000;
    const int phaseX=x&1,phaseY=y&1;
    const float gx=(sx-phaseX)*.5f,gy=(sy-phaseY)*.5f;
    const int ix=int(std::floor(gx)),iy=int(std::floor(gy));
    const float tx=gx-ix,ty=gy-iy;
    const int x0=std::clamp(2*ix+phaseX,phaseX,b.w-2+phaseX);
    const int x1=std::clamp(2*(ix+1)+phaseX,phaseX,b.w-2+phaseX);
    const int y0=std::clamp(2*iy+phaseY,phaseY,b.h-2+phaseY);
    const int y1=std::clamp(2*(iy+1)+phaseY,phaseY,b.h-2+phaseY);
    const float a=b.sample(f,x0,y0)*(1-tx)+b.sample(f,x1,y0)*tx;
    const float d=b.sample(f,x0,y1)*(1-tx)+b.sample(f,x1,y1)*tx;
    const float value=a*(1-ty)+d*ty;
    return uint16_t(std::min(16383.f,std::floor(value*16383.f+.5f)))
            | uint16_t(b.color(x,y)<<14);
}
using NiceExecute=std::function<void(const std::vector<float>&,std::vector<float>&)>;
inline std::vector<float> reconstruct(const Burst& b,const NiceExecute& execute,
                                      const std::function<void(const std::string&)>& report,
                                      const std::function<void(const std::string&,const std::vector<float>&,int,int)>& snapshot={}) {
    constexpr int tile=544,margin=16,core=512,step=496;
    auto ref=guides(b,3);std::array<Warp,7> warp;
    std::array<std::vector<uint16_t>,7> luts;
    const auto n=b.cameraNoise?b.noise:imx06cHdrNoise(b.iso[3]);
    const auto baseline=b.cameraNoise?n:imx06cHdrNoise(50);
    // Ref and RefN are the same normal-frame slot in the forward HDR XML.
    // Camera2 black subtraction happens before 14-bit encoding, so black=0.
    float norm=2*std::sqrt(1.0f/baseline.slope+float(double(baseline.offset)/(double(baseline.slope)*baseline.slope)+.375));
    // Exposures are expressed relative to the shortest captured frame for the
    // unit-range IVST. Public tensors carry sqrt(EV_N); undo it before IVST and
    // restore Camera2 normal-reference radiance afterwards (which may exceed 1).
    const float range=1.f/(*std::min_element(b.exposure.begin(),b.exposure.end()));
    const float sqrtEV=std::sqrt(range);
    VstMode2 p{0,n.slope,n.slope,n.offset,1,range,norm,1,{1,1,1},14,16};
    for(int f=0;f<7;++f){p.frameExposureRatio=b.exposure[f]*range;luts[f]=makeVstMode2(p);warp[f]=align(b,ref,f);}
    p.frameExposureRatio=1;
    auto inverse=makeInverseVstMode2(p,16,1.0f/65535,0);
    const float offset=float(double(n.offset)/(double(n.slope)*n.slope)+.375);
    float vstMask=std::min(2*std::sqrt((1/n.slope+offset)/range)/norm,1.f);
    uint16_t mask=uint16_t(vstMask*65535);
    report(std::string("NICE calibration source=")+(b.cameraNoise?"Camera2":"legacy IMX06C")+" ISO="+std::to_string(b.iso[3])+" slope="+std::to_string(n.slope)+" norm="+std::to_string(norm)+" mask="+std::to_string(vstMask)+" HDR_range="+std::to_string(range));
    report("NICE alignment: phase-preserving bilinear Bayer warp");
    std::array<std::vector<uint16_t>,7> packedRaw;for(auto& v:packedRaw)v.resize(tile*tile);
    std::array<TaggedFrame,7> frames;
    std::vector<float> result(size_t(b.w)*b.h*3,0),weight(size_t(b.w)*b.h,0),output(tile*tile*3);
    std::vector<int> xs,ys;for(int x=0;;x+=step){xs.push_back(x);if(x+core>=b.w)break;}for(int y=0;;y+=step){ys.push_back(y);if(y+core>=b.h)break;}
    int finished=0;
    for(int oy:ys)for(int ox:xs){
        for(int f=0;f<7;++f){
            for(int y=0;y<tile;++y)for(int x=0;x<tile;++x){
                int px=reflectCfa(ox+x-margin,b.w),py=reflectCfa(oy+y-margin,b.h);
                auto shift=warp[f].at(px,py);
                packedRaw[f][size_t(y)*tile+x]=warpBayer(b,f,px,py,shift);
            }
            frames[f]={packedRaw[f].data(),packedRaw[f].size(),tile,0,0,0,luts[f].data(),luts[f].size()};
        }
        auto input=packSevenFrames(frames,tile,tile,16383,0,sqrtEV/65535,mask,std::numeric_limits<float>::max());
        std::fill(output.begin(),output.end(),std::numeric_limits<float>::quiet_NaN());
        execute(input,output);
        if(output.size()!=size_t(tile)*tile*3)throw std::runtime_error("NICE tile output shape changed");
        if(snapshot && (finished==0 || finished==int(xs.size()*ys.size()/2)))
            snapshot("nice-diag-model-tile-"+std::to_string(finished),output,tile,tile);
        for(int y=0;y<core&&oy+y<b.h;++y)for(int x=0;x<core&&ox+x<b.w;++x){
            float wx=(ox>0&&x<16)?float(x+1)/17:1;float wy=(oy>0&&y<16)?float(y+1)/17:1;
            if(ox+core<b.w&&x>=core-16)wx=float(core-x)/17;
            if(oy+core<b.h&&y>=core-16)wy=float(core-y)/17;
            float w=wx*wy;size_t dst=size_t(oy+y)*b.w+ox+x,src=size_t(y+margin)*tile+x+margin;
            for(int c=0;c<3;++c){float value=output[src*3+c];if(!std::isfinite(value))throw std::runtime_error("NICE tile has nonfinite/unwritten pixels");
                unsigned idx=unsigned(std::clamp(value*(65535.f/sqrtEV),0.f,65535.f));result[dst*3+c]+=inverse[c][idx]*w*range;}
            weight[dst]+=w;
        }
        report("NICE TILE "+std::to_string(++finished)+"/"+std::to_string(xs.size()*ys.size()));
    }
    for(size_t i=0;i<weight.size();++i){if(!(weight[i]>0))throw std::runtime_error("NICE tile coverage gap");for(int c=0;c<3;++c)result[i*3+c]/=weight[i];}
    return result;
}
} // namespace vivo_nice
