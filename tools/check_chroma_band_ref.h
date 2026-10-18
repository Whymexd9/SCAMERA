#pragma once
// P213b: the CPU chroma median as it was before the streamed bands (c1aef307, app/src/main/cpp/scam-hybrid.h lines 7049-7191),
// verbatim but for its name: the reference of tools/check_chroma_band.cpp. bandRows -1 = the full-frame planes, > 0 (or 0 above
// 64 MP) = the bounded-rows strips (<= 7e-7 from the planes). Include after scam-hybrid.h (it uses its helpers).
namespace scam {
inline void mosaicChromaMedianRef(std::vector<float>& rgb,int w,int h,int block,const std::vector<float>* osc=nullptr,bool gpu=false,
                               std::string* how=nullptr,int bandRows=0){
    if(bandRows>=0&&(bandRows>0||uint64_t(w)*h>64000000)){
        const int halo=8*block,core=std::max(block,((bandRows>0?bandRows:256)/block)*block);
        const size_t row=size_t(w)*3;
        const int bw=w/block;
        const bool rawOsc=osc&&osc->size()==size_t(bw)*(h/block);
        std::vector<float> originalHalo;
        for(int y0=0;y0<h;y0+=core){
            const int y1=std::min(y0+core,h),first=std::max(0,y0-halo),last=std::min(h,y1+halo);
            std::vector<float> tile(size_t(last-first)*row);
            for(int y=first;y<last;++y){
                const float* source=y<y0?originalHalo.data()+size_t(y-first)*row:rgb.data()+size_t(y)*row;
                std::copy_n(source,row,tile.data()+size_t(y-first)*row);
            }
            // Later strips must read the original overlap, not the already filtered R/B sites.
            const int keep=std::max(0,y1-halo);
            std::vector<float> nextHalo(tile.begin()+size_t(keep-first)*row,tile.begin()+size_t(y1-first)*row);
            std::vector<float> tileOsc;
            if(rawOsc)tileOsc.assign(osc->begin()+size_t(first/block)*bw,
                                    osc->begin()+size_t(first/block+(last-first)/block)*bw);
            mosaicChromaMedianRef(tile,w,last-first,block,rawOsc?&tileOsc:nullptr,false,nullptr,-1);
            std::copy_n(tile.data()+size_t(y0-first)*row,size_t(y1-y0)*row,rgb.data()+size_t(y0)*row);
            originalHalo.swap(nextHalo);
        }
        if(how)*how="cpu (bounded rows "+std::to_string(core)+")";
        return;
    }
    if(gpu){
        // within the GPU storage of one call and a quarter of MemAvailable (any RAW size: 8192x6144 needs 2.4 GB, it stays here)
        const uint64_t avail=hybridMemAvailable();
        const size_t need=ChromaMedianGpu::bytesFor(w,h);
        if(int64_t(w)*h<=int64_t(16800000)&&(avail==0||need<avail/4)){
            try{auto g=chromaTake();g->process(rgb,w,h,block,osc);if(how)*how="gpu: "+g->times+" ms";chromaGiveBack(std::move(g));return;}
            catch(const std::exception& e){if(how)*how=std::string("cpu (")+e.what()+")";}
        } else if(how)*how="cpu (size)";
    } else if(how)*how="cpu";
    const int d=std::max(1,block/2);
    std::vector<float> u(size_t(w)*h),v(size_t(w)*h),t(size_t(w)*h);
    // Chroma as ratios to green (camera RGB before WB: differences R-G of a bright neighbour put on a dark line drove R and B
    // below zero, a green rim on every thin dark line). e keeps the ratio of black pixels finite.
    const float e=0.002f;
    // P30: the element-wise loops run on all cores (1.6 s for 12.6 MP on one core, the same values).
    auto rows=[&](const std::function<void(size_t,size_t)>& body){
        mergeRowBands(h,[&](int y0,int y1){body(size_t(y0)*w,size_t(y1)*w);});
    };
    // The green term is max(G, 0) + e (it was G + e: a pole at G = -e, where a last-bit change of a dark pixel swung its ratio from
    // +inf to -inf); the reconstruction below uses the same term, so a ratio the passes leave unchanged gives back R and B exactly.
    rows([&](size_t i0,size_t i1){for(size_t i=i0;i<i1;++i){const float g=std::max(rgb[i*3+1],0.f)+e;u[i]=(rgb[i*3]+e)/g;v[i]=(rgb[i*3+2]+e)/g;}});
    // Median of five by selection only (no arithmetic: the same value nth_element picked, ~10x faster): x, y = the 2nd and 3rd
    // of a..d, the median is e clamped to [x, y].
    auto med5=[](float a,float b,float c,float d,float e){
        const float t1=std::min(a,b),t2=std::max(a,b),t3=std::min(c,d),t4=std::max(c,d);
        const float lo=std::max(t1,t3),hi=std::min(t2,t4);
        const float x=std::min(lo,hi),y=std::max(lo,hi);
        return std::max(x,std::min(y,e));
    };
    for(std::vector<float>* ch:{&u,&v}){
        std::vector<float>& c=*ch;
        // P76: the same taps and selections; the clamps only at the borders, the five rows of the vertical pass taken once per row
        mergeRowBands(h,[&](int y0,int y1){
            for(int y=y0;y<y1;++y){const float* r=c.data()+size_t(y)*w;float* o=t.data()+size_t(y)*w;
                const int in0=std::min(2*d,w),in1=std::max(in0,w-2*d);
                auto edge=[&](int x){auto at=[&](int k){return r[std::clamp(x+k*d,0,w-1)];};o[x]=med5(at(-2),at(-1),at(0),at(1),at(2));};
                for(int x=0;x<in0;++x)edge(x);
                for(int x=in0;x<in1;++x)o[x]=med5(r[x-2*d],r[x-d],r[x],r[x+d],r[x+2*d]);
                for(int x=in1;x<w;++x)edge(x);}
        });
        mergeRowBands(h,[&](int y0,int y1){
            for(int y=y0;y<y1;++y){
                const float* a0=t.data()+size_t(std::clamp(y-2*d,0,h-1))*w;const float* a1=t.data()+size_t(std::clamp(y-d,0,h-1))*w;
                const float* a2=t.data()+size_t(y)*w;const float* a3=t.data()+size_t(std::clamp(y+d,0,h-1))*w;
                const float* a4=t.data()+size_t(std::clamp(y+2*d,0,h-1))*w;float* o=c.data()+size_t(y)*w;
                for(int x=0;x<w;++x)o[x]=med5(a0[x],a1[x],a2[x],a3[x],a4[x]);
            }
        });
    }
    std::vector<float>().swap(t); // any RAW size: every temporary goes once used (~40 B per pixel at the peak before)
    // False-colour suppression (v11 enable_false_color_suppression for remosaicked streams): where the luma oscillates faster
    // than the colour lattice can follow, the colour is aliased (zone-plate rings, fabric moire) and becomes the wide average
    // colour around. Oscillation: sign changes of the luma high-pass between neighbours, counted only where its swing exceeds 4 %
    // of the level (noise and smooth areas stay out; both tests are soft, mosaicSoftSignChange); a single edge changes sign along
    // one line only (a few % of the window), a grating of period p in 1/p of the pixels. Weight ramps over 0.51..1.02 / (2b), the
    // colour Nyquist period of the mosaic. Every decision of this pass is continuous (median, ratios, soft counts, smoothstep
    // ramps, max), so a tiny change of the merge gives a tiny change of the colour.
    const int r1=block,r2=2*block,r3=3*block;
    // P76: one scratch plane for every box (each allocated and zeroed its own: ~80 ms of 750 at 12 MP) and the column pass in
    // bands of 64 columns handed out 4 at a time (the band of ~31 columns read half a cache line per row); every column's
    // additions are the same, in the same order: the same floats.
    std::vector<float> boxTmp;
    auto box=[&](const std::vector<float>& in,std::vector<float>& out,int r){
        std::vector<float>& tmp=boxTmp;tmp.resize(in.size());
        mergeRowBands(h,[&](int y0,int y1){for(int y=y0;y<y1;++y){const float* a=in.data()+size_t(y)*w;float* o=tmp.data()+size_t(y)*w;double acc=0;
            for(int x=-r;x<=r;++x)acc+=a[std::clamp(x,0,w-1)];
            for(int x=0;x<w;++x){o[x]=float(acc/(2*r+1));acc+=a[std::min(x+r+1,w-1)]-a[std::max(x-r,0)];}}});
        out.resize(in.size());
        // P30: the column sums run down the rows for a band of columns at once (the same per-column additions in the same
        // order; one column at a time read a new cache line for every sample).
        constexpr int kCols=64;
        mergeRowBands((w+kCols-1)/kCols,[&](int b0,int b1){
            const int x0=b0*kCols,x1=std::min(w,b1*kCols);
            std::vector<double> acc(size_t(x1-x0),0.0);
            for(int x=x0;x<x1;++x){double a=0;for(int y=-r;y<=r;++y)a+=tmp[size_t(std::clamp(y,0,h-1))*w+x];acc[size_t(x-x0)]=a;}
            for(int y=0;y<h;++y){
                const float* add=tmp.data()+size_t(std::min(y+r+1,h-1))*w;const float* sub=tmp.data()+size_t(std::max(y-r,0))*w;
                float* o=out.data()+size_t(y)*w;
                for(int x=x0;x<x1;++x){double& a=acc[size_t(x-x0)];o[x]=float(a/(2*r+1));a+=add[x]-sub[x];}
            }
        });
    };
    std::vector<float> L(size_t(w)*h),hp,sc(size_t(w)*h,0.f),z;
    rows([&](size_t i0,size_t i1){for(size_t i=i0;i<i1;++i)L[i]=0.25f*rgb[i*3]+0.5f*rgb[i*3+1]+0.25f*rgb[i*3+2];});
    box(L,hp,r1);
    rows([&](size_t i0,size_t i1){for(size_t i=i0;i<i1;++i)hp[i]=L[i]-hp[i];});
    mergeRowBands(h,[&](int y0,int y1){for(int y=y0;y<y1;++y)for(int x=0;x<w;++x){
        const size_t i=size_t(y)*w+x;const float a=hp[i],gate=0.04f*std::max(L[i],1e-4f);float c=0;
        if(x+1<w)c+=0.5f*mosaicSoftSignChange(a,hp[i+1],gate,kMosaicLumaTau);
        if(y+1<h)c+=0.5f*mosaicSoftSignChange(a,hp[i+w],gate,kMosaicLumaTau);
        sc[i]=c;}});
    std::vector<float>().swap(L);std::vector<float>().swap(hp);
    box(sc,z,r2);
    std::vector<float>().swap(sc);
    std::vector<float> ul,vl;box(u,ul,r3);box(ul,ul,r3);box(v,vl,r3);box(vl,vl,r3);
    // soft counts (mosaicSoftSignChange) are a little lower than the former hard ones on a grating: the ramp starts at 0.51 / (2b)
    // (was 0.6), the raw-site oscillation at 0.2375 (was 0.25), which kept the false colour of syn_b2 / syn_b4 and the real bursts
    const float z0=0.51f/(2*block),z1=1.02f/(2*block);
    const int bw=w/block,bh=h/block;
    const bool rawOsc=osc&&int(osc->size())==bw*bh;
    mergeRowBands(h,[&](int y0,int y1){for(int Y=y0;Y<y1;++Y){ // P76: by rows (no division per pixel), the same values
        const float* oscRow=rawOsc?osc->data()+size_t(std::min(Y/block,bh-1))*bw:nullptr;
        for(int X=0;X<w;++X){
            const size_t i=size_t(Y)*w+X;
            const float t=std::clamp((z[i]-z0)/(z1-z0),0.f,1.f);float k=t*t*(3.f-2.f*t);
            if(oscRow){ // detail the merge already smoothed in the luma but the sites still show
                const float o=oscRow[std::min(X/block,bw-1)];
                const float t2=std::clamp((o-0.2375f)/0.2375f,0.f,1.f);k=std::max(k,t2*t2*(3.f-2.f*t2));
            }
            u[i]+=k*(ul[i]-u[i]);v[i]+=k*(vl[i]-v[i]);
        }
    }});
    std::vector<float>().swap(z);std::vector<float>().swap(ul);std::vector<float>().swap(vl);std::vector<float>().swap(boxTmp);
    rows([&](size_t i0,size_t i1){for(size_t i=i0;i<i1;++i){const float g=std::max(rgb[i*3+1],0.f)+e;rgb[i*3]=u[i]*g-e;rgb[i*3+2]=v[i]*g-e;}});
}
} // namespace scam
