// P31 (shot speed W1.2 / W1.4): the work the hybrid front end now does ahead on the shared pool must give the same bytes as the
// code it replaced.
//   1. StockMotion::tableGuide (per-frame tables + one block pass) against sampleGuide (the per-sample guide before P31): every
//      byte, over CFA phases, canonical / sensor order, odd sizes, black levels, white levels (integer, fractional, 16-bit),
//      exposures and ceilings, with samples above white.
//   2. bentoBase (the stamped dilation) against the search dilation it replaced, and bentoMask with a shared base against
//      bentoMask building its own: every float of the mask, every count.
//   3. NicePool / NiceTasks / mergeRowBands inside pool tasks: every row exactly once, nested rows from many tasks at once (no
//      deadlock), exceptions back to the caller, the task group waits for its tasks.
// vivo-nice-hybrid.h is not self-contained: the worker includes vivo-nice-capture.h first.
#include "../app/src/main/cpp/vivo-nice-capture.h"
#include "../app/src/main/cpp/vivo-nice-hybrid.h"
#include "../app/src/main/cpp/vivo-nice-stock-motion.h"
#include <cassert>
#include <cstdio>
#include <random>
#include <string>
#include <vector>
using namespace vivo_nice;

// The base part of bentoMask before P31 (search dilation), for item 2.
static std::vector<float> oldBentoBase(const Burst& b,const HybridTuning& t,double& clippedFraction) {
    const int w2=b.w/2,h2=b.h/2;
    std::vector<uint8_t> clip(size_t(w2)*h2,0),near(size_t(w2)*h2,0);
    long clipped=0;
    const float nearLevel=std::min(t.bentoNearClip,t.bentoHighlight);
    for(int cy=0;cy<h2;++cy)for(int cx=0;cx<w2;++cx){
        bool c=false,nr=false;
        for(int p=0;p<4;++p){const float v=b.sample(0,2*cx+(p&1),2*cy+(p>>1));if(v>=t.bentoHighlight)c=true;if(v>=nearLevel)nr=true;}
        if(c){clip[size_t(cy)*w2+cx]=1;++clipped;}
        if(nr)near[size_t(cy)*w2+cx]=1;
    }
    clippedFraction=double(clipped)/(double(w2)*h2);
    const int r=std::max(0,t.bentoDilate);
    std::vector<uint8_t> dil(size_t(w2)*h2,0);
    for(int cy=0;cy<h2;++cy)for(int cx=0;cx<w2;++cx){
        bool on=false;
        for(int dy=-r;dy<=r&&!on;++dy){
            const int yy=cy+dy;if(yy<0||yy>=h2)continue;
            for(int dx=-r;dx<=r;++dx){
                const int xx=cx+dx;if(xx<0||xx>=w2)continue;
                const bool shape=(std::abs(dx)+std::abs(dy)<=r)||(std::abs(dx)==r||std::abs(dy)==r);
                if(shape&&clip[size_t(yy)*w2+xx]){on=true;break;}
            }
        }
        dil[size_t(cy)*w2+cx]=on?1:0;
    }
    const float sigma=std::max(t.bentoSmooth,0.01f);
    float k[7];float ks=0;for(int i=-3;i<=3;++i){k[i+3]=std::exp(-0.5f*i*i/(sigma*sigma));ks+=k[i+3];}
    for(float& v:k)v/=ks;
    std::vector<float> tmp(size_t(w2)*h2),mask(size_t(w2)*h2);
    for(int cy=0;cy<h2;++cy)for(int cx=0;cx<w2;++cx){
        float s=0;for(int i=-3;i<=3;++i)s+=k[i+3]*dil[size_t(cy)*w2+std::clamp(cx+i,0,w2-1)];
        tmp[size_t(cy)*w2+cx]=s;
    }
    for(int cy=0;cy<h2;++cy)for(int cx=0;cx<w2;++cx){
        float s=0;for(int i=-3;i<=3;++i)s+=k[i+3]*tmp[size_t(std::clamp(cy+i,0,h2-1))*w2+cx];
        mask[size_t(cy)*w2+cx]=std::clamp(s,0.f,1.f);
    }
    if(t.bentoNearClip<t.bentoHighlight){
        std::vector<uint8_t> nd(size_t(w2)*h2,0);
        for(int cy=0;cy<h2;++cy)for(int cx=0;cx<w2;++cx){
            bool on=false;
            for(int dy=-1;dy<=1&&!on;++dy){const int yy=cy+dy;if(yy<0||yy>=h2)continue;
                for(int dx=-1;dx<=1;++dx){const int xx=cx+dx;if(xx<0||xx>=w2)continue;if(near[size_t(yy)*w2+xx]){on=true;break;}}}
            nd[size_t(cy)*w2+cx]=on?1:0;
        }
        float g[3];float gs=0;for(int i=-1;i<=1;++i){g[i+1]=std::exp(-0.5f*i*i);gs+=g[i+1];}
        for(float& v:g)v/=gs;
        for(int cy=0;cy<h2;++cy)for(int cx=0;cx<w2;++cx){
            float s=0;for(int i=-1;i<=1;++i)s+=g[i+1]*nd[size_t(cy)*w2+std::clamp(cx+i,0,w2-1)];
            tmp[size_t(cy)*w2+cx]=s;
        }
        for(int cy=0;cy<h2;++cy)for(int cx=0;cx<w2;++cx){
            float s=0;for(int i=-1;i<=1;++i)s+=g[i+1]*tmp[size_t(std::clamp(cy+i,0,h2-1))*w2+cx];
            mask[size_t(cy)*w2+cx]*=std::clamp(s,0.f,1.f);
        }
    }
    return mask;
}

