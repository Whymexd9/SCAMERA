#pragma once
// SCAMERA luma/chroma control inside the SCAM stage. The forward model's learned
// denoiser has fixed weights and no strength input, so strength is applied at its
// output, against the unwarped normal reference that the model itself received:
//   0..1  blend the model output back toward the reference (weaker model NR),
//   1..2  extra edge-aware smoothing on top of the model output (stronger NR),
// separately for luma (Y) and chroma (R-G, B-G). Everything runs in a square-root
// domain, where shot noise is roughly uniform, and the noise sigma is estimated
// from the shot itself (reference minus model output).
#include "scam-merge.h"
#include <algorithm>
#include <array>
#include <cmath>
#include <functional>
#include <string>
#include <thread>
#include <vector>

namespace scam {
struct ScamLumaChroma {
    float luma=1.f, chroma=1.f;      // 0..2, 1 = model as trained
    float lumaRadius=2.f, chromaRadius=4.f;
    // Weight of the other frames in the reference (1 = full burst merge, 0 = the
    // single reference frame: grain like the paired RAW, nothing averaged away).
    float merge=1.f;
    bool active() const { return std::abs(luma-1.f)>1e-4f || std::abs(chroma-1.f)>1e-4f || merge<1.f-1e-4f; }
};

template<class Fn> inline void parallelRows(int h, Fn fn) {
    const int n=std::max(1u,std::min(8u,std::thread::hardware_concurrency()));
    std::vector<std::thread> pool;
    for(int t=0;t<n;++t)pool.emplace_back([=]{for(int y=t;y<h;y+=n)fn(y);});
    for(auto& th:pool)th.join();
}

inline float medianOf(std::vector<float>& v) {
    if(v.empty())return NAN;
    auto mid=v.begin()+v.size()/2;std::nth_element(v.begin(),mid,v.end());return *mid;
}

// rgb: canonical-RGGB-origin linear camera RGB (before restoreSensorOrigin).
// referenceRgb(x,y): bilinear demosaic of the model's normal reference, same coords.
// noiseSlope/noiseOffset: single-frame RAW noise model (variance = slope*v + offset,
// RAW units); frames(x,y): effective number of frames averaged into the reference
// at that pixel. With them the threshold follows the real noise of every pixel
// (shadows, which the tone curve lifts many times, carry far more noise than the
// global median suggested, so the control barely acted there).
inline void applyLumaChroma(std::vector<float>& rgb,int w,int h,const ScamLumaChroma& lc,
        const std::function<std::array<float,3>(int,int)>& referenceRgb,
        const std::function<void(const std::string&)>& report,
        float noiseSlope=0,float noiseOffset=0,const std::function<float(int,int)>& frames={}) {
    if(!lc.active())return;
    const float luma=std::clamp(lc.luma,0.f,2.f),chroma=std::clamp(lc.chroma,0.f,2.f);
    // Reference and model output must share one linear scale; measure it.
    std::vector<float> ratios;
    for(int y=8;y<h-8;y+=16)for(int x=8;x<w-8;x+=16){
        const auto r=referenceRgb(x,y);const float g=rgb[(size_t(y)*w+x)*3+1];
        if(r[1]>.02f&&r[1]<.7f&&g>0)ratios.push_back(g/r[1]);
    }
    float k=medianOf(ratios);
    if(!std::isfinite(k)||k<.25f||k>4.f){report("SCAM LUMA/CHROMA: reference scale unavailable ("+std::to_string(k)+"), using 1");k=1;}
    auto toYab=[](float R,float G,float B,float* o){
        R=std::sqrt(std::max(R,0.f));G=std::sqrt(std::max(G,0.f));B=std::sqrt(std::max(B,0.f));
        o[0]=(R+G+B)*(1.f/3);o[1]=R-G;o[2]=B-G;
    };
    // Noise sigma (sqrt domain) from reference minus model output: the model
    // output is nearly noise-free, so the residual is the reference's noise.
    std::vector<float> dy,dc;
    for(int y=8;y<h-8;y+=7)for(int x=8;x<w-8;x+=7){
        const auto r=referenceRgb(x,y);const float* o=&rgb[(size_t(y)*w+x)*3];
        float a[3],b[3];toYab(r[0]*k,r[1]*k,r[2]*k,a);toYab(o[0],o[1],o[2],b);
        dy.push_back(std::abs(a[0]-b[0]));dc.push_back(std::abs(a[1]-b[1]));
    }
    const float sigmaY=std::max(1e-5f,1.4826f*medianOf(dy)),sigmaC=std::max(1e-5f,1.4826f*medianOf(dc));
    // Stage 1 (strength below 1): Wiener-style shrinkage of the reference detail.
    // d = reference - model is detail plus noise. Where its local energy is at the
    // noise level the model (denoised) value is kept; where it clearly exceeds the
    // noise it is real detail and is kept from the reference. The strength sets the
    // threshold in units of the noise variance: 0 = reference, 1 = model, and in
    // between noise goes away first while texture and edges stay - a plain linear
    // mix instead left both the model's plastic look and part of the noise.
    std::vector<float> yab(size_t(w)*h*3),ref;
    // Per-pixel noise variance in the sqrt (Yab) domain of the scaled reference:
    // var(sqrt(k v)) ~= k var(v) / (4 v); Y averages three channels, A/B are differences.
    auto pixelNoise=[&](int x,int y,float& vy,float& vc){
        const auto r=referenceRgb(x,y);
        const float n=frames?std::max(1.f,frames(x,y)):1.f;
        float v[3];
        for(int c=0;c<3;++c){const float s=std::max(r[c],2e-4f);v[c]=k*(noiseSlope*s+noiseOffset)/(4.f*s*n);}
        vy=(v[0]+v[1]+v[2])*(1.f/9);vc=.5f*(v[0]+v[2])+v[1];
    };
    const float bl=std::min(luma,1.f),bc=std::min(chroma,1.f);
    auto threshold=[](float s){return s>=1.f?1e30f:4.f*s/(1.f-s);};
    const float tl=threshold(bl),tc=threshold(bc);
    if(bl<1.f||bc<1.f){
        ref.resize(yab.size());
        parallelRows(h,[&](int y){
            for(int x=0;x<w;++x){
                const size_t i=(size_t(y)*w+x)*3;const auto r=referenceRgb(x,y);
                toYab(r[0]*k,r[1]*k,r[2]*k,&ref[i]);toYab(rgb[i],rgb[i+1],rgb[i+2],&yab[i]);
            }
        });
        std::vector<float> out(yab.size());
        parallelRows(h,[&](int y){
            for(int x=0;x<w;++x){
                const size_t i=(size_t(y)*w+x)*3;
                float vy=sigmaY*sigmaY,vc=sigmaC*sigmaC;
                if(noiseSlope>0) pixelNoise(x,y,vy,vc);
                // Local 3x3 energy of the difference (luma; chroma pair).
                float ey=0,ec=0;
                for(int j=-1;j<=1;++j){const int yy=std::clamp(y+j,0,h-1);
                    for(int m=-1;m<=1;++m){const size_t q=(size_t(yy)*w+std::clamp(x+m,0,w-1))*3;
                        const float dy0=ref[q]-yab[q],da=ref[q+1]-yab[q+1],db=ref[q+2]-yab[q+2];
                        ey+=dy0*dy0;ec+=.5f*(da*da+db*db);}}
                ey*=1.f/9;ec*=1.f/9;
                const float wy=bl<=0?1.f:std::max(0.f,1.f-tl*vy/std::max(ey,1e-12f));
                const float wc=bc<=0?1.f:std::max(0.f,1.f-tc*vc/std::max(ec,1e-12f));
                out[i]=yab[i]+wy*(ref[i]-yab[i]);
                out[i+1]=yab[i+1]+wc*(ref[i+1]-yab[i+1]);
                out[i+2]=yab[i+2]+wc*(ref[i+2]-yab[i+2]);
            }
        });
        yab.swap(out);
    } else {
        parallelRows(h,[&](int y){
            for(int x=0;x<w;++x){const size_t i=(size_t(y)*w+x)*3;toYab(rgb[i],rgb[i+1],rgb[i+2],&yab[i]);}
        });
    }
    // Stage 2: extra smoothing (strength above 1).
    const float el=std::max(luma-1.f,0.f),ec=std::max(chroma-1.f,0.f);
    const int rl=std::clamp(int(std::lround(lc.lumaRadius)),1,4),rc=std::clamp(int(std::lround(lc.chromaRadius)),1,12);
    const int sc=std::max(1,rc/3);
    const float ly0=1.f/(2*(1.5f*sigmaY)*(1.5f*sigmaY)),cy0=1.f/(2*(2.f*sigmaY)*(2.f*sigmaY)),cc0=1.f/(2*(3.f*sigmaC)*(3.f*sigmaC));
    const float sl=1.f/(2.f*rl*rl*.5f),ss=1.f/(2.f*rc*rc*.5f);
    parallelRows(h,[&](int y){
        for(int x=0;x<w;++x){
            const size_t i=(size_t(y)*w+x)*3;float Y=yab[i],A=yab[i+1],B=yab[i+2];
            float ly=ly0,cy=cy0,cc=cc0;
            if(noiseSlope>0 && (el>0||ec>0)){
                float vy,vc;pixelNoise(x,y,vy,vc);
                ly=1.f/(2*2.25f*vy);cy=1.f/(2*4.f*vy);cc=1.f/(2*9.f*vc);
            }
            if(el>0){
                float sum=0,wsum=0;
                for(int j=-rl;j<=rl;++j){const int yy=std::clamp(y+j,0,h-1);
                    for(int m=-rl;m<=rl;++m){const int xx=std::clamp(x+m,0,w-1);
                        const float v=yab[(size_t(yy)*w+xx)*3],d=v-yab[i];
                        const float wt=fastNegExp(d*d*ly+(j*j+m*m)*sl);sum+=wt*v;wsum+=wt;}}
                Y=yab[i]+el*(sum/wsum-yab[i]);
            }
            if(ec>0){
                float sa=0,sb=0,wsum=0;
                for(int j=-rc;j<=rc;j+=sc){const int yy=std::clamp(y+j,0,h-1);
                    for(int m=-rc;m<=rc;m+=sc){const int xx=std::clamp(x+m,0,w-1);
                        const float* q=&yab[(size_t(yy)*w+xx)*3];
                        const float d=q[0]-yab[i],da=q[1]-yab[i+1],db=q[2]-yab[i+2];
                        const float wt=fastNegExp(d*d*cy+(da*da+db*db)*cc+(j*j+m*m)*ss);
                        sa+=wt*q[1];sb+=wt*q[2];wsum+=wt;}}
                A=yab[i+1]+ec*(sa/wsum-yab[i+1]);B=yab[i+2]+ec*(sb/wsum-yab[i+2]);
            }
            const float G=Y-(A+B)*(1.f/3),R=G+A,Bl=G+B;
            rgb[i]=R>0?R*R:0;rgb[i+1]=G>0?G*G:0;rgb[i+2]=Bl>0?Bl*Bl:0;
        }
    });
    report("SCAM LUMA/CHROMA: luma="+std::to_string(luma)+" chroma="+std::to_string(chroma)
        +" lumaRadius="+std::to_string(rl)+" chromaRadius="+std::to_string(rc)
        +" referenceScale="+std::to_string(k)+" sigmaY="+std::to_string(sigmaY)+" sigmaC="+std::to_string(sigmaC)
        +(noiseSlope>0?" noise=per-pixel(slope="+std::to_string(noiseSlope)+",offset="+std::to_string(noiseOffset)+",frames="+(frames?"map":"1")+")":" noise=global"));
}
} // namespace scam
