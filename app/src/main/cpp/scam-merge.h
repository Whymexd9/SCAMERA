#pragma once
// Temporal merge used as the "reference" of the Luma/Chroma controls inside SCAM.
// The controls blend the model output with this image; blending with the single
// N reference frame brought back one frame's noise together with the detail.
// Here the aligned normal frames and the long frame are averaged in the N-ref
// domain (weights = relative exposure, i.e. collected light). Robustness is
// measured against the model output (anchor): it follows the reference geometry,
// so moving objects do not ghost, but unlike the single reference pixel it is not
// itself a noise outlier (a noisy anchor rejected every donor and left dark dots).
#include "scam-profile.h"
#include "scam-pool.h"
#include <algorithm>
#include <atomic>
#include <array>
#include <cmath>
#include <functional>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace scam {

// Runs body(y0,y1) over row bands on all cores.
// exp(-x) for x >= 0, tabulated with linear interpolation (abs error < 2e-5):
// the per-sample weight of the merges, where libm expf was a hot spot.
inline float fastNegExp(float x) {
    struct Table { float v[2050]; Table(){for(int i=0;i<2050;++i)v[i]=std::exp(-i/128.f);} };
    static const Table table;
    if(!(x<16.f))return x>=16.f?0.f:1.f;
    if(x<=0.f)return 1.f;
    const float t=x*128.f;const int i=int(t);const float f=t-float(i);
    return table.v[i]+(table.v[i+1]-table.v[i])*f;
}

inline void mergeRowBands(int h,const std::function<void(int,int)>& body) {
    const int threads=std::max(1u,std::min(8u,std::thread::hardware_concurrency()));
    if(threads<=1||h<64){body(0,h);return;}
    // Small chunks handed out dynamically: the work per row depends on the scene
    // (robust frames, clipped areas), and equal static bands left cores idle.
    const int chunk=std::max(4,h/(threads*16));
    // P31: inside a task of the shared pool (hybrid stages built during the alignment) the same chunks go to the pool's threads,
    // not to 8 new threads on top of them; every row is computed by the same body.
    if(ScamPool::inWorker()){ScamPool::get().rows(h,chunk,body);return;}
    std::atomic<int> next{0};
    std::vector<std::thread> pool;
    for(int t=0;t<threads;++t)pool.emplace_back([&]{
        for(int y0=next.fetch_add(chunk);y0<h;y0=next.fetch_add(chunk))body(y0,std::min(h,y0+chunk));
    });
    for(auto& thread:pool)thread.join();
}

// sampleAligned(f,x,y) returns frame f's sample aligned to reference pixel (x,y),
// normalised to [0,1] in f's own exposure, or a negative value when the donor has
// another CFA colour. anchor(x,y) is the model output at (x,y) for that pixel's
// colour, in the N-ref domain. Frames: 0..3 N (0 = ref), 4 = L.
// Callables are template parameters so the per-pixel calls inline (std::function
// cost dominated this loop).
// frames[f]: equal-exposure RAWs already averaged into slot f (extra ZSL N frames);
// its noise variance is lower by that factor and its weight higher by it.
template<class BurstT,class NoiseT,class SampleF,class AnchorF>
std::vector<float> mergeNormalAndLong(const BurstT& b,const NoiseT& refNoise,
        const SampleF& sampleAligned,const AnchorF& anchor,
        const std::function<void(const std::string&)>& report,
        const std::array<float,5>& frames={1,1,1,1,1}) {
    std::vector<float> merged(size_t(b.w)*b.h);
    const float clip=.95f,robustK=3.f,anchorWeight=.05f;
    std::array<double,5> used{};
    double wrongColour=0,clipped=0,samples=0,devSum=0;
    mergeRowBands(b.h,[&](int y0,int y1){
        std::array<double,5> local{};
        double lWrong=0,lClip=0,lSamples=0,lDev=0;
        for(int y=y0;y<y1;++y)for(int x=0;x<b.w;++x){
            const float a=std::max(anchor(x,y),0.f);
            float sum=anchorWeight*a,weight=anchorWeight;
            for(int f=0;f<5;++f){
                const float e=b.exposure[f];
                if(!(e>0))continue;
                const float raw=sampleAligned(f,x,y);
                if(raw<0){lWrong++;continue;}
                if(raw>=clip){lClip++;continue;}
                const float v=raw/e;
                // Frame f's noise in the ref domain: var = (slope*a*e + offset)/e^2.
                const float sigma=std::sqrt(std::max((refNoise.slope*a*e+refNoise.offset)/(e*e*frames[f]),1e-12f));
                const float d=(v-a)/(robustK*sigma);
                // |v-a|/sigma should average ~0.8 if the noise model matches the data.
                if(f==forwardReferenceSlot&&(x&7)==0&&(y&7)==0){lDev+=std::abs(d)*robustK;lSamples++;}
                const float w=e*frames[f]*fastNegExp(d*d);
                sum+=w*v;weight+=w;local[f]+=w/e;
            }
            merged[size_t(y)*b.w+x]=sum/weight;
        }
        static std::mutex lock;std::lock_guard<std::mutex> guard(lock);
        for(int f=0;f<5;++f)used[f]+=local[f];
        wrongColour+=lWrong;clipped+=lClip;samples+=lSamples;devSum+=lDev;
    });
    const double pixels=double(b.w)*b.h;
    std::string line="SCAM MERGE REF: accepted share N0..N3,L";
    for(int f=0;f<5;++f)line+=" "+std::to_string(used[f]/pixels);
    report(line+" wrongColour="+std::to_string(wrongColour/pixels)+" clipped="+std::to_string(clipped/pixels)
        +" refMeanAbsDev/sigma="+std::to_string(samples>0?devSum/samples:0));
    return merged;
}

}
