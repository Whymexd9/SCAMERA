#pragma once
#include "vivo-hexquad-check.h"
#include <sys/mman.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <fstream>
#include <limits>
#include <chrono>
#include <memory>
#include "vivo-hexquad-detail.h"
#include "vivo-hexquad-gpu.h"

namespace vivo_hexquad {
// Versioned little-endian transport. Six DISTINCT, equal-exposure RAW16 frames.
// Output is Bayer16, optionally keeping every native x2 output position.
struct RawBurst {
    int w=0,h=0,iso=0,red=0; float black=0,white=0; bool response=false;
    float luma=1.f,chroma=1.f;
    int scale=2;
    bool fullResolution=false,useGpu=false;
    NoiseScale noise;
    float texture=0.f;
    Rgb neutral{{1.f,1.f,1.f}};
    std::array<const uint16_t*,Frames> raw{};
    std::array<float,64> gain;
    RawBurst() { gain.fill(1.f); }
    // Sampling, site gains, guides, registration and tiles use canonical RGGB
    // coordinates. red is retained for the physical sensor/output orientation.
    int color(int x,int y) const { return tagSource(0,x,y,0)>>14; }
    float sample(int f,int x,int y) const {
        const int k=(y&7)*8+(x&7);
        const CfaOrientation orientation(w,h,red);
        return std::max(0.f,std::min(1.f,(float(raw[f][size_t(orientation.y(y))*w+orientation.x(x)])-black)/(white-black)*gain[k]));
    }
};
struct MappedBurst {
    void* address=MAP_FAILED; size_t length=0; RawBurst burst;
    explicit MappedBurst(const std::string& path) {
        int fd=open(path.c_str(),O_RDONLY|O_CLOEXEC);
        if(fd<0)throw std::runtime_error("Cannot open HexQuad RAW burst");
        struct stat st{};
        if(fstat(fd,&st)||st.st_size<64||st.st_size>112+16000000LL*12){close(fd);throw std::runtime_error("Invalid burst file size");}
        length=size_t(st.st_size);address=mmap(nullptr,length,PROT_READ,MAP_PRIVATE,fd,0);close(fd);
        if(address==MAP_FAILED)throw std::runtime_error("Cannot map burst");
        try {
            uint32_t v[16];std::memcpy(v,address,64);
            require(v[0]==0x32515848&&(v[1]>=1&&v[1]<=4)&&v[2]>=288&&v[3]>=288&&v[2]%8==0&&v[3]%8==0&&
                uint64_t(v[2])*v[3]<=16000000&&v[4]>=50&&v[4]<=12800&&v[5]<=3&&v[6]==0&&v[7]==0,
                "Unsupported HexQuad RAW header / phase");
            burst.w=int(v[2]);burst.h=int(v[3]);burst.iso=int(v[4]);burst.red=int(v[5]);
            std::memcpy(&burst.black,&v[8],4);std::memcpy(&burst.white,&v[9],4);
            require(std::isfinite(burst.black)&&std::isfinite(burst.white)&&burst.black>=0&&
                    burst.white<=65535&&burst.white>burst.black+1&&v[10]<=1&&v[11]==6,
                    "Invalid HexQuad radiometry");
            burst.response=v[10]!=0;
            const size_t header=v[1]>=3?112:v[1]==2?80:64;
            if(v[1]>=2){
                require(length>=header,"Truncated HexQuad detail header");
                float controls[5];std::memcpy(controls,static_cast<const uint8_t*>(address)+48,sizeof(controls));
                require(std::isfinite(controls[0])&&std::isfinite(controls[1])&&controls[0]>=0&&controls[0]<=1&&controls[1]>=0&&controls[1]<=1,
                        "Invalid HexQuad luma/chroma controls");
                burst.luma=controls[0];burst.chroma=controls[1];
                for(int c=0;c<3;++c){require(std::isfinite(controls[c+2])&&controls[c+2]>=.0001f&&controls[c+2]<=10000.f,"Invalid HexQuad neutral point");burst.neutral[c]=controls[c+2];}
            }
            if(v[1]>=3){
                uint32_t scale;float controls[4];
                std::memcpy(&scale,static_cast<const uint8_t*>(address)+80,4);
                std::memcpy(controls,static_cast<const uint8_t*>(address)+84,sizeof(controls));
                require(scale==1||scale==2,"Invalid HexQuad model scale");burst.scale=int(scale);
                burst.noise={controls[0],controls[1],controls[2]};burst.noise.validate();
                burst.texture=controls[3];
                require(std::isfinite(burst.texture)&&burst.texture>=0&&burst.texture<=1,"Invalid texture strength");
                const auto* bytes=static_cast<const uint8_t*>(address);
                for(size_t i=68;i<80;++i)require(bytes[i]==0,"Invalid reserved header bytes");
                uint32_t full;std::memcpy(&full,bytes+100,4);
                require(full<=1&&(!full||burst.scale==2),"Full output requires x2 model");burst.fullResolution=full!=0;
                if(v[1]==4){uint32_t gpu;std::memcpy(&gpu,bytes+104,4);require(gpu<=1,"Invalid GPU mode");burst.useGpu=gpu!=0;}
                for(size_t i=v[1]==4?108:104;i<112;++i)require(bytes[i]==0,"Invalid reserved header bytes");
            }
            size_t pixels=size_t(burst.w)*burst.h;
            require(length==header+pixels*Frames*2,"Truncated HexQuad burst");
            const uint16_t* data=reinterpret_cast<const uint16_t*>(static_cast<const uint8_t*>(address)+header);
            for(size_t f=0;f<Frames;++f)burst.raw[f]=data+f*pixels;
        }catch(...){munmap(address,length);address=MAP_FAILED;throw;}
    }
    MappedBurst(const MappedBurst&)=delete;
    ~MappedBurst(){if(address!=MAP_FAILED)munmap(address,length);}
};
inline float median(std::vector<float> v) {
    size_t n=v.size()/2;std::nth_element(v.begin(),v.begin()+n,v.end());return v[n];
}
// Conservative repeating-site correction from reference frame, independently
// supported in all four image quadrants. Never changes mean colour gain.
inline void estimateResponse(RawBurst& b) {
    if(!b.response)return;
    for(int q=0;q<4;++q){
        std::array<std::array<std::vector<float>,16>,4> samples;
        for(int y=0;y+32<=b.h;y+=32)for(int x=0;x+32<=b.w;x+=32){
            float values[16]={},mean=0;
            for(int cy=0;cy<4;++cy)for(int cx=0;cx<4;++cx)
                for(int ky=0;ky<4;++ky)for(int kx=0;kx<4;++kx)
                    values[ky*4+kx]+=b.sample(0,x+cx*8+(q%2)*4+kx,y+cy*8+(q/2)*4+ky)/16.f;
            for(float v:values)mean+=v/16.f;
            if(mean<.02f||mean>.875f)continue;
            bool valid=true;for(float& v:values){v/=mean;valid=valid&&v>.75f&&v<1.25f;}
            if(!valid)continue;
            int zone=(y>=b.h/2?2:0)+(x>=b.w/2?1:0);
            for(int k=0;k<16;++k)samples[zone][k].push_back(values[k]);
        }
        bool valid=true;std::array<float,16> profile{};
        for(int k=0;k<16;++k){
            std::vector<float> all;
            for(int zone=0;zone<4;++zone){if(samples[zone][k].size()<8)valid=false;all.insert(all.end(),samples[zone][k].begin(),samples[zone][k].end());}
            if(all.size()<64||!valid){valid=false;break;}
            profile[k]=median(all);
            for(int zone=0;zone<4;++zone)if(std::abs(median(samples[zone][k])-profile[k])>.03f)valid=false;
        }
        float mean=0;for(float v:profile)mean+=v/16;
        if(valid)for(float v:profile)if(mean/v<.8f||mean/v>1.25f)valid=false;
        if(valid)for(int ky=0;ky<4;++ky)for(int kx=0;kx<4;++kx)
            b.gain[((q/2)*4+ky)*8+(q%2)*4+kx]=mean/profile[ky*4+kx];
        vivo_nn::log("HEX RESPONSE: quadrant="+std::to_string(q)+" applied="+std::to_string(valid));
    }
}
struct Guide {
    int w,h;std::vector<float> data;
    Guide(const RawBurst& b,int f,RowExecutor* team=nullptr):w(b.w/8),h(b.h/8),data(size_t(w)*h){
        independentRows(team,h,[&](int y){for(int x=0;x<w;++x){float sum=0;
            for(int ky=0;ky<8;++ky)for(int kx=0;kx<8;++kx)sum+=b.sample(f,x*8+kx,y*8+ky);
            data[y*w+x]=sum/64;
        }});
    }
    float at(float x,float y)const{
        x=std::max(0.f,std::min(float(w-1),x));y=std::max(0.f,std::min(float(h-1),y));
        int ix=int(x),iy=int(y),jx=std::min(ix+1,w-1),jy=std::min(iy+1,h-1);float fx=x-ix,fy=y-iy;
        return (data[iy*w+ix]*(1-fx)+data[iy*w+jx]*fx)*(1-fy)+(data[jy*w+ix]*(1-fx)+data[jy*w+jx]*fx)*fy;
    }
};
struct Shift{float x=0,y=0,error=0;};
inline float matchCost(const Guide& a,const Guide& b,int cx,int cy,int radius,float dx,float dy,int step=1){
    double sum=0;int count=0;
    for(int y=std::max(1,cy-radius);y<std::min(a.h-1,cy+radius+1);y+=step)
        for(int x=std::max(1,cx-radius);x<std::min(a.w-1,cx+radius+1);x+=step){
            if(x+dx<1||y+dy<1||x+dx>b.w-2||y+dy>b.h-2)continue;
            float diff=std::abs(a.at(x,y)-b.at(x+dx,y+dy));sum+=std::min(diff,.15f);++count;
        }
    return count>=16?float(sum/count):1.f;
}
struct Flow {
    int nx,ny;std::vector<Shift> field;
    Flow(const Guide& ref,const Guide& src,RowExecutor* team=nullptr):nx((ref.w+15)/16+1),ny((ref.h+15)/16+1),field(size_t(nx)*ny){
        Shift global;global.error=matchCost(ref,src,ref.w/2,ref.h/2,std::max(ref.w,ref.h),0,0,8);
        for(int dy=-12;dy<=12;++dy)for(int dx=-12;dx<=12;++dx){
            float cost=matchCost(ref,src,ref.w/2,ref.h/2,std::max(ref.w,ref.h),dx,dy,8);
            // Mild preference for smaller displacement in flat/repeated patterns.
            if(cost+.00001f*(dx*dx+dy*dy)<global.error+.00001f*(global.x*global.x+global.y*global.y))global={float(dx),float(dy),cost};
        }
        independentRows(team,ny,[&](int gy){for(int gx=0;gx<nx;++gx){
            int cx=std::min(gx*16,ref.w-1),cy=std::min(gy*16,ref.h-1);
            Shift best=global;best.error=matchCost(ref,src,cx,cy,12,best.x,best.y);
            for(int dy=-2;dy<=2;++dy)for(int dx=-2;dx<=2;++dx){
                float x=global.x+dx,y=global.y+dy,cost=matchCost(ref,src,cx,cy,12,x,y);
                if(cost<best.error)best={x,y,cost};
            }
            // Refine to one physical RAW pixel using colour-independent guide.
            for(float step:{.5f,.25f,.125f}){
                Shift center=best;
                for(int dy=-1;dy<=1;++dy)for(int dx=-1;dx<=1;++dx){
                    float x=center.x+dx*step,y=center.y+dy*step,cost=matchCost(ref,src,cx,cy,12,x,y);
                    if(cost<best.error)best={x,y,cost};
                }
            }
            field[gy*nx+gx]=best;
        }});
        vivo_nn::log("HEX ALIGN: global RAW shift="+std::to_string(global.x*8)+","+std::to_string(global.y*8)+" guide_error="+std::to_string(global.error));
    }
    Shift at(int x,int y)const{
        float fx=std::max(0.f,(x-3.5f)/128.f),fy=std::max(0.f,(y-3.5f)/128.f);
        int ix=std::min(int(fx),nx-2),iy=std::min(int(fy),ny-2);fx=std::min(1.f,fx-ix);fy=std::min(1.f,fy-iy);
        Shift s{};
        for(int j=0;j<2;++j)for(int i=0;i<2;++i){float w=(i?fx:1-fx)*(j?fy:1-fy);const auto& t=field[(iy+j)*nx+ix+i];s.x+=t.x*8*w;s.y+=t.y*8*w;s.error+=t.error*w;}
        return s;
    }
};
// Extend whole 8x8 CFA cells, retaining the phase inside each cell. Pixel-wise
// reflect101 changes the reference CFA support in the halo at image edges.
inline int reflect(int p,int size){
    require(size>=8&&size%8==0,"Invalid CFA padding size");
    int phase=((p%8)+8)%8,cell=(p-phase)/8,cells=size/8,period=2*cells;
    cell=((cell%period)+period)%period;
    return (cell<cells?cell:period-1-cell)*8+phase;
}
// Output contract is always normalized linear Bayer16, black=0, white=65535.
// Requantizing the fused result to sensor 10-bit destroys shadow precision.
inline uint16_t encodeLinearBayer16(float value) {
    require(std::isfinite(value),"Nonfinite linear Bayer sample");
    return uint16_t(std::lround(std::max(0.f,std::min(1.f,value))*65535.f));
}
inline double hexClockMs(){return std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now().time_since_epoch()).count();}
struct SignalStats {
    std::array<double,3> sum{{0,0,0}};
    std::array<size_t,3> count{{0,0,0}};
    size_t zero=0;float low=1,high=0;
    void add(float v,int c){sum[c]+=v;++count[c];zero+=v<=0;low=std::min(low,v);high=std::max(high,v);}
    std::string summary()const{std::ostringstream out;out<<std::setprecision(8)<<"meanRGB=";
        for(int c=0;c<3;++c)out<<(c?",":"")<<(count[c]?sum[c]/count[c]:0);
        out<<" min="<<low<<" max="<<high<<" zeros="<<zero;return out.str();}
};
constexpr int Halo=32, Core=224, Step=192;
inline float feather(float p){return std::min(1.f,std::min((p+.5f)/32.f,(Core-p-.5f)/32.f));}
inline std::vector<int> origins(int size){std::vector<int> r;for(int x=0;x<size;x+=Step){r.push_back(x);if(x+Core>=size)break;}return r;}
inline int bayerColor(int x,int y,int red){int q=(y&1)*2+(x&1);return q==red?0:q==(red^3)?2:1;}

