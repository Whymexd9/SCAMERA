#pragma once
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>

// SCAMERA output controls, not undocumented Vivo algorithm parameters.
// Work on the enhancement residual against a half-pixel bilinear reference.
// Ten cached rows keep memory bounded even for 96 MP outputs.
namespace vivo_raisr {
struct Controls { unsigned strength=50, texture=60, halo=70; };
inline void referenceRow(const uint8_t* in, int w, int h, int ow, int oh,
                         int row, int channels, std::vector<float>& out) {
    float sy=std::clamp((row+.5f)*h/oh-.5f,0.f,float(h-1));
    int y=int(sy), y1=std::min(y+1,h-1); float fy=sy-y;
    for(int x=0;x<ow;++x) {
        float sx=std::clamp((x+.5f)*w/ow-.5f,0.f,float(w-1));
        int x0=int(sx),x1=std::min(x0+1,w-1);float fx=sx-x0;
        for(int c=0;c<channels;++c) {
            float a=in[(size_t(y)*w+x0)*channels+c]*(1-fx)+in[(size_t(y)*w+x1)*channels+c]*fx;
            float b=in[(size_t(y1)*w+x0)*channels+c]*(1-fx)+in[(size_t(y1)*w+x1)*channels+c]*fx;
            out[size_t(x)*channels+c]=a*(1-fy)+b*fy;
        }
    }
}
inline void finish(const uint8_t* in,uint8_t* out,int w,int h,int ow,int oh,Controls c) {
    if(c.strength==100 && c.texture==0 && c.halo==0)return;
    const float strength=c.strength*.01f, texture=c.texture*.01f, halo=c.halo*.01f;
    std::vector<float> base[5],residual[5];
    for(int i=0;i<5;++i){base[i].resize(ow);residual[i].resize(ow);}
    auto cache=[&](int row) {
        int slot=row%5;referenceRow(in,w,h,ow,oh,row,1,base[slot]);
        for(int x=0;x<ow;++x)residual[slot][x]=out[size_t(row)*ow+x]-base[slot][x];
    };
    for(int y=0;y<std::min(3,oh);++y)cache(y);
    auto sample=[&](int x,int y) {
        return float(in[size_t(std::clamp(y,0,h-1))*w+std::clamp(x,0,w-1)]);
    };
    // At texture=0 the raw enhancement remains available. At the tested 35%
    // setting, incoherent noise gets only 3.2% of the enhancement residual.
    const float noiseFloor=std::pow(1.f-texture,8.f);
    for(int y=0;y<oh;++y) {
        if(y+2<oh && y>0)cache(y+2);
        for(int x=0;x<ow;++x) {
            float b=base[y%5][x],r=residual[y%5][x],sum=0,weight=0,lo=b,hi=b;
            float xx=0,yy=0,xy=0,lapEnergy=0;
            const int inputX=std::min(int((x+.5f)*w/ow),w-1);
            const int inputY=std::min(int((y+.5f)*h/oh),h-1);
            for(int dy=-1;dy<=1;++dy) {
                int slot=std::clamp(y+dy,0,oh-1)%5;
                for(int dx=-1;dx<=1;++dx) {
                    int nx=std::clamp(x+dx,0,ow-1);float nb=base[slot][nx];
                    lo=std::min(lo,nb);hi=std::max(hi,nb);
                    float wt=1.f/(1.f+std::abs(nb-b)*.25f);
                    sum+=residual[slot][nx]*wt;weight+=wt;
                    if(texture>0) {
                        int px=inputX+dx,py=inputY+dy;
                        float left=sample(px-1,py),right=sample(px+1,py);
                        float top=sample(px,py-1),bottom=sample(px,py+1);
                        float gx=right-left,gy=bottom-top;
                        float lap=4.f*sample(px,py)-left-right-top-bottom;
                        xx+=gx*gx;yy+=gy*gy;xy+=gx*gy;lapEnergy+=lap*lap;
                    }
                }
            }
            // Suppress alternating fine residuals without smoothing the reference edge.
            r=r*(1-texture)+(sum/weight)*texture;
            if(texture>0) {
                // Structure tensor at INPUT resolution: interpolation would make
                // noise look coherent at 2x. Never measure the processed output.
                // White-noise gradient energy is 4*sigma^2; Laplacian is 20*sigma^2.
                float energy=xx+yy;
                float coherence=std::sqrt((xx-yy)*(xx-yy)+4.f*xy*xy)/(energy+1.e-6f);
                float signal=std::clamp(1.f-.2f*lapEnergy/(energy+1.e-6f),0.f,1.f);
                float confidence=std::clamp((coherence*signal-.25f)/.45f,0.f,1.f);
                confidence=confidence*confidence*(3.f-2.f*confidence);
                r*=noiseFloor+(1.f-noiseFloor)*confidence;
            }
            // At maximum protection, enhancement cannot create new local extrema.
            float bounded=std::clamp(b+r,lo,hi)-b;
            r=r*(1-halo)+bounded*halo;
            out[size_t(y)*ow+x]=uint8_t(std::clamp(std::lround(b+strength*r),0l,255l));
        }
    }
    const uint8_t* uv=in+size_t(w)*h;uint8_t* dst=out+size_t(ow)*oh;
    std::vector<float> row(ow);
    for(int y=0;y<oh/2;++y) {
        referenceRow(uv,w/2,h/2,ow/2,oh/2,y,2,row);
        for(int x=0;x<ow;++x) {
            size_t i=size_t(y)*ow+x;
            dst[i]=uint8_t(std::clamp(std::lround(row[x]+strength*(dst[i]-row[x])),0l,255l));
        }
    }
}
}
