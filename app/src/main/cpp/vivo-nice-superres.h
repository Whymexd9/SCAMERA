#pragma once
// Multi-frame super-resolution reference for the NICE luma/chroma blend
// (after Wronski et al., "Handheld Multi-Frame Super-Resolution", 2019).
//
// Hand shake and ship/body motion put the N frames at random sub-pixel offsets,
// so around every reference pixel the burst holds samples of all three colours.
// Each output colour is the model output plus a kernel-weighted average of the
// residuals (sample minus model at the sample's position): where the frames
// jitter, the residual carries real sub-pixel texture; with no jitter it falls
// back to the model-guided demosaic of the averaged frames. The former reference
// resampled every donor bilinearly on its 2-px colour lattice and demosaiced
// with a 3x3 residual average, which low-passed fine texture before the blend.
//
// Robustness per frame (motion, e.g. waves) compares 2x2-cell means of the frame
// and the reference against the noise model plus a small alignment tolerance.
#include "vivo-nice-homography.h"
#include "vivo-nice-merge.h"
#include "vivo-nice-preprocess.h"
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <chrono>
#include <functional>
#include <mutex>
#include <vector>

namespace vivo_nice {

struct SuperResStats { std::vector<double> robust; double coverage=0; std::vector<float> effectiveFrames; double robustMs=0,mergeMs=0; bool gpu=false; };

// sample(f,x,y): frame f's normalised RAW at site (x,y) of its own grid; any
//   integer coordinates (the sampler reflects at the borders, keeping parity).
// origin(f,x,y): sub-pixel position of reference pixel (x,y) in frame f (f>0).
// color(x,y): 0 R, 1 G, 2 B of a site (same CFA phase in every frame).
// model: model RGB (w*h*3); model/scale is in RAW units.
// Returns RGB (w*h*3) in RAW units.
template<class Sample,class Origin,class Color>
std::vector<float> superResolveReference(int w,int h,int frames,Sample sample,Origin origin,Color color,
        const std::vector<float>& model,float scale,NiceNoise noise,SuperResStats& stats,float merge,
        const std::function<bool(std::vector<float>&,std::vector<float>&,std::vector<double>&)>& gpuMerge={}) {
    // GPU: robustness and merge in one pass over the strips; CPU below is the fallback.
    if(gpuMerge){
        const auto gpuStart=std::chrono::steady_clock::now();
        std::vector<float> gpuOut;std::vector<double> share;
        if(gpuMerge(gpuOut,stats.effectiveFrames,share)){
            stats.gpu=true;stats.robust=share;stats.robustMs=0;
            stats.mergeMs=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-gpuStart).count();
            return gpuOut;
        }
    }
    const int w2=w/2,h2=h/2;
    const float invScale=1.f/scale;
    // Reference cell means (frame 0) and their 3x3 range (alignment tolerance).
    std::vector<float> ref(size_t(w2)*h2),range(size_t(w2)*h2);
    mergeRowBands(h2,[&](int y0,int y1){
        for(int j=y0;j<y1;++j)for(int i=0;i<w2;++i)
            ref[size_t(j)*w2+i]=.25f*(sample(0,2*i,2*j)+sample(0,2*i+1,2*j)+sample(0,2*i,2*j+1)+sample(0,2*i+1,2*j+1));
    });
    mergeRowBands(h2,[&](int y0,int y1){
        for(int j=y0;j<y1;++j)for(int i=0;i<w2;++i){
            float lo=1e9f,hi=-1e9f;
            for(int dj=-1;dj<=1;++dj)for(int di=-1;di<=1;++di){
                const float v=ref[size_t(std::clamp(j+dj,0,h2-1))*w2+std::clamp(i+di,0,w2-1)];
                lo=std::min(lo,v);hi=std::max(hi,v);
            }
            range[size_t(j)*w2+i]=hi-lo;
        }
    });
    const auto robustStart=std::chrono::steady_clock::now();
    // Robustness of each frame per 2x2 cell, 0..255 (frame 0 is always 1).
    std::vector<std::vector<uint8_t>> robust(frames);
    stats.robust.assign(frames,1.0);
    std::vector<uint8_t> raw(size_t(w2)*h2);
    std::vector<float> means(size_t(w2)*h2);
    for(int f=1;f<frames;++f) {
        robust[f].resize(size_t(w2)*h2);
        double total=0;std::mutex lock;
        // Frame f's 2x2 cell means once, then bilinear lookups (was 16 RAW reads per cell).
        mergeRowBands(h2,[&](int y0,int y1){
            for(int j=y0;j<y1;++j)for(int i=0;i<w2;++i)
                means[size_t(j)*w2+i]=.25f*(sample(f,2*i,2*j)+sample(f,2*i+1,2*j)+sample(f,2*i,2*j+1)+sample(f,2*i+1,2*j+1));
        });
        mergeRowBands(h2,[&](int y0,int y1){
            for(int j=y0;j<y1;++j)for(int i=0;i<w2;++i){
                const DonorPoint o=origin(f,2*i,2*j);
                const float cx=o.x*.5f,cy=o.y*.5f;
                const int ix=int(std::floor(cx)),iy=int(std::floor(cy));
                const float fx=cx-ix,fy=cy-iy;
                auto cell=[&](int a,int c){
                    return means[size_t(std::clamp(c,0,h2-1))*w2+std::clamp(a,0,w2-1)];
                };
                const float g=(cell(ix,iy)*(1-fx)+cell(ix+1,iy)*fx)*(1-fy)+(cell(ix,iy+1)*(1-fx)+cell(ix+1,iy+1)*fx)*fy;
                const size_t k=size_t(j)*w2+i;const float m=ref[k];
                float r=0;
                if(m<.9f&&g<.9f) {
                    // Difference of two cell means: 2 * (single-site variance / 4),
                    // plus a small allowance for sub-pixel misregistration at edges.
                    const float var=std::max(noise.slope*m+noise.offset,1e-9f)*.5f;
                    const float tol2=9.f*var+(.04f*range[k])*(.04f*range[k]);
                    const float d=g-m;
                    r=std::max(0.f,(fastNegExp(d*d/tol2)-.25f)*(1.f/.75f));
                }
                raw[k]=uint8_t(std::lround(r*255));
            }
        });
        // A moving object also disagrees at its border cells: take the minimum
        // over the 3x3 cell neighbourhood, so a frame is used only where it agrees
        // all around (no partial mixing of moving content, e.g. waves).
        mergeRowBands(h2,[&](int y0,int y1){
            double acc=0;
            for(int j=y0;j<y1;++j)for(int i=0;i<w2;++i){
                uint8_t lo=255;
                for(int dj=-1;dj<=1;++dj)for(int di=-1;di<=1;++di)
                    lo=std::min(lo,raw[size_t(std::clamp(j+dj,0,h2-1))*w2+std::clamp(i+di,0,w2-1)]);
                robust[f][size_t(j)*w2+i]=lo;acc+=lo*(1.0/255);
            }
            std::lock_guard<std::mutex> guard(lock);total+=acc;
        });
        stats.robust[f]=total/(double(w2)*h2);
    }
    // User merge weight: scales every other frame's contribution (0 = reference only).
    merge=std::clamp(merge,0.f,1.f);
    if(merge<1.f)for(int f=1;f<frames;++f){
        double total=0;
        for(auto& v:robust[f]){v=uint8_t(std::lround(v*merge));total+=v;}
        stats.robust[f]=total/(255.0*double(w2)*h2);
    }
    stats.robustMs=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-robustStart).count();
    const auto mergeStart=std::chrono::steady_clock::now();

    // Gaussian kernel of the sample distance, tabulated (|d| <= 2 px per axis).
    constexpr float sigma=.7f;
    constexpr int lutSteps=256;
    std::array<float,2*lutSteps+1> kernel{};
    for(int i=0;i<=2*lutSteps;++i){const float d=float(i-lutSteps)*2.f/lutSteps;kernel[i]=std::exp(-d*d/(2*sigma*sigma));}
    // Fast LUT index (d within +-2 px): no lround in the inner loop.
    auto k1=[&](float d){
        const int i=int(d*(lutSteps/2.f)+float(lutSteps)+.5f);
        return kernel[std::clamp(i,0,2*lutSteps)];
    };
    auto roundInt=[](float v){return int(v+4096.5f)-4096;};
    // CFA phases: (px,py) and their colour.
    std::array<int,4> phaseColor;
    for(int p=0;p<4;++p)phaseColor[p]=color(p&1,p>>1);
    auto modelAt=[&](float x,float y,int c){
        x=std::clamp(x,0.f,float(w-1));y=std::clamp(y,0.f,float(h-1));
        const int ix=std::min(int(x),w-2),iy=std::min(int(y),h-2);
        const float fx=x-ix,fy=y-iy;
        const float* p=&model[(size_t(iy)*w+ix)*3+c];
        const float* q=p+size_t(w)*3;
        return ((p[0]*(1-fx)+p[3]*fx)*(1-fy)+(q[0]*(1-fx)+q[3]*fx)*fy)*invScale;
    };
    constexpr float prior=.02f;
    constexpr float relativeFloor=.004f; // RAW units; keeps shadows from dividing by ~0
    constexpr float achromatic=.75f;
    constexpr float chromaFloor=.2f;
    std::vector<float> out(size_t(w)*h*3);
    stats.effectiveFrames.assign(size_t(w)*h,1.f);
    double coverage=0;std::mutex lock;
    struct Acc{float num[3],den[3],numA,denA,denA2;};
    // Work per 2x2 cell: robustness is per cell, and the aligned position of a
    // cell's pixels is the cell origin plus the pixel offset (the frames differ by
    // a near-identity homography: < 0.01 px error), so each frame is projected once
    // per cell instead of once per pixel.
    // Frame-outer order inside bands of four cell rows: per cell, the 20 frames are
    // 20 separate memory streams (TLB/cache thrash); per band, each frame is read
    // sequentially and the accumulators (~1 MB per band) stay in cache.
    constexpr int bandRows=4;
    auto addTo=[&](Acc& e,int c,float kw,float v,float m){
        e.num[c]+=kw*(v-m);e.den[c]+=kw;
        const float ka=kw*kw; // narrower kernel (sigma/sqrt2) for luminance
        e.numA+=ka*(v-m)/(m+relativeFloor);e.denA+=ka;e.denA2+=ka*ka;
    };
    mergeRowBands(h2,[&](int c0,int c1){
        double acc=0;
        std::vector<Acc> band;
        for(int b0=c0;b0<c1;b0+=bandRows){
            const int b1=std::min(c1,b0+bandRows);
            band.resize(size_t(b1-b0)*w2*4);
            for(auto& e:band){e.num[0]=e.num[1]=e.num[2]=0;e.den[0]=e.den[1]=e.den[2]=prior;e.numA=0;e.denA=prior;e.denA2=0;}
            // Reference: the 2x2 lattice points of every phase around each pixel.
            for(int cy=b0;cy<b1;++cy)for(int cx=0;cx<w2;++cx){
                Acc* a=&band[(size_t(cy-b0)*w2+cx)*4];
                for(int q=0;q<4;++q){
                    const int x=2*cx+(q&1),y=2*cy+(q>>1);
                    for(int p=0;p<4;++p){
                        const int px=p&1,py=p>>1,c=phaseColor[p];
                        const int bx=((x-px)&~1)+px,by=((y-py)&~1)+py;
                        for(int dj=0;dj<=2;dj+=2)for(int di=0;di<=2;di+=2){
                            const int sx=bx+di,sy=by+dj;
                            const float kw=k1(float(sx-x))*k1(float(sy-y));
                            if(kw<.01f)continue;
                            const float v=sample(0,sx,sy);
                            if(v>=.95f)continue;
                            addTo(a[q],c,kw,v,model[(size_t(std::clamp(sy,0,h-1))*w+std::clamp(sx,0,w-1))*3+c]*invScale);
                        }
                    }
                }
            }
            for(int f=1;f<frames;++f)for(int cy=b0;cy<b1;++cy)for(int cx=0;cx<w2;++cx){
                const float r=robust[f][size_t(cy)*w2+cx]*(1.f/255);
                if(r<.02f)continue;
                Acc* a=&band[(size_t(cy-b0)*w2+cx)*4];
                const DonorPoint oc=origin(f,2*cx,2*cy);
                // The four pixels of the cell use at most 3x3 distinct sites of frame f,
                // all inside a 4x4 window; each site's RAW value and model value (its
                // position in reference coordinates is site + (2cx,2cy) - origin, one
                // shared bilinear fraction) is fetched once instead of per pixel.
                const int ax=int(std::floor(oc.x))-1,ay=int(std::floor(oc.y))-1;
                const float tx=float(2*cx)-oc.x,ty=float(2*cy)-oc.y;
                const int itx=int(std::floor(tx)),ity=int(std::floor(ty));
                const float mfx=tx-float(itx),mfy=ty-float(ity);
                float siteV[16],siteM[16];uint32_t have=0;
                for(int q=0;q<4;++q){
                    const float ox=oc.x+float(q&1),oy=oc.y+float(q>>1);
                    for(int p=0;p<4;++p){
                        const int px=p&1,py=p>>1,c=phaseColor[p];
                        const int sx=2*roundInt((ox-px)*.5f)+px,sy=2*roundInt((oy-py)*.5f)+py;
                        const float dx=float(sx)-ox,dy=float(sy)-oy;
                        const float kw=r*k1(dx)*k1(dy);
                        if(kw<.005f)continue;
                        const int gx=sx-ax,gy=sy-ay;
                        float v,m;
                        if(gx>=0&&gx<4&&gy>=0&&gy<4){
                            const int k=gy*4+gx;
                            if(!(have>>k&1u)){
                                have|=1u<<k;siteV[k]=sample(f,sx,sy);
                                if(siteV[k]<.95f){
                                    const int mx=std::clamp(sx+itx,0,w-2),my=std::clamp(sy+ity,0,h-2);
                                    const float* p0=&model[(size_t(my)*w+mx)*3+c];const float* p1=p0+size_t(w)*3;
                                    siteM[k]=((p0[0]*(1-mfx)+p0[3]*mfx)*(1-mfy)+(p1[0]*(1-mfx)+p1[3]*mfx)*mfy)*invScale;
                                }
                            }
                            v=siteV[k];m=siteM[k];
                        } else {
                            v=sample(f,sx,sy);
                            m=v<.95f?modelAt(float(2*cx+(q&1))+dx,float(2*cy+(q>>1))+dy,c):0.f;
                        }
                        if(v>=.95f)continue;
                        addTo(a[q],c,kw,v,m);
                    }
                }
            }
            for(int cy=b0;cy<b1;++cy)for(int cx=0;cx<w2;++cx){
                const Acc* a=&band[(size_t(cy-b0)*w2+cx)*4];
                for(int q=0;q<4;++q){
                    const int x=2*cx+(q&1),y=2*cy+(q>>1);
                    const Acc& e=a[q];
                    float* o=&out[(size_t(y)*w+x)*3];
                    const float* m=&model[(size_t(y)*w+x)*3];
                    // Colour-ratio reconstruction: model colour, luminance detail from the
                    // achromatic residual, true per-colour residual for the rest.
                    const float rel=e.numA/e.denA;
                    const float framesHere=e.denA2>0?std::max(1.f,(e.denA-prior)*(e.denA-prior)/e.denA2):1.f;
                    for(int c=0;c<3;++c){
                        const float mc=m[c]*invScale;
                        // Clean areas take the true per-colour residual in full (see the GPU merge).
                        const float snr=mc/std::sqrt(std::max(noise.slope*std::max(mc,0.f)+noise.offset,1e-9f)/framesHere);
                        const float t=std::clamp((snr-10.f)/40.f,0.f,1.f);
                        const float a=chromaFloor+(achromatic-chromaFloor)*(1.f-t*t*(3.f-2.f*t));
                        const float ct=std::clamp((framesHere-1.3f)/3.7f,0.f,1.f),cover=ct*ct*(3.f-2.f*ct);
                        o[c]=std::max(0.f,mc+cover*(a*(mc+relativeFloor)*rel+(1-a)*e.num[c]/e.den[c]));
                    }
                    acc+=e.den[0]+e.den[1]+e.den[2];
                    // Effective number of independent samples behind the luminance estimate
                    // (weighted average: (sum w)^2 / sum w^2), for the per-pixel noise level.
                    stats.effectiveFrames[size_t(y)*w+x]=e.denA2>0?std::max(1.f,(e.denA-prior)*(e.denA-prior)/e.denA2):1.f;
                }
            }
        }
        std::lock_guard<std::mutex> guard(lock);coverage+=acc;
    });
    stats.coverage=coverage/(double(w)*h*3);
    stats.mergeMs=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-mergeStart).count();
    return out;
}
} // namespace vivo_nice