static bool sameResult(const BentoResult& a,const BentoResult& b) {
    return a.active==b.active&&a.reason==b.reason&&a.clippedFraction==b.clippedFraction&&a.usClippedRatio==b.usClippedRatio
        &&a.largestHole==b.largestHole&&a.inpaintHole==b.inpaintHole&&a.invalidCells==b.invalidCells&&a.maskCells==b.maskCells
        &&a.mask==b.mask&&a.smooth==b.smooth&&a.valid==b.valid;
}

int main() {
    std::mt19937 rng(31);
    // ---- 1. guides
    int guides=0;
    for(int size=0;size<4;++size){
        const int w=size==0?64:size==1?66:size==2?130:250,h=size==0?48:size==1?50:size==2?98:186;
        for(int cfa=0;cfa<4;++cfa)for(int canonical=0;canonical<2;++canonical)
        for(float white:{1023.f,4095.f,1023.5f,16383.f,65535.f}){
            std::vector<uint16_t> raw(size_t(w)*h);
            const int top=int(std::min(65535.f,white*1.1f+8));
            std::uniform_int_distribution<int> any(0,top),dark(0,64);
            for(size_t i=0;i<raw.size();++i)raw[i]=uint16_t((i%7==0)?any(rng):std::min(top,int(white*0.3f)+dark(rng)*int(white/256.f+1)));
            Burst b;b.w=w;b.h=h;b.cfa=cfa;b.canonicalRggb=canonical!=0;b.white=white;
            for(int p=0;p<4;++p)b.black[p]=float(dark(rng));
            for(float exposure:{1.f,0.125f,2.f,4.7f})for(float ceiling:{1.f,0.5f,1.f/4.7f}){
                const auto s=StockMotion::guideSource(b,raw.data(),exposure,ceiling);
                const auto expected=StockMotion::sampleGuide(s);
                assert(StockMotion::tableGuide(s)==expected);
                assert(StockMotion::tableGuide(s,true)==expected); // rows on the pool (the reference guide)
                ++guides;
            }
        }
    }
    std::printf("guides: %d tabulated guides byte-identical to the per-sample guide\n",guides);

    // ---- 2. Bento base
    int masks=0;
    for(int trial=0;trial<6;++trial){
        const int w=trial%2?260:192,h=trial%2?196:128;
        const float white=1023,black=64;
        std::vector<uint16_t> base(size_t(w)*h),us(size_t(w)*h);
        std::uniform_real_distribution<float> u(0.f,1.f);
        for(int y=0;y<h;++y)for(int x=0;x<w;++x){
            const float scene=0.3f+0.25f*std::sin(x*0.07f+trial)*std::cos(y*0.05f);
            float v=scene+0.05f*u(rng);
            if(((x/23+y/17+trial)%9)==0||u(rng)<0.002f)v=1.2f; // clipped patches and single sites
            base[size_t(y)*w+x]=uint16_t(std::clamp(black+v*(white-black),0.f,white));
            us[size_t(y)*w+x]=uint16_t(std::clamp(black+v/8.f*(white-black)*(u(rng)<0.01f?0.2f:1.f),0.f,white));
        }
        Burst b;b.w=w;b.h=h;b.cfa=trial%4;b.canonicalRggb=true;b.white=white;b.black.fill(black);
        b.raw.fill(base.data());b.exposure.fill(1.f);b.raw[1]=us.data();b.exposure[1]=0.125f;
        for(int r:{0,1,2,4,7})for(int nearOn=0;nearOn<2;++nearOn)for(int mode:{1,2}){
            HybridTuning t;t.bentoDilate=r;t.bento=mode;t.bentoMinClipped=trial==5?0.9f:0.00039f; // trial 5: below the clip minimum
            if(!nearOn)t.bentoNearClip=t.bentoHighlight;
            double cf=0;
            const auto expected=oldBentoBase(b,t,cf);
            const BentoBase bb=bentoBase(b,t);
            assert(bb.clippedFraction==cf);
            if(bb.full)assert(bb.mask==expected); else assert(cf<=t.bentoMinClipped&&t.bento!=2);
            BackwardHomography hm;hm.h={1,0,1.5f,0,1,-0.5f,0,0};
            const BentoResult own=bentoMask(b,1,hm,0.125f,t);
            const BentoResult shared=bentoMask(b,1,hm,0.125f,t,&bb);
            HybridTuning t2=t;t2.bento=2; // a validation mask on the base built for the first one
            const BentoResult own2=bentoMask(b,1,hm,0.125f,t2),shared2=bentoMask(b,1,hm,0.125f,t2,&bb);
            assert(sameResult(own,shared)&&sameResult(own2,shared2));
            ++masks;
        }
    }
    std::printf("bento: %d stamped base masks identical to the search dilation, shared and own bentoMask identical\n",masks);

    // ---- 3. pool
    auto& pool=NicePool::get();
    {
        std::vector<int> hits(10007,0);
        pool.rows(int(hits.size()),13,[&](int y0,int y1){for(int y=y0;y<y1;++y)++hits[y];});
        for(int v:hits)assert(v==1);
        NiceTasks tasks;
        std::vector<std::future<long>> sums;
        for(int k=0;k<40;++k)sums.push_back(tasks.run([k]{ // nested rows from many tasks at once
            std::vector<long> part(4096,0);
            mergeRowBands(4096,[&](int y0,int y1){for(int y=y0;y<y1;++y)part[y]=long(y)*k;});
            long s=0;for(long v:part)s+=v;return s;
        }));
        for(int k=0;k<40;++k)assert(sums[k].get()==long(k)*4096L*4095L/2L);
        bool thrown=false;
        try{pool.rows(1000,7,[](int y0,int){if(y0>=500)throw std::runtime_error("row");});}catch(const std::runtime_error&){thrown=true;}
        assert(thrown);
        auto failing=tasks.run([]()->int{throw std::runtime_error("task");});
        thrown=false;try{failing.get();}catch(const std::runtime_error&){thrown=true;}
        assert(thrown);
        std::atomic<int> done{0};
        {
            NiceTasks group;
            for(int k=0;k<20;++k)group.run([&done]{std::this_thread::sleep_for(std::chrono::milliseconds(2));++done;return 0;});
        } // the group waits for its tasks
        assert(done==20);
    }
    std::printf("pool: %d threads; rows once each, nested rows from 40 tasks, exceptions, task group wait\n",pool.size());
    std::printf("PASS: P31 prefetch work is bit-identical to the code it replaced\n");
    return 0;
}
