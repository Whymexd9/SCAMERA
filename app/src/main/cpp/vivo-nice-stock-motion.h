#pragma once
#include "vivo-nice-capture.h"
#include "vivo-nice-guide.h"
#include <dlfcn.h>
#include <cstddef>

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
    Detect detect=nullptr;
    Track track=nullptr;
    template<class T> static void put(std::array<uint8_t,0x140>& data,size_t at,T value) {
        std::memcpy(data.data()+at,&value,sizeof(value));
    }
    static Image image(std::vector<uint8_t>& pixels,int w,int h) {
        Image out;out.width=w;out.height=h;out.data[0]=pixels.data();out.stride[0]=w;return out;
    }
    static std::vector<uint8_t> guide(const Burst& b,int frame) {
        // Camera2 adaptation: canonical CFA and calibrated sensor black/white
        // are expressed in the original guide's RAW14 black=1024 convention.
        std::vector<uint16_t> raw(size_t(b.w)*b.h);
        for(int y=0;y<b.h;++y)for(int x=0;x<b.w;++x)
            raw[size_t(y)*b.w+x]=uint16_t(std::floor(1024.f+b.sample(frame,x,y)*15359.f+.5f));
        return stockMotionGuide4(raw.data(),b.w,b.h,b.w,14,1.f/b.exposure[frame],.6f);
    }
public:
    StockMotion() {
        library=dlopen("/vendor/lib64/libvivo_nice_cre.so",RTLD_NOW|RTLD_LOCAL);
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
    ~StockMotion(){if(library)dlclose(library);}
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
        auto refPixels=guide(burst,0);auto ref=image(refPixels,w,h);
        Features features;
        std::vector<Point> original(features.maxCorners),src(features.maxCorners),dst(features.maxCorners);
        int count=0;
        const int detection=detect(&features,&ref,nullptr,original.data(),&count,0);
        if(detection || count<0 || count>features.maxCorners)
            throw std::runtime_error("Original NICE corner detector failed");
        std::array<uint8_t,0x140> params{};
        put(params,0x30,.01f);put(params,0x34,int32_t(20));put(params,0x38,.7f);
        put(params,0x3c,int32_t(100));put(params,0x40,.995f);put(params,0x44,int32_t(3));
        std::array<BackwardHomography,7> result{};
        std::array<bool,7> failed{};
        for(int frame=1;frame<7;++frame) {
            if(burst.raw[frame]==burst.raw[0] && burst.exposure[frame]==1)continue;
            auto donorPixels=guide(burst,frame);auto donor=image(donorPixels,w,h);
            std::copy_n(original.begin(),count,src.begin());
            int accepted=count;std::array<float,9> homography;
            homography.fill(std::numeric_limits<float>::quiet_NaN());
            int status=count>=50?track(&ref,&donor,src.data(),dst.data(),count,&accepted,params.data(),homography.data(),0):-1;
            failed[frame]=status!=0 || accepted>count || accepted<50 || accepted<float(count)*.7f;
            if(!failed[frame]) {
                try {
                    result[frame]=inverseToRaw(homography);
                    validateFrameMap(result[frame],burst.w,burst.h);
                } catch(const std::exception&) {failed[frame]=true;}
            }
            report("NICE STOCK MOTION frame="+std::to_string(frame)+" corners="+std::to_string(count)+
                   " accepted="+std::to_string(accepted)+" replaceReference="+std::to_string(failed[frame]));
        }
        replaceFailed(burst,failed,result);
        report("NICE MOTION: original detector/LK/RANSAC; calibrated RAW guide; projective donor sampling");
        return result;
    }
};
} // namespace vivo_nice
