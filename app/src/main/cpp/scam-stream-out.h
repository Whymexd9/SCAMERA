#pragma once
// P371 (speed): the merge worker's RGB output as half floats - the conversion (halvesOf) and the stream of the finished rows into
// the app's output memfd during the merge (StreamOut). Include after scam-hybrid.h (scam::floatToHalf, arm_neon.h on aarch64).
// tools/check_stream_out.cpp checks both on the host.
#include <algorithm>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

namespace scam {
// P80 / P153: n floats as IEEE half floats (round to nearest even); stats: their sum and maximum (from 0) on the way (P371).
inline void halvesOf(const float* src,uint16_t* dst,size_t n,bool stats,double& sum,float& maximum) {
    size_t i=0;
    double s=0;float m=0;
#if defined(__aarch64__)
    // P153: FCVT (round to nearest even, the default FPCR): the same halves as floatToHalf for every one of the 2^32 float bit
    // patterns (checked on the X200 Ultra), eight at a time
    float32x4_t vm=vdupq_n_f32(0.f);float64x2_t vs=vdupq_n_f64(0.0);
    for(;i+8<=n;i+=8){
        const float32x4_t x0=vld1q_f32(src+i),x1=vld1q_f32(src+i+4);
        const float16x4_t a=vcvt_f16_f32(x0),c=vcvt_f16_f32(x1);
        vst1q_u16(dst+i,vreinterpretq_u16_f16(vcombine_f16(a,c)));
        if(stats){
            vm=vmaxnmq_f32(vm,vmaxnmq_f32(x0,x1));
            vs=vaddq_f64(vs,vaddq_f64(vaddq_f64(vcvt_f64_f32(vget_low_f32(x0)),vcvt_high_f64_f32(x0)),
                                      vaddq_f64(vcvt_f64_f32(vget_low_f32(x1)),vcvt_high_f64_f32(x1))));
        }
    }
    if(stats){s=vgetq_lane_f64(vs,0)+vgetq_lane_f64(vs,1);m=vmaxnmvq_f32(vm);}
#endif
    if(stats)for(;i<n;++i){dst[i]=scam::floatToHalf(src[i]);s+=src[i];m=std::max(m,src[i]);}
    else for(;i<n;++i)dst[i]=scam::floatToHalf(src[i]);
    sum=s;maximum=m;
}

// P371 (speed, gpu-merge-stream-output): the RGB halves go into the app's output memfd while the GPU merges. The plain merge's
// chroma consumer finishes the result's rows strip by strip (scam::hybridFinalRowsHook) and converts them here, on its own thread
// (between two strip readbacks it waits ~0.3 s): after the merge only the trailers are left to write (the whole RGB's reservation
// and half fill were most of the output write: 44 ms at 12 MP on the X8U). The same bytes: rows are converted once final, and the
// memfd is used only for the result whose rows came (the commit event: the same buffer, every row, nothing after it); otherwise it
// is emptied again and the output written as before. Off: SCAM_NO_STREAM_OUT, a file output (a replay), no half output, the
// mapped write off, an RGB above 160 MB (the merge sends nothing above 16 MP either).
class StreamOut {
public:
    static constexpr size_t kMaxBytes=size_t(160)<<20;
    explicit StreamOut(int output):fd(output){}
    ~StreamOut(){drop();if(fd>=0)close(fd);}
    StreamOut(const StreamOut&)=delete;
    StreamOut& operator=(const StreamOut&)=delete;
    void event(const float* rows,int width,int height,int y0,int y1) noexcept {
        std::lock_guard<std::mutex> guard(lock);
        try{
            if(!rows){reset(width,height);return;}
            if(y0<0){committed=!failed&&rows==data&&done==h&&h>0&&width==w&&height==h;return;}
            committed=false;
            if(failed)return;
            if(!data)data=rows;
            if(rows!=data||width!=w||height!=h||y0!=done||y1<y0||y1>h){fail("rows out of order");return;}
            if(!map&&!open())return;
            double s=0;float m=0;
            const size_t row=size_t(w)*3;
            halvesOf(rows+size_t(y0)*row,map+size_t(y0)*row,size_t(y1-y0)*row,true,s,m);
            sum+=s;maximum=std::max(maximum,m);
            done=y1;
        }catch(...){fail("an exception");}
    }
    // true: the memfd holds result's halves (its first result.size() * 2 bytes, the file that long), sum / max of its floats
    bool take(const std::vector<float>& result,double& s,float& m){
        std::lock_guard<std::mutex> guard(lock);
        if(!committed||failed||!map||data!=result.data()||size_t(w)*h*3!=result.size()){
            if(why.empty())why=!committed?"no commit (the result is not the plain sink's)":"another result";
            dropLocked();return false;
        }
        munmap(map,bytes);map=nullptr;taken=true;
        s=sum;m=maximum;
        return true;
    }
    void drop(){std::lock_guard<std::mutex> guard(lock);dropLocked();}
    std::string reason() const {return why;}
    int rows() const {return done;}
private:
    void reset(int width,int height){
        const size_t want=width>0&&height>0?size_t(width)*size_t(height)*6:0;
        if(want!=bytes)dropLocked();
        w=width;h=height;bytes=want;data=nullptr;done=0;sum=0;maximum=0;committed=false;failed=false;why.clear();
        if(want==0||want>kMaxBytes)fail(want?"the RGB above 160 MB":"no size");
    }
    bool open(){
        struct stat st{};
        if(fd<0||fstat(fd,&st)!=0||!S_ISREG(st.st_mode)||st.st_size!=0||lseek(fd,0,SEEK_CUR)!=0){fail("the output is not an empty memfd");return false;}
        sized=true; // (empty until now: a failed reservation leaves it empty)
        if(fallocate(fd,0,0,off_t(bytes))!=0){fail("no reservation");dropLocked();return false;}
        void* p=mmap(nullptr,bytes,PROT_READ|PROT_WRITE,MAP_SHARED,fd,0);
        if(p==MAP_FAILED){fail("no mapping");dropLocked();return false;}
        map=static_cast<uint16_t*>(p);
        return true;
    }
    void fail(const char* what){failed=true;if(why.empty())why=what;}
    void dropLocked(){
        if(map){munmap(map,bytes);map=nullptr;}
        if(sized&&!taken&&ftruncate(fd,0)!=0){} // the output empty again, as it came (then written as before)
        sized=false;
    }
    const int fd;
    std::mutex lock;
    uint16_t* map=nullptr;size_t bytes=0;
    const float* data=nullptr;int w=0,h=0,done=0;
    double sum=0;float maximum=0;
    bool committed=false,failed=false,taken=false,sized=false; // sized: this stream reserved the memfd
    std::string why;
};
} // namespace scam
