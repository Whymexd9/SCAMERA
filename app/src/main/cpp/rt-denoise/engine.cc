#include "portable.h"
#include "engine.h"
#include <stdexcept>
namespace {
void loadCurve(rtengine::NoiseCurve& curve, const float* samples) {
    if (!samples) return;
    curve.lut(501);
    for(int i=0;i<501;i++) {
        if(!std::isfinite(samples[i]) || samples[i]<0 || samples[i]>1)
            throw std::invalid_argument("Curve must contain 501 finite values in [0,1]");
        curve.lut[i]=std::max(samples[i],0.01f);
    }
}
}
// Implemented from the global automatic caller in RawTherapee simpleprocess.cc.
void rtAutoChroma(rtengine::Imagefloat&,rtengine::ImProcFunctions&,rtengine::procparams::ProcParams&);
extern "C" int rt_denoise(float* rgba,int w,int h,const float* v,int count,
                          const float* lc,const float* cc,char* error,size_t errorSize) {
    try {
        if(!rgba || !v || count!=14 || w<32 || h<32 || w>16384 || h>16384
           || size_t(w)*h>64000000) throw std::invalid_argument("Unsupported image or parameter count");
        for(int i=0;i<count;i++) if(!std::isfinite(v[i])) throw std::invalid_argument("Non-finite parameter");
        if(v[0]<0||v[0]>100||v[1]<0||v[1]>100||v[2]<0||v[2]>100||std::abs(v[3])>100
           ||std::abs(v[4])>100||v[5]<1||v[5]>3||v[6]<0||v[6]>1||v[7]<0||v[7]>1
           ||v[8]<0||v[8]>5||v[9]<0||v[9]>5||v[10]<1||v[10]>10||v[11]<0||v[11]>1
           ||v[12]<0||v[12]>1||std::abs(v[13])>5) throw std::invalid_argument("Parameter out of range");
        for(int i=6;i<=12;i++) if(v[i]!=int(v[i])) throw std::invalid_argument("Non-integer selector");
        for(size_t i=0;i<size_t(w)*h;i++) for(int c=0;c<3;c++)
            if(!std::isfinite(rgba[4*i+c])) throw std::invalid_argument("Non-finite input pixel");
        if(v[0]==0 && v[1]==0 && v[8]==0 && v[11]==0 && !lc && !cc) return 1;
        // FFTW planner / RT globals are shared. Serialize whole invocation including auto analysis.
        static std::mutex invocationMutex;
        std::lock_guard<std::mutex> lock(invocationMutex);
        rtengine::initializeDenoiseColor();
        rtengine::procparams::ProcParams p;
        auto& d=p.dirpyrDenoise;
        d.luma=v[0]; d.chroma=v[1]; d.Ldetail=v[2]; d.redchro=v[3]; d.bluechro=v[4]; d.gamma=v[5];
        d.dmethod=v[6]==0?"Lab":"RGB"; d.smethod=v[7]==0?"shal":"shalbi";
        const char* channels[]={"none","Lonly","ab","Lab","Lpab","RGB"};
        const char* kernels[]={"soft","33","55soft","55","77","99"};
        d.median=v[8]!=0; d.methodmed=channels[int(v[8])]; d.medmethod=kernels[int(v[9])];
        d.rgbmethod=d.medmethod; d.passes=int(v[10]); d.autoGain=v[12]!=0;
        if(d.methodmed=="RGB" && v[9]>3) throw std::invalid_argument("RGB median supports 3x3 and 5x5 only");
        rtengine::Imagefloat image(w,h);
        for(int y=0;y<h;y++) for(int x=0;x<w;x++) {
            const size_t i=(size_t(y)*w+x)*4;
            image.r(y,x)=rgba[i]*65535.f; image.g(y,x)=rgba[i+1]*65535.f; image.b(y,x)=rgba[i+2]*65535.f;
        }
        rtengine::ImProcFunctions ipf(&p);
        if(v[11]) rtAutoChroma(image,ipf,p);
        rtengine::NoiseCurve lcurve,ccurve;
        loadCurve(lcurve,lc); loadCurve(ccurve,cc);
        rtengine::Imagefloat* half=nullptr;
        if((lc && lcurve.getSum()>=7.f)||(cc && ccurve.getSum()>5.f)) {
            half=new rtengine::Imagefloat((w+1)/2,(h+1)/2);
            for(int y=0;y<h;y+=2) for(int x=0;x<w;x+=2) {
                half->r(y/2,x/2)=image.r(y,x); half->g(y/2,x/2)=image.g(y,x); half->b(y/2,x/2)=image.b(y,x);
            }
        }
        if(lc) d.luma=0.5; // RT curve-mode dispatch in simpleprocess.cc.
        float residual=0,highResidual=0;
        // In-place is the upstream full-resolution calling convention. Caller RGBA is untouched.
        ipf.RGB_denoise(2,&image,&image,half,nullptr,nullptr,nullptr,true,d,v[13],lcurve,ccurve,residual,highResidual);
        for(int y=0;y<h;y++) for(int x=0;x<w;x++)
            if(!std::isfinite(image.r(y,x))||!std::isfinite(image.g(y,x))||!std::isfinite(image.b(y,x)))
                throw std::runtime_error("RawTherapee returned non-finite pixels");
        for(int y=0;y<h;y++) for(int x=0;x<w;x++) {
            const size_t i=(size_t(y)*w+x)*4;
            rgba[i]=image.r(y,x)/65535.f; rgba[i+1]=image.g(y,x)/65535.f; rgba[i+2]=image.b(y,x)/65535.f;
        }
        return 1;
    } catch(const std::exception& e) {
        if(error && errorSize) std::snprintf(error,errorSize,"%s",e.what());
        return 0;
    }
}
