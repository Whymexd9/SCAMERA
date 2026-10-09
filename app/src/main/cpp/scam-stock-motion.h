#pragma once
#include "scam-capture.h"
#include "scam-guide.h"
#include "scam-crash.h"
#include <dlfcn.h>
#include <unistd.h>
#include <cstddef>
#include <cstdio>
#include <cstring>
#include <future>

namespace scam {
// Internal ABI of the SHA-256-pinned PD2454 CRE only. The root Java launcher
// verifies the whole file before exec; offsets are additionally checked here.
class StockMotion {
    struct Image {
        int32_t format=9,width=0,height=0,reserved=0;
        void* data[4]{};
        int32_t stride[4]{};
    };
    struct Point { float x,y; };
    struct Features {
        void* context=nullptr;
        int32_t maxCorners=1000,minCorners=0,maxCandidates=32768,minDistance=16,useHarris=0;
        float harrisK=.04f;
        int32_t scale=4;
        float quality=.01f;
    };
    static_assert(sizeof(Image)==64 && offsetof(Image,data)==16 && offsetof(Image,stride)==48);
    static_assert(sizeof(Features)==40 && offsetof(Features,quality)==36);
    using Detect=int(*)(Features*,Image*,void*,Point*,int*,int);
    using Track=int(*)(Image*,Image*,Point*,Point*,int,int*,void*,float*,int);
    void* library=nullptr;
    // Extra ZSL frames are aligned in groups of three against the same reference:
    // its guide and corners are computed once per burst, not once per group.
    std::vector<uint8_t> cachedRef;
    std::vector<Point> cachedCorners;
    const uint16_t* cachedRaw=nullptr;
    float cachedExposure=0;
    int cachedW=0,cachedH=0,cachedCount=0;
    Detect detect=nullptr;
    Track track=nullptr;
    template<class T> static void put(std::array<uint8_t,0x140>& data,size_t at,T value) {
        std::memcpy(data.data()+at,&value,sizeof(value));
    }
    static Image image(std::vector<uint8_t>& pixels,int w,int h) {
        Image out;out.width=w;out.height=h;out.data[0]=pixels.data();out.stride[0]=w;return out;
    }
    // ceiling: clip the frame's normalised signal (reference against a brighter L:
    // 1/ratio, so both guides saturate at the same scene level and the window
    // frames the corners sit on look alike in both).
    // scale (any RAW size, owner 2026-10-07): the guide's input is the scale x scale block mean of the samples (colour-mixed, as
    // the guide's own 4x4 mean), (w/scale) x (h/scale). 1 = the frame itself, exactly as before.
    static std::vector<uint8_t> guide(const Burst& b,int frame,float ceiling=1.f,int scale=1) {
        if(scale>1)return binnedGuide(b,frame,ceiling,scale);
        return tableGuide(guideSource(b,b.raw[frame],b.exposure[frame],ceiling));
    }
public:
    // Everything guide(b, frame, ceiling, 1) reads: the frame's samples, the burst geometry and calibration, its exposure, the ceiling.
    struct GuideSource {
        const uint16_t* raw=nullptr;int w=0,h=0,cfa=0;bool canonical=false;
        std::array<float,4> black{};float white=0,exposure=1,ceiling=1;
        bool operator==(const GuideSource& o) const {
            return raw==o.raw&&w==o.w&&h==o.h&&cfa==o.cfa&&canonical==o.canonical&&black==o.black&&white==o.white
                &&exposure==o.exposure&&ceiling==o.ceiling;
        }
    };
    static GuideSource guideSource(const Burst& b,const uint16_t* raw,float exposure,float ceiling) {
        GuideSource s;s.raw=raw;s.w=b.w;s.h=b.h;s.cfa=b.cfa;s.canonical=b.canonicalRggb;s.black=b.black;s.white=b.white;
        s.exposure=exposure;s.ceiling=ceiling;return s;
    }
    // P31 (W1.2): the guide of a frame up to 16 MP without a float divide per sample and without the 24 MB RAW14 copy it used to
    // build. Camera2 adaptation (unchanged): canonical CFA and calibrated sensor black / white are expressed in the original guide's
    // RAW14 black=1024 convention. The RAW14 code of a sample, and stockMotionGuide4's signal() of that code, depend only on the CFA
    // phase of the site and its 16-bit value: both are tabulated per frame from the very expressions of Burst::sampleRaw, the RAW14
    // conversion and stockMotionGuide4 (same operations in the same translation unit: same rounding and contraction), then one
    // pass sums each 4x4 block. Same mean at stride 8, same reflected border sites: bit-identical to the per-sample guide.
    // parallel: the 4x4 pass in row bands on the shared pool (the reference guide, which the first detect waits for).
    static std::vector<uint8_t> tableGuide(const GuideSource& s,bool parallel=false) {
        const int width=s.w,height=s.h;
        const float gain=1.f/s.exposure,gamma=.6f; // stockMotionGuide4(raw, w, h, w, 14, 1 / exposure, .6f)
        if(!s.raw || width<4 || height<4 || width>32760 || height>32760 || int64_t(width)*height>16000000 ||
           !std::isfinite(gain) || gain<=0)
            throw std::invalid_argument("Invalid SCAM motion guide input");
        if(!(s.white>0.f&&s.white<=65535.f))return sampleGuide(s); // outside the parsers' range: no table bound
        // Every sample at or above `cap` (>= white) is clamped to 1 by sampleRaw: the tables end there.
        const int cap=int(std::min(65535.f,std::ceil(s.white)));
        const size_t entries=size_t(cap)+1;
        const int dx=s.canonical?(s.cfa&1):0,dy=s.canonical?(s.cfa>>1):0;
        std::vector<uint16_t> table(4*entries);
        for(int p=0;p<4;++p) {
            const float b=s.black[p];
            uint16_t* t=table.data()+size_t(p)*entries;
            for(int v=0;v<=cap;++v) {
                const float sample=std::clamp((float(v)-b)/(s.white-b),0.0f,1.0f);                        // Burst::sampleRaw
                const uint16_t code=uint16_t(std::floor(1024.f+std::min(sample,s.ceiling)*15359.f+.5f)); // RAW14 guide input
                const int clamped=std::clamp(int(code>>4),64,1023);                                     // signal(), 14 bits
                t[v]=uint16_t(int(std::clamp(float(clamped-64)*gain,0.f,1023.f)));
            }
        }
        // signal(x, y) of stockMotionGuide4 on guide-input site (x, y): sampleRaw's shifted, border-reflected site
        auto signal=[&](int x,int y)->int{
            int X=x+dx,Y=y+dy;
            if(X>=width)X=reflectCfa(X,width);
            if(Y>=height)Y=reflectCfa(Y,height);
            const unsigned v=std::min<unsigned>(s.raw[size_t(Y)*width+X],unsigned(cap));
            return table[size_t(((Y&1)<<1)|(X&1))*entries+v];
        };
        int64_t sum=0;int count=0;
        for(int y=0;y<height;y+=8)for(int x=0;x<width;x+=8){sum+=signal(x,y);++count;}
        const int mean=std::max(int(sum/count),2);
        const int ratio=130944/mean;
        const int weight=ratio>65479?128:ratio>>3;
        std::array<uint8_t,1024> lut{};
        for(int i=0;i<1024;++i)lut[i]=uint8_t(std::pow(double(i)/1023.0,double(gamma))*255.0);
        const int gw=width/4,gh=height/4;
        std::vector<uint8_t> out(size_t(gw)*gh);
        // Guide columns whose four sites need no reflection: 4 x + 3 + dx < width.
        const int inner=width-4-dx>=0?std::min(gw,(width-4-dx)/4+1):0;
        auto band=[&](int y0,int y1){
        for(int y=y0;y<y1;++y) {
            const uint16_t* rows[4];const uint16_t* even[4];const uint16_t* odd[4];
            for(int j=0;j<4;++j){
                int Y=4*y+j+dy;if(Y>=height)Y=reflectCfa(Y,height);
                rows[j]=s.raw+size_t(Y)*width+dx;
                // sites x' = 4 gx + i + dx: i = 0, 2 have the parity of dx, i = 1, 3 the other one
                even[j]=table.data()+size_t(((Y&1)<<1)|(dx&1))*entries;
                odd[j]=table.data()+size_t(((Y&1)<<1)|((dx+1)&1))*entries;
            }
            uint8_t* o=out.data()+size_t(y)*gw;
            for(int x=0;x<inner;++x) {
                int total=0;
                for(int j=0;j<4;++j){
                    const uint16_t* r=rows[j]+4*x;const uint16_t* a=even[j];const uint16_t* c=odd[j];
                    total+=a[std::min<unsigned>(r[0],unsigned(cap))]+c[std::min<unsigned>(r[1],unsigned(cap))]
                          +a[std::min<unsigned>(r[2],unsigned(cap))]+c[std::min<unsigned>(r[3],unsigned(cap))];
                }
                o[x]=lut[std::clamp(((total*weight+1024)>>7)/16,0,1023)];
            }
            for(int x=inner;x<gw;++x) {
                int total=0;
                for(int j=0;j<4;++j)for(int i=0;i<4;++i)total+=signal(x*4+i,y*4+j);
                o[x]=lut[std::clamp(((total*weight+1024)>>7)/16,0,1023)];
            }
        }
        };
        if(parallel)ScamPool::get().rows(gh,std::max(4,gh/64),band); else band(0,gh);
        return out;
    }
    // The per-sample guide tableGuide replaces (the code before P31): the reference of the host check and the path for a white
    // level the tables cannot bound.
    static std::vector<uint8_t> sampleGuide(const GuideSource& s) {
        Burst b;b.w=s.w;b.h=s.h;b.cfa=s.cfa;b.canonicalRggb=s.canonical;b.black=s.black;b.white=s.white;
        b.raw[0]=s.raw;b.exposure[0]=s.exposure;
        const float ceiling=s.ceiling;const int frame=0;
        std::vector<uint16_t> raw(size_t(b.w)*b.h);
        for(int y=0;y<b.h;++y)for(int x=0;x<b.w;++x)
            raw[size_t(y)*b.w+x]=uint16_t(std::floor(1024.f+std::min(b.sample(frame,x,y),ceiling)*15359.f+.5f));
        return stockMotionGuide4(raw.data(),b.w,b.h,b.w,14,1.f/b.exposure[frame],.6f);
    }
private:
    static std::vector<uint8_t> binnedGuide(const Burst& b,int frame,float ceiling,int s) {
        const int gw=b.w/s,gh=b.h/s;
        std::vector<uint16_t> raw(size_t(gw)*gh);
        const float inv=1.f/float(s*s);
        for(int y=0;y<gh;++y)for(int x=0;x<gw;++x){
            float sum=0;
            for(int j=0;j<s;++j)for(int i=0;i<s;++i)sum+=std::min(b.sample(frame,x*s+i,y*s+j),ceiling);
            raw[size_t(y)*gw+x]=uint16_t(std::floor(1024.f+sum*inv*15359.f+.5f));
        }
        return stockMotionGuide4(raw.data(),gw,gh,gw,14,1.f/b.exposure[frame],.6f);
    }
public:
    // Scale of the guide input: stockMotionGuide4 takes at most 16 MP (a 1 MP guide, the size the CRE corner detector and LK were
    // built for) and 32760 px a side. A larger frame is reduced by the smallest power of two that fits; 1 up to 16 MP (unchanged).
    static int guideScale(int w,int h) {
        int s=1;
        while(s<(1<<12)&&(int64_t(w/s)*(h/s)>16000000||w/s>32760||h/s>32760))s*=2;
        return s;
    }
private:
    // P30: the clipped reference of a brighter frame depends only on its exposure (the Shasta frames share one): kept per burst.
    std::vector<uint8_t> clippedRefCache;std::vector<Point> clippedCornersCache;int clippedCountCache=0;
    float clippedExposureCache=0;const uint16_t* clippedRawCache=nullptr;
    // P31 (W1.2): guides of the whole burst built ahead on the shared pool (prefetch() before the first group); align() takes a
    // prefetched guide, keyed by everything it reads, instead of building it (the groups waited 105-167 ms each for theirs).
    struct Prefetched { GuideSource source; std::shared_future<std::vector<uint8_t>> guide; };
    std::vector<Prefetched> prefetched;
    bool takePrefetched(const GuideSource& s,std::shared_future<std::vector<uint8_t>>& out) {
        for(auto it=prefetched.begin();it!=prefetched.end();++it)if(it->source==s){out=it->guide;prefetched.erase(it);return true;}
        return false;
    }
    std::vector<void*> compat;
    // Same pinned CRE file shipped in the APK, for devices without it in /vendor.
    // Its scam-only dependencies are replaced by scam-cre-compat stubs, preloaded
    // so the CRE's DT_NEEDED entries resolve to them by soname.
    void* loadBundled(const std::string& dir) {
        for(const char* name:{"libc++_shared.so","libvivolog.so","libvivo_platform_common.so","libvivo.mempool.so"}) {
            void* handle=dlopen((dir+"/"+name).c_str(),RTLD_NOW|RTLD_GLOBAL);
            if(!handle)throw std::runtime_error(std::string("Bundled CRE dependency load failed: ")+dlerror());
            compat.push_back(handle);
        }
        void* handle=dlopen((dir+"/libvivo_nice_cre.so").c_str(),RTLD_NOW|RTLD_LOCAL);
        if(!handle)throw std::runtime_error(std::string("Bundled CRE load failed: ")+dlerror());
        return handle;
    }
    static bool sameFile(const std::string& a,const std::string& b) {
        FILE* fa=fopen(a.c_str(),"rb");FILE* fb=fopen(b.c_str(),"rb");
        bool same=fa&&fb;
        if(same){fseek(fa,0,SEEK_END);fseek(fb,0,SEEK_END);same=ftell(fa)==ftell(fb);rewind(fa);rewind(fb);}
        std::vector<char> ba(1<<16),bb(1<<16);
        while(same){
            size_t na=fread(ba.data(),1,ba.size(),fa),nb=fread(bb.data(),1,bb.size(),fb);
            same=na==nb&&std::memcmp(ba.data(),bb.data(),na)==0;
            if(na==0)break;
        }
        if(fa)fclose(fa);if(fb)fclose(fb);
        return same;
    }
public:
    std::string source;
    // P30: the CRE initialises itself on its first detect / track (~0.4 s in the first group of a shot on the OPPO): one
    // detect and one track on a synthetic texture while the app still writes the burst. Separate buffers; the burst's
    // results do not depend on it (checked: replays bit-identical with SCAM_WARMUP).
    void warmUp() {
        const int w=256,h=192;
        std::vector<uint8_t> a(size_t(w)*h),b(size_t(w)*h);
        uint32_t seed=12345;
        for(int y=0;y<h;++y)for(int x=0;x<w;++x){
            seed=seed*1664525u+1013904223u;
            const int v=((x/8+y/8)&1)*120+int(seed>>26);
            a[size_t(y)*w+x]=uint8_t(v);
        }
        for(int y=0;y<h;++y)for(int x=0;x<w;++x)b[size_t(y)*w+x]=a[size_t(y)*w+std::min(w-1,x+1)];
        auto ia=image(a,w,h),ib=image(b,w,h);
        Features features;std::vector<Point> corners(features.maxCorners),moved(features.maxCorners);int count=0;
        worker_crash::Stage stage("CRE warm-up detect / track");
        if(detect(&features,&ia,nullptr,corners.data(),&count,0)||count<0||count>features.maxCorners)return;
        if(count<8)return;
        std::array<uint8_t,0x140> params{};
        put(params,0x30,.01f);put(params,0x34,int32_t(20));put(params,0x38,.7f);
        put(params,0x3c,int32_t(100));put(params,0x40,.995f);put(params,0x44,int32_t(3));
        int accepted=count;std::array<float,9> homography;homography.fill(0.f);
        track(&ia,&ib,corners.data(),moved.data(),count,&accepted,params.data(),homography.data(),0);
    }
    // P31 (W1.2): start every guide the groups of one burst will ask align() for: the reference (unless cached), each donor
    // (raw, exposure) in order and, after the first donor of each brighter exposure, the reference clipped for it. `base` is the
    // burst of the groups with its frames 1..6 still unset (geometry, calibration, slot 0). Only frames up to 16 MP (guide scale 1).
    // detect and track stay in align(), on the calling thread, in their order.
    void prefetch(const Burst& base,const std::vector<std::pair<const uint16_t*,float>>& donors) {
        dropPrefetched();
        if(guideScale(base.w,base.h)!=1)return;
        auto add=[&](const uint16_t* raw,float exposure,float ceiling){
            const GuideSource s=guideSource(base,raw,exposure,ceiling);
            for(const auto& p:prefetched)if(p.source==s)return;
            prefetched.push_back({s,ScamPool::get().submit([s]{return tableGuide(s);}).share()});
        };
        // the reference first and at once, its rows on the whole pool: the first detect waits for it (unless an earlier group of
        // this burst left it cached); an exception reaches align() where the guide is taken, as before
        if(!(cachedRaw==base.raw[0] && cachedExposure==base.exposure[0] && cachedW==base.w && cachedH==base.h && !cachedRef.empty())) {
            const GuideSource s=guideSource(base,base.raw[0],base.exposure[0],1.f);
            std::promise<std::vector<uint8_t>> ready;
            try{ready.set_value(tableGuide(s,true));}catch(...){ready.set_exception(std::current_exception());}
            prefetched.push_back({s,ready.get_future().share()});
        }
        for(const auto& d:donors) {
            if(d.first==base.raw[0] && d.second==1)continue;
            add(d.first,d.second,1.f);
            if(d.second>1.5f && !(clippedRawCache==base.raw[0] && clippedExposureCache==d.second && !clippedRefCache.empty()))
                add(base.raw[0],base.exposure[0],1.f/d.second);
        }
    }
    // Waits for and frees the guides prefetch() started that align() did not take (end of the alignment, or an exception).
    void dropPrefetched() {for(auto& p:prefetched)if(p.guide.valid())p.guide.wait();prefetched.clear();}
    // bundleDir: the job directory holding the APK copies; forceBundled skips /vendor
    // (used to validate the bundled path on vivo itself).
    explicit StockMotion(const std::string& bundleDir="",bool forceBundled=false) {
        const std::string vendorCre="/vendor/lib64/libvivo_nice_cre.so",bundledCre=bundleDir+"/libvivo_nice_cre.so";
        bool vendor=!forceBundled && access(vendorCre.c_str(),F_OK)==0;
        // Another vivo build of the CRE (X100 Ultra, owner's log 2026-10-05) does not load here, and the vendor libraries
        // preloaded for it then bound the bundled copy to a libc++_shared without __emutls_get_address: /vendor is taken
        // only when it is the pinned file itself.
        bool otherBuild=false;
        if(vendor && !bundleDir.empty() && access(bundledCre.c_str(),R_OK)==0 && !sameFile(vendorCre,bundledCre)) {vendor=false;otherBuild=true;}
        if(vendor) {
            // The job directory precedes /vendor on LD_LIBRARY_PATH (bundled QNN) and now
            // also holds the compat stubs: pin the real vivo dependencies first so the
            // vendor CRE binds to them by soname, not to the stubs.
            for(const char* name:{"libc++_shared.so","libvivolog.so","libvivo_platform_common.so","libvivo.mempool.so"})
                if(void* handle=dlopen((std::string("/vendor/lib64/")+name).c_str(),RTLD_NOW|RTLD_GLOBAL))compat.push_back(handle);
            library=dlopen("/vendor/lib64/libvivo_nice_cre.so",RTLD_NOW|RTLD_LOCAL);
        }
        if(library)source="vendor deps="+std::to_string(compat.size())+"/4 vendor";
        else if(!bundleDir.empty()) {library=loadBundled(bundleDir);source=otherBuild?"bundled (vendor CRE is another build)":"bundled";}
        if(!library)throw std::runtime_error(std::string("Original CRE motion load failed: ")+dlerror());
        try {
            Dl_info info{};
            void* exported=dlsym(library,"vivoNiceCREGetVersion");
            if(!exported || !dladdr(exported,&info) || !info.dli_fbase)
                throw std::runtime_error("Cannot locate pinned CRE code");
            auto base=static_cast<const uint8_t*>(info.dli_fbase);
            if(reinterpret_cast<uintptr_t>(exported)-reinterpret_cast<uintptr_t>(base)!=0x26a0e4)
                throw std::runtime_error("Unsupported CRE code layout");
            const uint32_t detectorStart[]={0xfc190fe8,0xa9017bfd};
            const uint32_t trackerStart[]={0xa9ba7bfd,0xa9016ffc};
            if(std::memcmp(base+0x2914d0,detectorStart,sizeof(detectorStart)) ||
               std::memcmp(base+0x29a548,trackerStart,sizeof(trackerStart)))
                throw std::runtime_error("Unsupported CRE motion entry points");
            detect=reinterpret_cast<Detect>(const_cast<uint8_t*>(base+0x2914d0));
            track=reinterpret_cast<Track>(const_cast<uint8_t*>(base+0x29a548));
        } catch(...) {dlclose(library);library=nullptr;throw;}
    }
    StockMotion(const StockMotion&)=delete;
    ~StockMotion(){dropPrefetched();if(library)dlclose(library);for(auto it=compat.rbegin();it!=compat.rend();++it)dlclose(*it);}
    // scale: RAW px per guide px (4, or 4 x the guideScale of a frame above 16 MP).
    static BackwardHomography inverseToRaw(const std::array<float,9>& input,int scale=4) {
        for(float v:input)if(!std::isfinite(v))throw std::runtime_error("Unwritten SCAM alignment matrix");
        const double a=input[0],b=input[1],c=input[2],d=input[3],e=input[4],f=input[5],g=input[6],h=input[7],i=input[8];
        const std::array<double,9> inverse{e*i-f*h,c*h-b*i,b*f-c*e,
            f*g-d*i,a*i-c*g,c*d-a*f,d*h-e*g,b*g-a*h,a*e-b*d};
        const double det=a*inverse[0]+b*inverse[3]+c*inverse[6];
        if(!std::isfinite(det)||std::abs(det)<1e-12||std::abs(inverse[8])<1e-12)
            throw std::runtime_error("Singular SCAM alignment matrix");
        BackwardHomography result;
        for(int k=0;k<8;++k)result.h[k]=float(inverse[k]/inverse[8]);
        // H returned by the wrapper maps donor guide -> reference guide.
        // Samplers need reference RAW -> donor RAW, at 4x guide coordinates.
        const float k=float(scale);
        result.h[2]*=k;result.h[5]*=k;result.h[6]/=k;result.h[7]/=k;
        result.validate();return result;
    }
    static void replaceFailed(Burst& burst,const std::array<bool,7>& failed,
            std::array<BackwardHomography,7>& result) {
        // Stock default method 0 replaces donors with Nref. It also resets
        // failed-L normalization; replacing its measured EV makes domains
        // recomputation select normalEV as refNEV before VST/model execution.
        if(failed[0])throw std::runtime_error("SCAM reference cannot replace itself");
        if(failed[4] && burst.cameraNoise && !burst.hasNormalNoise)
            throw std::runtime_error("NCH v5 required for failed-L reference noise");
        for(int frame=1;frame<7;++frame)if(failed[frame]) {
            burst.raw[frame]=burst.raw[0];burst.exposure[frame]=1;
            burst.iso[frame]=burst.iso[0];result[frame]={};
        }
        if(failed[4] && burst.cameraNoise) {
            burst.noise=burst.normalNoise;burst.noiseReferenceSlot=0;
        }
    }
    static void validateFrameMap(const BackwardHomography& map,int width,int height) {
        map.validate();
        if(width<=0 || height<=0)throw std::invalid_argument("Invalid SCAM frame extent");
        // The denominator is affine over this rectangle and is 1 at (0,0).
        // Positive corner values exclude a horizon crossing anywhere inside.
        // This is an adapter safety check, not the stock ROI acceptance policy.
        for(int y:{0,height-1})for(int x:{0,width-1}) {
            const double denominator=double(map.h[6])*x+double(map.h[7])*y+1;
            if(!std::isfinite(denominator) || denominator<=0)
                throw std::invalid_argument("SCAM homography horizon crosses frame");
            BackwardHomography::checkCoordinate(map.project(x,y));
        }
    }
    std::array<BackwardHomography,7> align(Burst& burst,const std::function<void(const std::string&)>& report) {
        // s: guideScale of the frame (1 up to 16 MP); the guide is (w / 4s) x (h / 4s), the homography scaled by 4s. The cached
        // reference is keyed on w and h, which fix s.
        const int s=guideScale(burst.w,burst.h);
        const int w=burst.w/(4*s),h=burst.h/(4*s);
        if(w<16||h<16)throw std::runtime_error("SCAM guide too small for original LK");
        if(s>1)report("SCAM STOCK MOTION: guide from x"+std::to_string(s)+" binned RAW ("+std::to_string(burst.w)+"x"+std::to_string(burst.h)
            +" -> guide "+std::to_string(w)+"x"+std::to_string(h)+")");
        // P31: a guide prefetch() started, or built here as before
        auto build=[&](int frame,float ceiling)->std::vector<uint8_t>{
            std::shared_future<std::vector<uint8_t>> ahead;
            if(s==1 && takePrefetched(guideSource(burst,burst.raw[frame],burst.exposure[frame],ceiling),ahead))return ahead.get();
            return guide(burst,frame,ceiling,s);
        };
        // Donor guides are independent: build them in parallel with the reference.
        std::array<std::shared_future<std::vector<uint8_t>>,7> donorGuides;
        for(int frame=1;frame<7;++frame)
            if(!(burst.raw[frame]==burst.raw[0] && burst.exposure[frame]==1)
                    && !(s==1 && takePrefetched(guideSource(burst,burst.raw[frame],burst.exposure[frame],1.f),donorGuides[frame])))
                donorGuides[frame]=std::async(std::launch::async,[&burst,frame,s]{return guide(burst,frame,1.f,s);}).share();
        Features features;
        if(cachedRaw!=burst.raw[0] || cachedExposure!=burst.exposure[0] || cachedW!=burst.w || cachedH!=burst.h
                || cachedRef.empty()) {
            cachedRef=build(0,1.f);
            auto reference=image(cachedRef,w,h);
            cachedCorners.assign(features.maxCorners,Point{});
            cachedCount=0;
            worker_crash::Stage stage("CRE detect (reference)");
            const int detection=detect(&features,&reference,nullptr,cachedCorners.data(),&cachedCount,0);
            if(detection || cachedCount<0 || cachedCount>features.maxCorners) {
                cachedRef.clear();
                for(auto& g:donorGuides)if(g.valid())g.wait();
                throw std::runtime_error("Original SCAM corner detector failed");
            }
            cachedRaw=burst.raw[0];cachedExposure=burst.exposure[0];cachedW=burst.w;cachedH=burst.h;
        }
        auto ref=image(cachedRef,w,h);
        const std::vector<Point>& original=cachedCorners;
        const int count=cachedCount;
        std::vector<Point> src(features.maxCorners),dst(features.maxCorners);
        std::array<uint8_t,0x140> params{};
        put(params,0x30,.01f);put(params,0x34,int32_t(20));put(params,0x38,.7f);
        put(params,0x3c,int32_t(100));put(params,0x40,.995f);put(params,0x44,int32_t(3));
        std::array<BackwardHomography,7> result{};
        std::array<bool,7> failed{};
        std::vector<Guide> refPyramid;
        for(int frame=1;frame<7;++frame) {
            if(burst.raw[frame]==burst.raw[0] && burst.exposure[frame]==1)continue;
            auto donorPixels=donorGuides[frame].get();auto donor=image(donorPixels,w,h);
            // A brighter L clips where the reference does not (windows): track it
            // against a reference clipped at the same scene level, with its own corners.
            const bool brighter=burst.exposure[frame]>1.5f;
            if(brighter && !(clippedRawCache==burst.raw[0] && clippedExposureCache==burst.exposure[frame] && !clippedRefCache.empty()
                    && clippedRefCache.size()==size_t(w)*h)) {
                clippedRefCache=build(0,1.f/burst.exposure[frame]);
                auto clipped=image(clippedRefCache,w,h);Features local;
                clippedCornersCache.assign(local.maxCorners,Point{});clippedCountCache=0;
                worker_crash::Stage stage("CRE detect (clipped reference)");
                if(detect(&local,&clipped,nullptr,clippedCornersCache.data(),&clippedCountCache,0)
                        || clippedCountCache<0 || clippedCountCache>local.maxCorners)clippedCountCache=0;
                clippedRawCache=burst.raw[0];clippedExposureCache=burst.exposure[frame];
            }
            std::vector<uint8_t>& clippedRef=clippedRefCache;std::vector<Point>& clippedCorners=clippedCornersCache;
            const int clippedCount=clippedCountCache;
            const int n=brighter?clippedCount:count;
            auto refImage=brighter?image(clippedRef,w,h):ref;
            std::copy_n((brighter?clippedCorners:original).begin(),n,src.begin());
            int accepted=n;std::array<float,9> homography;
            homography.fill(std::numeric_limits<float>::quiet_NaN());
            worker_crash::Stage trackStage("CRE track");
            int status=n>=50?track(&refImage,&donor,src.data(),dst.data(),n,&accepted,params.data(),homography.data(),0):-1;
            // RANSAC inliers: 50 and a third of the corners determine the homography
            // well. The former 70% rule rejected most L/ES frames (clipped windows,
            // noisy ES) and they were replaced by the reference: no shadows from L,
            // no highlights from ES.
            failed[frame]=status!=0 || accepted>n || accepted<50 || accepted<float(n)*.35f;
            if(!failed[frame]) {
                try {
                    result[frame]=inverseToRaw(homography,4*s);
                    validateFrameMap(result[frame],burst.w,burst.h);
                } catch(const std::exception&) {failed[frame]=true;}
            }
            std::string how="stock";
            if(failed[frame]) {
                // Fallback: global translation from the guide pyramids (few corners
                // in the dark, strong shake on the tele), kept only if it matches.
                if(refPyramid.empty())refPyramid=guides(burst,0);
                const auto donorPyramid=guides(burst,frame);
                const float ev=burst.exposure[frame];
                const Shift shift=globalShift(refPyramid,donorPyramid,ev);
                const Guide& r0=refPyramid[0];
                const float score=matchScore(r0,donorPyramid[0],int(shift.x/4),int(shift.y/4),ev,
                        r0.w/2,r0.h/2,std::max(r0.w,r0.h),std::max(1,r0.w/96));
                if(std::isfinite(score)&&score<.12f) {
                    BackwardHomography t;t.h={1,0,shift.x,0,1,shift.y,0,0};
                    try{validateFrameMap(t,burst.w,burst.h);result[frame]=t;failed[frame]=false;how="translation score="+std::to_string(score);}
                    catch(const std::exception&){}
                } else how="translation rejected score="+std::to_string(score);
            }
            report("SCAM STOCK MOTION frame="+std::to_string(frame)+" corners="+std::to_string(n)+
                   " accepted="+std::to_string(accepted)+" "+how+" replaceReference="+std::to_string(failed[frame]));
        }
        replaceFailed(burst,failed,result);
        report("SCAM MOTION: original detector/LK/RANSAC; calibrated RAW guide; projective donor sampling");
        return result;
    }
};
} // namespace scam