struct OutputStats {
    size_t count=0,below=0,above=0,outsideChart=0;
    float min=std::numeric_limits<float>::infinity(),max=-std::numeric_limits<float>::infinity();
    int firstX=-1,firstY=-1,firstChannel=-1;float firstValue=0;
    void add(float v,int x,int y,int c){
        require(std::isfinite(v),"Nonfinite HexQuad output");
        ++count;min=std::min(min,v);max=std::max(max,v);below+=v<0;above+=v>1;
        if(v<-.05f||v>1.25f){
            ++outsideChart;if(firstX<0){firstX=x;firstY=y;firstChannel=c;firstValue=v;}
        }
    }
    void merge(const OutputStats& s){
        count+=s.count;below+=s.below;above+=s.above;outsideChart+=s.outsideChart;
        min=std::min(min,s.min);max=std::max(max,s.max);
    }
    std::string summary()const{
        std::ostringstream s;s<<std::setprecision(8)<<"count="<<count<<" min="<<min<<" max="<<max
            <<" ivst_clip_low="<<below<<" ivst_clip_high="<<above<<" outside_chart_range="<<outsideChart
            <<" clip_fraction="<<(count?double(below+above)/count:0);
        if(firstX>=0)s<<" first_xy="<<firstX<<','<<firstY<<" channel="<<firstChannel<<" value="<<firstValue;
        return s.str();
    }
};

