#pragma once
#include "vivo-nice-capture.h"
#include "vivo-nice-guide.h"
#include <dlfcn.h>
#include <unistd.h>
#include <cstddef>
#include <cstdio>
#include <cstring>
#include <future>

namespace vivo_nice {
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
    static std::vector<uint8_t> guide(const Burst& b,int frame,float ceiling=1.f) {
        // Camera2 adaptation: canonical CFA and calibrated sensor black/white
        // are expressed in the original guide's RAW14 black=1024 convention.
        std::vector<uint16_t> raw(size_t(b.w)*b.h);
        for(int y=0;y<b.h;++y)for(int x=0;x<b.w;++x)
            raw[size_t(y)*b.w+x]=uint16_t(std::floor(1024.f+std::min(b.sample(frame,x,y),ceiling)*15359.f+.5f));
        return stockMotionGuide4(raw.data(),b.w,b.h,b.w,14,1.f/b.exposure[frame],.6f);
    }
    // P30: the clipped reference of a brighter frame depends only on its exposure (the Shasta frames share one): kept per burst.
    std::vector<uint8_t> clippedRefCache;std::vector<Point> clippedCornersCache;int clippedCountCache=0;
    float clippedExposureCache=0;const uint16_t* clippedRawCache=nullptr;
    std::vector<void*> compat;
    // Same pinned CRE file shipped in the APK, for devices without it in /vendor.
    // Its vivo-only dependencies are replaced by vivo-cre-compat stubs, preloaded
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
    ~StockMotion(){if(library)dlclose(library);for(auto it=compat.rbegin();it!=compat.rend();++it)dlclose(*it);}
    static BackwardHomography inverseToRaw(const std::array<float,9>& input) {
        for(float v:input)if(!std::isfinite(v))throw std::runtime_error("Unwritten NICE alignment matrix");
        const double a=input[0],b=input[1],c=input[2],d=input[3],e=input[4],f=input[5],g=input[6],h=input[7],i=input[8];
        const std::array<double,9> inverse{e*i-f*h,c*h-b*i,b*f-c*e,
            f*g-d*i,a*i-c*g,c*d-a*f,d*h-e*g,b*g-a*h,a*e-b*d};
        const double det=a*inverse[0]+b*inverse[3]+c*inverse[6];
        if(!std::isfinite(det)||std::abs(det)<1e-12||std::abs(inverse[8])<1e-12)
            throw std::runtime_error("Singular NICE alignment matrix");
        BackwardHomography result;
        for(int k=0;k<8;++k)result.h[k]=float(inverse[k]/inverse[8]);
        // H returned by the wrapper maps donor guide -> reference guide.
        // Samplers need reference RAW -> donor RAW, at 4x guide coordinates.
        result.h[2]*=4;result.h[5]*=4;result.h[6]/=4;result.h[7]/=4;
        result.validate();return result;
    }
    static void replaceFailed(Burst& burst,const std::array<bool,7>& failed,
            std::array<BackwardHomography,7>& result) {
        // Stock default method 0 replaces donors with Nref. It also resets
        // failed-L normalization; replacing its measured EV makes domains
        // recomputation select normalEV as refNEV before VST/model execution.
        if(failed[0])throw std::runtime_error("NICE reference cannot replace itself");
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
        if(width<=0 || height<=0)throw std::invalid_argument("Invalid NICE frame extent");
        // The denominator is affine over this rectangle and is 1 at (0,0).
        // Positive corner values exclude a horizon crossing anywhere inside.
        // This is an adapter safety check, not the stock ROI acceptance policy.
        for(int y:{0,height-1})for(int x:{0,width-1}) {
            const double denominator=double(map.h[6])*x+double(map.h[7])*y+1;
            if(!std::isfinite(denominator) || denominator<=0)
                throw std::invalid_argument("NICE homography horizon crosses frame");
            BackwardHomography::checkCoordinate(map.project(x,y));
        }
    }
    std::array<BackwardHomography,7> align(Burst& burst,const std::function<void(const std::string&)>& report) {
        const int w=burst.w/4,h=burst.h/4;
        if(w<16||h<16)throw std::runtime_error("NICE guide too small for original LK");
        // Donor guides are independent: build them in parallel with the reference.
        std::array<std::future<std::vector<uint8_t>>,7> donorGuides;
        for(int frame=1;frame<7;++frame)
            if(!(burst.raw[frame]==burst.raw[0] && burst.exposure[frame]==1))
                donorGuides[frame]=std::async(std::launch::async,[&burst,frame]{return guide(burst,frame);});
        Features features;
        if(cachedRaw!=burst.raw[0] || cachedExposure!=burst.exposure[0] || cachedW!=burst.w || cachedH!=burst.h
                || cachedRef.empty()) {
            cachedRef=guide(burst,0);
            auto reference=image(cachedRef,w,h);
            cachedCorners.assign(features.maxCorners,Point{});
            cachedCount=0;
            const int detection=detect(&features,&reference,nullptr,cachedCorners.data(),&cachedCount,0);
            if(detection || cachedCount<0 || cachedCount>features.maxCorners) {
                cachedRef.clear();
                for(auto& g:donorGuides)if(g.valid())g.wait();
                throw std::runtime_error("Original NICE corner detector failed");
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
            if(brighter && !(clippedRawCache==burst.raw[0] && clippedExposureCache==burst.exposure[frame] && !clippedRefCache.empty())) {
                clippedRefCache=guide(burst,0,1.f/burst.exposure[frame]);
                auto clipped=image(clippedRefCache,w,h);Features local;
                clippedCornersCache.assign(local.maxCorners,Point{});clippedCountCache=0;
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
            int status=n>=50?track(&refImage,&donor,src.data(),dst.data(),n,&accepted,params.data(),homography.data(),0):-1;
            // RANSAC inliers: 50 and a third of the corners determine the homography
            // well. The former 70% rule rejected most L/ES frames (clipped windows,
            // noisy ES) and they were replaced by the reference: no shadows from L,
            // no highlights from ES.
            failed[frame]=status!=0 || accepted>n || accepted<50 || accepted<float(n)*.35f;
            if(!failed[frame]) {
                try {
                    result[frame]=inverseToRaw(homography);
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
            report("NICE STOCK MOTION frame="+std::to_string(frame)+" corners="+std::to_string(n)+
                   " accepted="+std::to_string(accepted)+" "+how+" replaceReference="+std::to_string(failed[frame]));
        }
        replaceFailed(burst,failed,result);
        report("NICE MOTION: original detector/LK/RANSAC; calibrated RAW guide; projective donor sampling");
        return result;
    }
};
} // namespace vivo_nice