// A Network template makes tile coverage, halo rejection, scaling, CFA and
// stale client-buffer bugs testable independently from proprietary HTP weights.
template<class Network> void captureHex(Network& net,RawBurst& b,const std::string& output,unsigned cpuThreads=0){
    const double started=hexClockMs();
    const CfaOrientation orientation(b.w,b.h,b.red);
    vivo_nn::log("HEX CFA: sensor red="+std::to_string(b.red)+
        " -> canonical RGGB; flip_x="+std::to_string(bool(b.red&1))+
        " flip_y="+std::to_string(bool(b.red&2))+"; output restored to sensor orientation/CFA");
    RowExecutor team(cpuThreads);
    vivo_nn::log("HEX CPU: row_workers="+std::to_string(team.size())+"; serial NPU and tile overlap order");
    estimateResponse(b);
    const double responseDone=hexClockMs();
    require(b.scale==1||b.scale==2,"Invalid capture model scale");b.noise.validate();
    require(!b.fullResolution||b.scale==2,"Full output requires x2 model");
    const int outputScale=b.fullResolution?2:1,ow=b.w*outputScale,oh=b.h*outputScale;
    const CfaOrientation outputOrientation(ow,oh,b.red);
    require(std::isfinite(b.texture)&&b.texture>=0&&b.texture<=1,"Invalid capture texture strength");
    const bool hybrid=b.luma<1.f||b.chroma<1.f||b.texture>0.f;
    std::unique_ptr<TetraDetailReference<RawBurst>> reference;
    double detailStart=hexClockMs();
    if(hybrid)reference.reset(new TetraDetailReference<RawBurst>(b,&team));
    double detailMs=hexClockMs()-detailStart;
    const double detailInitMs=detailMs;
    vivo_nn::log("HEX DENOISE: luma="+std::to_string(b.luma*100)+" chroma="+std::to_string(b.chroma*100)+
        " texture="+std::to_string(b.texture*100)+
        "; 100=neural 0=single-reference Tetra Detail; texture reduces only local luma weight; neutral-balanced camera RGB");
    const double guideStart=hexClockMs();
    std::vector<Guide> guides;guides.reserve(6);for(int f=0;f<6;++f)guides.emplace_back(b,f,&team);
    const double guidesDone=hexClockMs();
    std::vector<Flow> flows;flows.reserve(5);for(int f=1;f<6;++f)flows.emplace_back(guides[0],guides[f],&team);
    const double flowsDone=hexClockMs();
    SignalStats inputSignal;
    // Sample complete CFA cells so the sample stride cannot alias one colour.
    for(int y=0;y+8<=b.h;y+=64)for(int x=0;x+8<=b.w;x+=64)
        for(int dy=0;dy<8;++dy)for(int dx=0;dx<8;++dx)
            inputSignal.add(b.sample(0,x+dx,y+dy),b.color(x+dx,y+dy));
    vivo_nn::log("HEX SIGNAL INPUT: black-subtracted sensor normalized; "+inputSignal.summary());
    const double aligned=hexClockMs();double packingMs=0,npuMs=0,assemblyMs=0;
    NormalVst transfer(b.iso,b.noise),physicalNoise(b.iso);auto lut=transfer.forward();auto inverse=transfer.inverse();
    vivo_nn::log("HEX RADIOMETRY: ISO="+std::to_string(b.iso)+" black="+std::to_string(b.black)+
        " white="+std::to_string(b.white)+" vst_min="+std::to_string(lut[0]/65535.f)+
        " vst_max="+std::to_string(lut[Levels-1]/65535.f)+
        " noise_variance_factors="+std::to_string(b.noise.overall)+","+std::to_string(b.noise.photon)+","+std::to_string(b.noise.readout)+
        " shot="+std::to_string(transfer.shot)+" read_variance="+std::to_string(transfer.variance)+
        "; matched VST/IVST; measured ISO unchanged; fixed ISO50 normalization");
    std::unique_ptr<GpuPost<RawBurst>> gpu;
    bool gpuVerified=false;size_t gpuTiles=0;double gpuMs=0;
    if(b.useGpu){
        const double start=hexClockMs();
        try{gpu.reset(new GpuPost<RawBurst>(b,reference.get(),inverse));vivo_nn::log("HEX GPU: renderer="+gpu->renderer+"; first tile must match CPU");}
        catch(const std::exception& e){vivo_nn::log(std::string("HEX GPU FALLBACK: ")+e.what()+"; using CPU + NPU");}
        vivo_nn::log("HEX TIMING ms: gpu_init="+std::to_string(hexClockMs()-start));
    }
    std::vector<float> sum(size_t(ow)*oh,0),weight(sum.size(),0);
    OutputStats frameStats;size_t anomalousTiles=0;
    const auto xs=origins(b.w),ys=origins(b.h);size_t done=0,total=xs.size()*ys.size(),holes=0,samples=0;
    require(net.input.size()==288u*288u*18,"Unexpected HexQuad input allocation");
    for(int oy:ys)for(int ox:xs){
        double tick=hexClockMs();
        std::fill(net.input.begin(),net.input.end(),0.f);
        std::array<size_t,288> rowHoles{},rowSamples{};
        team.run(288,[&](int ty){for(int tx=0;tx<288;++tx){
            int x=reflect(ox-Halo+tx,b.w),y=reflect(oy-Halo+ty,b.h);
            for(int f=0;f<6;++f){
                Shift shift=f?flows[f-1].at(x,y):Shift{};
                int sx=x+int(std::lround(shift.x)),sy=y+int(std::lround(shift.y));
                bool valid=sx>=0&&sx<b.w&&sy>=0&&sy<b.h&&shift.error<.06f;
                if(valid&&f){
                    float a=guides[0].at((x-3.5f)/8,(y-3.5f)/8),v=guides[f].at((sx-3.5f)/8,(sy-3.5f)/8);
                    valid=std::abs(a-v)<.08f;
                }
                if(tx>=Halo&&tx<288-Halo&&ty>=Halo&&ty<288-Halo){++rowSamples[ty];if(!valid)++rowHoles[ty];}
                if(!valid)continue; // sparse hole, never duplicate the base frame
                int c=b.color(sx,sy);unsigned value=unsigned(std::lround(b.sample(f,sx,sy)*16383.f));
                net.input[(size_t(ty)*288+tx)*18+f*3+c]=lut[c*Levels+value]*(1.f/65535.f);
            }
        }});
        for(int ty=0;ty<288;++ty){holes+=rowHoles[ty];samples+=rowSamples[ty];}
        packingMs+=hexClockMs()-tick;tick=hexClockMs();
        net.execute();npuMs+=hexClockMs()-tick;tick=hexClockMs();
        typename TetraDetailReference<RawBurst>::Tile detailTile;

        const int scale=b.scale,side=288*scale;
        require(net.output.size()==size_t(side)*side*3,"Unexpected HexQuad output shape");
        // Nonfinite/unwritten output anywhere is fatal. Finite overshoot is
        // decoded with stock IVST index saturation, not a chart-only range gate.
        for(float v:net.output)require(std::isfinite(v),"Nonfinite HexQuad output");
        OutputStats tileStats;
        std::array<OutputStats,Core*2> rowStats;
        const int tileSide=Core*outputScale;
        std::vector<float> gpuValues,cpuCheck;
        if(gpu){
            const double start=hexClockMs();
            try{gpuValues=gpu->render(net.output,ox,oy);}
            catch(const std::exception& e){vivo_nn::log(std::string("HEX GPU FALLBACK: ")+e.what()+"; current and remaining tiles use CPU + NPU");gpu.reset();gpuVerified=false;}
            gpuMs+=hexClockMs()-start;
        }
        const bool checkGpu=gpu&&!gpuVerified;
        if(checkGpu)cpuCheck.resize(size_t(tileSide)*tileSide);
        if(gpu&&gpuVerified){
            team.run(std::min(tileSide,oh-oy*outputScale),[&](int ty){for(int tx=0;tx<tileSide&&ox*outputScale+tx<ow;++tx){
                // Diagnostic extrema/counters retain the original sample support.
                const int area=b.fullResolution?1:scale;
                const int nx=b.fullResolution?tx+64:(tx+Halo)*scale,ny=b.fullResolution?ty+64:(ty+Halo)*scale;
                for(int dy=0;dy<area;++dy)for(int dx=0;dx<area;++dx)for(int c=0;c<3;++c)
                    rowStats[ty].add(net.output[(size_t(ny+dy)*side+nx+dx)*3+c],nx+dx,ny+dy,c);
                const float value=gpuValues[size_t(ty)*tileSide+tx];
                const float w=feather((tx+.5f)/outputScale-.5f)*feather((ty+.5f)/outputScale-.5f);
                const size_t at=size_t(oy*outputScale+ty)*ow+ox*outputScale+tx;
                sum[at]+=value*w;weight[at]+=w;
            }});
            ++gpuTiles;
        }else{
        if(reference){double start=hexClockMs();detailTile=reference->tile(ox,oy,Core,b.fullResolution?5:4);detailMs+=hexClockMs()-start;}
        if(b.fullResolution){
            // Build the single-RAW reference once at input resolution. Only the
            // reference is bilinearly sampled; every neural output pixel is kept.
            constexpr int refSide=Core+2;
            std::vector<Rgb> refRgb;std::vector<float> confidence;
            if(reference){
                refRgb.resize(refSide*refSide);confidence.resize(refRgb.size());
                team.run(refSide,[&](int y){for(int x=0;x<refSide;++x){
                    int px=std::max(0,std::min(b.w-1,ox+x-1)),py=std::max(0,std::min(b.h-1,oy+y-1));
                    refRgb[y*refSide+x]=reference->rgb(detailTile,px,py);
                    confidence[y*refSide+x]=b.texture>0?reference->textureConfidence(px,py,physicalNoise.shot,physicalNoise.variance):0;
                }});
            }
            team.run(std::min(Core*2,oh-oy*2),[&](int ty){for(int tx=0;tx<Core*2&&ox*2+tx<ow;++tx){
                int x=ox*2+tx,y=oy*2+ty,c=bayerColor(x,y,0);
                size_t ni=(size_t(ty+Halo*2)*side+tx+Halo*2)*3;
                Rgb rgb{};for(int ch=0;ch<3;++ch){
                    rowStats[ty].add(net.output[ni+ch],tx+Halo*2,ty+Halo*2,ch);
                    rgb[ch]=inverse[ch][normalizedIvstIndex(net.output[ni+ch])];
                }
                float value=rgb[c];
                if(reference){
                    float sx=(tx+.5f)*.5f+.5f,sy=(ty+.5f)*.5f+.5f;
                    int ix=int(sx),iy=int(sy);float fx=sx-ix,fy=sy-iy;
                    Rgb ref{};float mask=0;
                    for(int j=0;j<2;++j)for(int i=0;i<2;++i){
                        float w=(i?fx:1-fx)*(j?fy:1-fy);size_t at=size_t(iy+j)*refSide+ix+i;
                        for(int ch=0;ch<3;++ch)ref[ch]+=refRgb[at][ch]*w;
                        mask+=confidence[at]*w;
                    }
                    value=mixDetail(ref,rgb,b.luma*(1-b.texture*mask),b.chroma,b.neutral)[c];
                }
                if(checkGpu)cpuCheck[size_t(ty)*tileSide+tx]=value;
                float w=feather((tx+.5f)*.5f-.5f)*feather((ty+.5f)*.5f-.5f);
                size_t at=size_t(y)*ow+x;sum[at]+=value*w;weight[at]+=w;
            }
            });
        } else team.run(std::min(Core,b.h-oy),[&](int ty){for(int tx=0;tx<Core&&ox+tx<b.w;++tx){
            int x=ox+tx,y=oy+ty,c=bayerColor(x,y,0);float value=0;Rgb rgb{};
            const float area=1.f/(scale*scale);
            for(int dy=0;dy<scale;++dy)for(int dx=0;dx<scale;++dx){
                size_t index=(size_t((ty+Halo)*scale+dy)*side+(tx+Halo)*scale+dx)*3;
                for(int ch=0;ch<3;++ch)rowStats[ty].add(net.output[index+ch],(tx+Halo)*scale+dx,(ty+Halo)*scale+dy,ch);
                value+=inverse[c][normalizedIvstIndex(net.output[index+c])]*area;
                if(hybrid)for(int ch=0;ch<3;++ch)rgb[ch]+=inverse[ch][normalizedIvstIndex(net.output[index+ch])]*area;
            }
            if(reference){
                const float confidence=b.texture>0?reference->textureConfidence(x,y,physicalNoise.shot,physicalNoise.variance):0.f;
                value=mixDetail(reference->rgb(detailTile,x,y),rgb,b.luma*(1.f-b.texture*confidence),b.chroma,b.neutral)[c];
            }
            if(checkGpu)cpuCheck[size_t(ty)*tileSide+tx]=value;
            float w=feather(tx)*feather(ty);size_t index=size_t(y)*b.w+x;
            sum[index]+=value*w;weight[index]+=w;
        }});
        }
        if(checkGpu){
            double squared=0;float worst=0;size_t count=0;
            for(int y=0;y<tileSide&&oy*outputScale+y<oh;++y)for(int x=0;x<tileSide&&ox*outputScale+x<ow;++x){
                const size_t i=size_t(y)*tileSide+x;float error=std::abs(cpuCheck[i]-gpuValues[i]);
                squared+=double(error)*error;worst=std::max(worst,error);++count;
            }
            const double rmse=std::sqrt(squared/std::max(size_t(1),count));
            gpuVerified=count&&worst<=.0002f&&rmse<=.00002;
            vivo_nn::log("HEX GPU CHECK: max="+std::to_string(worst)+" RMSE="+std::to_string(rmse)+" pass="+std::to_string(gpuVerified)+"; first tile kept from CPU");
            if(!gpuVerified){gpu.reset();vivo_nn::log("HEX GPU FALLBACK: precision check failed; using CPU + NPU");}
        }
        // Combine integer/extrema diagnostics in raster order; never reduce
        // floating-point pixel accumulators across threads.
        for(const auto& stats:rowStats){
            if(!stats.count)continue;
            if(tileStats.firstX<0&&stats.firstX>=0){tileStats.firstX=stats.firstX;tileStats.firstY=stats.firstY;tileStats.firstChannel=stats.firstChannel;tileStats.firstValue=stats.firstValue;}
            tileStats.merge(stats);
        }
        assemblyMs+=hexClockMs()-tick;
        frameStats.merge(tileStats);++done;
        if(tileStats.outsideChart)++anomalousTiles;
        if(done==1||done%8==0||done==total||(tileStats.outsideChart&&anomalousTiles<=4))
            vivo_nn::log("HEX OUTPUT: tile="+std::to_string(done)+"/"+std::to_string(total)+
                " origin="+std::to_string(ox)+","+std::to_string(oy)+" "+tileStats.summary());
    }
    vivo_nn::log("HEX OUTPUT TOTAL: retained RGB samples incl overlap; "+frameStats.summary()+
        " anomalous_tiles="+std::to_string(anomalousTiles));
    require(samples>0&&double(holes)/samples<.40,"Too much motion / unreliable HexQuad registration");
    vivo_nn::log("HEX ALIGN: missing sparse samples="+std::to_string(double(holes)/samples));
    const double writeStart=hexClockMs();
    std::ofstream file(output,std::ios::binary|std::ios::trunc);require(bool(file),"Cannot open HexQuad output");
    SignalStats outputSignal;
    std::vector<uint16_t> row(ow);
    for(int y=0;y<oh;++y){for(int x=0;x<ow;++x){
        size_t i=size_t(outputOrientation.y(y))*ow+outputOrientation.x(x);
        require(weight[i]>0&&std::isfinite(sum[i]),"Hole in HexQuad tile assembly");
        const float linear=sum[i]/weight[i];
        row[x]=encodeLinearBayer16(linear);
        outputSignal.add(row[x]/65535.f,bayerColor(x,y,b.red));
    }file.write(reinterpret_cast<const char*>(row.data()),ow*2);}
    file.close();require(bool(file),"HexQuad output write failed");
    vivo_nn::log("HEX SIGNAL OUTPUT: normalized Bayer16 black=0 white=65535; "+outputSignal.summary());
    vivo_nn::log("HEX CPU TIMING ms: response="+std::to_string(responseDone-started)+
        " detail_init="+std::to_string(detailInitMs)+" guides="+std::to_string(guidesDone-guideStart)+
        " registration="+std::to_string(flowsDone-guidesDone)+" output_write="+std::to_string(hexClockMs()-writeStart));
    vivo_nn::log("HEX TIMING ms: response_alignment="+std::to_string(aligned-started)+" packing="+std::to_string(packingMs)+
        " npu="+std::to_string(npuMs)+" assembly="+std::to_string(assemblyMs)+" detail_preparation="+std::to_string(detailMs)+" total="+std::to_string(hexClockMs()-started));
    vivo_nn::log("HEX COMPUTE: requested="+std::string(b.useGpu?"GPU + NPU":"CPU + NPU")+" gpu_tiles="+std::to_string(gpuTiles)+" cpu_tiles="+std::to_string(total-gpuTiles)+" gpu_post_ms="+std::to_string(gpuMs));
    vivo_nn::log("HEX FRAME COMPLETE: x"+std::to_string(b.scale)+" RGB -> "+(b.fullResolution?std::string("full native output"):"area"+std::to_string(b.scale))+" -> Bayer16; "+std::to_string(ow)+"x"+std::to_string(oh)+" actual_frames=6 ISO="+std::to_string(b.iso));
}
} // namespace vivo_hexquad
