#pragma once
// Main-camera 2x2 Quad (2x ISZ) neural remosaic with the vendor
// scam_ldr_imx06c_general_quad_x1 model (ScamCREConfigQuad.xml, NormalConfig):
// four equal-exposure quad-CFA RAWs, sparse per-frame RGB input at 544x544x12
// (frame-major, one colour per pixel from its SOURCE CFA site, warp=2),
// IMX06C VST with ISO-50 normalisation, x1 RGB output kept at the CFA site.
// Registration, halo/overlap and the NPU double buffer follow captureHex.
#include "scam-hexquad-capture.h"

namespace scam_quad {
using scam_hexquad::require;
using scam_hexquad::hexClockMs;
using scam_hexquad::RowExecutor;
constexpr int Frames=4, Side=544, Channels=12, Halo=32, Core=Side-2*Halo, Step=Core-32;
constexpr size_t Levels=scam_hexquad::Levels;

struct QuadBurst {
    int w=0,h=0,iso=0,red=0,model=0;
    // Network actually run: 0 IMX06C, 1 HP9 roi_quad_x1, 2 HP9 roi_quad_x1_highdrc.
    int network=0; float black=0,white=0; bool response=false;
    // Luma/chroma share of the network (1 = neural only, 0 = reference RAW), as in HexQuad.
    float luma=1.f,chroma=1.f;
    scam_hexquad::Rgb neutral{{1.f,1.f,1.f}};
    scam_hexquad::NoiseScale noise;
    std::array<const uint16_t*,Frames> raw{};
    // Extra equal-exposure RAWs merged into the four model slots (round-robin).
    std::vector<const uint16_t*> extra;
    std::array<float,16> gain;   // per site of the 4x4 quad period
    QuadBurst(){gain.fill(1.f);}
    // Canonical RGGB quad: 2x2 blocks R G / G B over a 4x4 period.
    static int color(int x,int y){const int q=((y&3)>>1)*2+((x&3)>>1);return q==0?0:q==3?2:1;}
    float sample(int f,int x,int y) const {return sampleRaw(raw[f],x,y);}
    float sampleRaw(const uint16_t* data,int x,int y) const {
        const scam_hexquad::CfaOrientation o(w,h,red);
        return std::max(0.f,std::min(1.f,(float(data[size_t(o.y(y))*w+o.x(x)])-black)/(white-black)*gain[(y&3)*4+(x&3)]));
    }
};

// Same transport as HexQuad (header v3/v4, 112 bytes) with a frame count of 4.
struct MappedQuadBurst {
    void* address=MAP_FAILED; size_t length=0; QuadBurst burst;
    explicit MappedQuadBurst(const std::string& path) {
        int fd=open(path.c_str(),O_RDONLY|O_CLOEXEC);
        if(fd<0)throw std::runtime_error("Cannot open Quad RAW burst");
        struct stat st{};
        if(fstat(fd,&st)||st.st_size<112||st.st_size>112+16000000LL*2*50){close(fd);throw std::runtime_error("Invalid Quad burst size");}
        length=size_t(st.st_size);address=mmap(nullptr,length,PROT_READ,MAP_PRIVATE,fd,0);close(fd);
        if(address==MAP_FAILED)throw std::runtime_error("Cannot map Quad burst");
        try {
            uint32_t v[28];std::memcpy(v,address,sizeof(v));
            require(v[0]==0x32515848&&(v[1]==3||v[1]==4)&&v[2]>=Side&&v[3]>=Side&&v[2]%8==0&&v[3]%8==0&&
                uint64_t(v[2])*v[3]<=16000000&&v[4]>=50&&v[4]<=12800&&v[5]<=3&&v[6]<=1&&v[7]==0&&v[11]>=Frames&&v[11]<=50,
                "Unsupported Quad RAW header / phase");
            burst.w=int(v[2]);burst.h=int(v[3]);burst.iso=int(v[4]);burst.red=int(v[5]);burst.model=int(v[6]);
            std::memcpy(&burst.black,&v[8],4);std::memcpy(&burst.white,&v[9],4);
            require(std::isfinite(burst.black)&&std::isfinite(burst.white)&&burst.black>=0&&
                    burst.white<=65535&&burst.white>burst.black+1&&v[10]<=1,"Invalid Quad radiometry");
            burst.response=v[10]!=0;
            float controls[5];std::memcpy(controls,static_cast<const uint8_t*>(address)+48,sizeof(controls));
            require(std::isfinite(controls[0])&&std::isfinite(controls[1])&&controls[0]>=0&&controls[0]<=1&&controls[1]>=0&&controls[1]<=1,
                    "Invalid Quad luma/chroma controls");
            burst.luma=controls[0];burst.chroma=controls[1];
            for(int c=0;c<3;++c){require(std::isfinite(controls[c+2])&&controls[c+2]>=.0001f&&controls[c+2]<=10000.f,"Invalid Quad neutral point");burst.neutral[c]=controls[c+2];}
            float n[3];std::memcpy(n,static_cast<const uint8_t*>(address)+84,sizeof(n));
            burst.noise={n[0],n[1],n[2]};burst.noise.validate();
            const size_t pixels=size_t(burst.w)*burst.h;
            require(length==112+pixels*v[11]*2,"Truncated Quad burst");
            const uint16_t* data=reinterpret_cast<const uint16_t*>(static_cast<const uint8_t*>(address)+112);
            for(int f=0;f<Frames;++f)burst.raw[f]=data+f*pixels;
            for(uint32_t f=Frames;f<v[11];++f)burst.extra.push_back(data+f*pixels);
        }catch(...){munmap(address,length);address=MAP_FAILED;throw;}
    }
    MappedQuadBurst(const MappedQuadBurst&)=delete;
    ~MappedQuadBurst(){if(address!=MAP_FAILED)munmap(address,length);}
};

// Per-site response inside each 2x2 colour block (quad sensitivity mismatch),
// from flat 16x16 windows of the reference, required to agree in all four image
// quadrants. Never changes a block's mean gain.
inline void estimateQuadResponse(QuadBurst& b) {
    if(!b.response)return;
    for(int q=0;q<4;++q){
        const int bx=(q&1)*2,by=(q>>1)*2;
        std::array<std::array<std::vector<float>,4>,4> samples;
        for(int y=0;y+16<=b.h;y+=32)for(int x=0;x+16<=b.w;x+=32){
            float values[4]={},mean=0;
            for(int cy=0;cy<4;++cy)for(int cx=0;cx<4;++cx)
                for(int k=0;k<4;++k)values[k]+=b.sample(0,x+cx*4+bx+(k&1),y+cy*4+by+(k>>1))/16.f;
            for(float v:values)mean+=v/4.f;
            if(mean<.02f||mean>.875f)continue;
            bool valid=true;for(float& v:values){v/=mean;valid=valid&&v>.8f&&v<1.25f;}
            if(!valid)continue;
            const int zone=(y>=b.h/2?2:0)+(x>=b.w/2?1:0);
            for(int k=0;k<4;++k)samples[zone][k].push_back(values[k]);
        }
        bool valid=true;std::array<float,4> profile{};
        for(int k=0;k<4&&valid;++k){
            std::vector<float> all;
            for(int zone=0;zone<4;++zone){if(samples[zone][k].size()<8)valid=false;all.insert(all.end(),samples[zone][k].begin(),samples[zone][k].end());}
            if(!valid||all.size()<64){valid=false;break;}
            profile[k]=scam_hexquad::median(all);
            for(int zone=0;zone<4;++zone)if(std::abs(scam_hexquad::median(samples[zone][k])-profile[k])>.02f)valid=false;
        }
        float mean=0;for(float v:profile)mean+=v/4;
        if(valid)for(int k=0;k<4;++k)b.gain[(by+(k>>1))*4+bx+(k&1)]=mean/profile[k];
        scam_nn::log("QUAD RESPONSE: block="+std::to_string(q)+" applied="+std::to_string(valid)+
            (valid?" sites="+std::to_string(profile[0])+","+std::to_string(profile[1])+","+std::to_string(profile[2])+","+std::to_string(profile[3]):""));
    }
}

inline float feather(float p){return std::min(1.f,std::min((p+.5f)/32.f,(Core-p-.5f)/32.f));}
inline std::vector<int> origins(int size){std::vector<int> r;for(int x=0;x<size;x+=Step){r.push_back(x);if(x+Core>=size)break;}return r;}

template<class Network> void captureQuad(Network& net,QuadBurst& b,const std::string& output) {
    const double started=hexClockMs();
    require(net.input.size()==size_t(Side)*Side*Channels&&net.output.size()==size_t(Side)*Side*3,"Unexpected Quad tensor allocation");
    scam_nn::log("QUAD CFA: sensor red="+std::to_string(b.red)+" -> canonical RGGB quad; output Bayer restored to sensor CFA");
    RowExecutor team;
    estimateQuadResponse(b);
    const double responseDone=hexClockMs();
    std::vector<scam_hexquad::Guide> guides;guides.reserve(Frames);
    for(int f=0;f<Frames;++f)guides.emplace_back(b,f,&team);
    std::vector<scam_hexquad::Flow> flows;flows.reserve(Frames-1);
    for(int f=1;f<Frames;++f)flows.emplace_back(guides[0],guides[f],&team);
    std::vector<scam_hexquad::Flow> extraFlows;std::vector<scam_hexquad::Guide> extraGuides;
    for(const uint16_t* data:b.extra){
        QuadBurst view=b;view.raw[1]=data;
        extraGuides.emplace_back(view,1,&team);
        extraFlows.emplace_back(guides[0],extraGuides.back(),&team);
    }
    if(!b.extra.empty())scam_nn::log("QUAD EXTRA FRAMES: "+std::to_string(b.extra.size())+" merged into the four slots");
    const double aligned=hexClockMs();
    scam_hexquad::SignalStats inputSignal;
    for(int y=0;y+4<=b.h;y+=64)for(int x=0;x+4<=b.w;x+=64)
        for(int dy=0;dy<4;++dy)for(int dx=0;dx<4;++dx)inputSignal.add(b.sample(0,x+dx,y+dy),QuadBurst::color(x+dx,y+dy));
    scam_nn::log("QUAD SIGNAL INPUT: black-subtracted normalised reference; "+inputSignal.summary());
    // Debug knobs (harness only): QUAD_NORM_ISO, QUAD_REF_SLOT.
    const char* normEnv=std::getenv("QUAD_NORM_ISO");const char* slotEnv=std::getenv("QUAD_REF_SLOT");
    const int normIso=normEnv?(std::atoi(normEnv)>0?std::atoi(normEnv):b.iso):50;
    if(const char* n=std::getenv("QUAD_NOISE")){b.noise.overall=float(std::atof(n));b.noise.validate();scam_nn::log("QUAD DEBUG: noise overall="+std::string(n));}
    const int refSlot=slotEnv?std::max(0,std::min(Frames-1,std::atoi(slotEnv))):0;
    if(normEnv||slotEnv)scam_nn::log("QUAD DEBUG: normIso="+std::to_string(normIso)+" refSlot="+std::to_string(refSlot));
    // TeleCamera ScamCREConfigROIQuad.xml (UseAnalogGainMethod=1): againCoeff 1.1 for
    // roi_quad_x1 and 1.2 for the highdrc entry scale the gain entering the noise
    // model; the ISO-50 VST normalisation is unchanged. Without it flat areas at
    // high ISO keep patches of quad-patterned noise. Main IMX06C uses no coefficient.
    const float againCoeff=b.network==2?1.2f:b.network==1?1.1f:1.f;
    const int noiseIso=std::min(12800,int(std::lround(b.iso*againCoeff)));
    const scam_hexquad::NormalVst transfer(noiseIso,b.noise,b.model==1?scam_hexquad::Hp9RoiQuadNoise:scam_hexquad::Imx06cNoise,normIso);
    const auto lut=transfer.forward();const auto inverse=transfer.inverse();
    scam_nn::log("QUAD RADIOMETRY: ISO="+std::to_string(b.iso)+" againCoeff="+std::to_string(againCoeff)+" noiseISO="+std::to_string(noiseIso)+" black="+std::to_string(b.black)+" white="+std::to_string(b.white)+
        (b.model==1?" HP9-ROI":" IMX06C")+std::string(" shot=")+std::to_string(transfer.shot)+" read_variance="+std::to_string(transfer.variance)+
        " vst_max="+std::to_string(lut[Levels-1]/65535.f)+"; ISO50 normalisation");
    std::vector<float> sum(size_t(b.w)*b.h,0.f),weight(sum.size(),0.f);
    // Hybrid luma/chroma (HexQuad controls): reference = the first RAW, measured
    // site kept, the other two colours from same-colour sites of a 5x5 window.
    const bool hybrid=b.luma<1.f||b.chroma<1.f;
    std::vector<float> refPlane;
    if(hybrid){
        refPlane.resize(size_t(b.w)*b.h);
        team.run(b.h,[&](int y){for(int x=0;x<b.w;++x)refPlane[size_t(y)*b.w+x]=b.sample(0,x,y);});
    }
    auto referenceRgb=[&](int x,int y){
        scam_hexquad::Rgb acc{0,0,0},wsum{0,0,0};
        for(int dy=-2;dy<=2;++dy)for(int dx=-2;dx<=2;++dx){
            const int sx=std::max(0,std::min(b.w-1,x+dx)),sy=std::max(0,std::min(b.h-1,y+dy));
            const int c=QuadBurst::color(sx,sy);
            const float w=std::exp(-float(dx*dx+dy*dy)*.35f);
            acc[c]+=w*refPlane[size_t(sy)*b.w+sx];wsum[c]+=w;
        }
        scam_hexquad::Rgb rgb;
        for(int c=0;c<3;++c)rgb[c]=wsum[c]>0?acc[c]/wsum[c]:0;
        rgb[QuadBurst::color(x,y)]=refPlane[size_t(y)*b.w+x];
        return rgb;
    };
    scam_nn::log("QUAD DENOISE: luma="+std::to_string(b.luma*100)+" chroma="+std::to_string(b.chroma*100)
        +"; 100=neural 0=single-reference quad reconstruction; neutral-balanced camera RGB");
    const auto xs=origins(b.w),ys=origins(b.h);
    size_t holes=0,samples=0,done=0;double packingMs=0,npuMs=0,assemblyMs=0,waitMs=0;
    scam_hexquad::OutputStats frameStats;
    struct PackInfo{size_t holes=0,samples=0;double ms=0;};
    auto pack=[&](std::vector<float>& input,int ox,int oy){
        const double begin=hexClockMs();PackInfo info;
        std::fill(input.begin(),input.end(),0.f);
        std::array<size_t,Side> rowHoles{},rowSamples{};
        team.run(Side,[&](int ty){for(int tx=0;tx<Side;++tx){
            const int x=scam_hexquad::reflect(ox-Halo+tx,b.w),y=scam_hexquad::reflect(oy-Halo+ty,b.h);
            for(int f=0;f<Frames;++f){
                const scam_hexquad::Shift shift=f?flows[f-1].at(x,y):scam_hexquad::Shift{};
                const int sx=x+int(std::lround(shift.x)),sy=y+int(std::lround(shift.y));
                bool valid=sx>=0&&sx<b.w&&sy>=0&&sy<b.h&&shift.error<.06f;
                if(valid&&f){
                    const float a=guides[0].at((x-3.5f)/8,(y-3.5f)/8),v=guides[f].at((sx-3.5f)/8,(sy-3.5f)/8);
                    valid=std::abs(a-v)<.08f;
                }
                if(tx>=Halo&&tx<Side-Halo&&ty>=Halo&&ty<Side-Halo){++rowSamples[ty];if(!valid)++rowHoles[ty];}
                if(!valid)continue; // sparse hole; never duplicate the reference
                const int c=QuadBurst::color(sx,sy);
                float own=b.sample(f,sx,sy);
                if(!b.extra.empty()){
                    const float sigma=std::sqrt(std::max(2*(transfer.shot*own+transfer.variance),1e-12f));
                    float sum=own,weight=1;
                    for(size_t e=size_t(f);e<b.extra.size();e+=Frames){
                        const scam_hexquad::Shift s=extraFlows[e].at(x,y);
                        const int ex=x+int(std::lround(s.x)),ey=y+int(std::lround(s.y));
                        if(ex<0||ex>=b.w||ey<0||ey>=b.h||s.error>=.06f||QuadBurst::color(ex,ey)!=c)continue;
                        const float v=b.sampleRaw(b.extra[e],ex,ey);
                        if(v>=.95f||own>=.95f)continue;
                        const float d=(v-own)/(3*sigma),w=std::exp(-d*d);sum+=w*v;weight+=w;
                    }
                    own=sum/weight;
                }
                const unsigned value=unsigned(std::lround(own*16383.f));
                const int slot=f==0?refSlot:(f<=refSlot?f-1:f);
                input[(size_t(ty)*Side+tx)*Channels+slot*3+c]=lut[c*Levels+value]*(1.f/65535.f);
            }
        }});
        for(int ty=0;ty<Side;++ty){info.holes+=rowHoles[ty];info.samples+=rowSamples[ty];}
        info.ms=hexClockMs()-begin;return info;
    };
    auto assemble=[&](int ox,int oy,const std::vector<float>& tileOut){
        const double tick=hexClockMs();
        std::array<scam_hexquad::OutputStats,Core> rowStats;
        team.run(std::min(Core,b.h-oy),[&](int ty){for(int tx=0;tx<Core&&ox+tx<b.w;++tx){
            const int x=ox+tx,y=oy+ty,c=scam_hexquad::bayerColor(x,y,0);
            const size_t index=(size_t(ty+Halo)*Side+tx+Halo)*3;
            for(int ch=0;ch<3;++ch)rowStats[ty].add(tileOut[index+ch],tx+Halo,ty+Halo,ch);
            float value=inverse[c][scam_hexquad::normalizedIvstIndex(tileOut[index+c])];
            if(hybrid){
                scam_hexquad::Rgb rgb;
                for(int ch=0;ch<3;++ch)rgb[ch]=inverse[ch][scam_hexquad::normalizedIvstIndex(tileOut[index+ch])];
                value=scam_hexquad::mixDetail(referenceRgb(x,y),rgb,b.luma,b.chroma,b.neutral)[c];
            }
            const float w=feather(float(tx))*feather(float(ty));const size_t at=size_t(y)*b.w+x;
            sum[at]+=value*w;weight[at]+=w;
        }});
        scam_hexquad::OutputStats tileStats;for(const auto& s:rowStats)if(s.count)tileStats.merge(s);
        frameStats.merge(tileStats);++done;assemblyMs+=hexClockMs()-tick;
        if(done==1||done%8==0)scam_nn::log("QUAD OUTPUT: tile="+std::to_string(done)+"/"+std::to_string(xs.size()*ys.size())+" "+tileStats.summary());
    };
    std::vector<std::pair<int,int>> order;
    for(int oy:ys)for(int ox:xs)order.push_back({ox,oy});
    // NPU overlap as in captureHex: tile i on the HTP while tile i-1 is assembled
    // and tile i+1 packed; buffers swap only between completed calls.
    std::vector<float> preparedInput(net.input.size()),finishedOutput(net.output.size());
    PackInfo current=pack(net.input,order[0].first,order[0].second),next;
    for(size_t i=0;i<order.size();++i){
        packingMs+=current.ms;holes+=current.holes;samples+=current.samples;
        double npuTile=0;
        auto npu=std::async(std::launch::async,[&]{const double t=hexClockMs();net.execute();npuTile=hexClockMs()-t;});
        try{
            if(i>0)assemble(order[i-1].first,order[i-1].second,finishedOutput);
            if(i+1<order.size())next=pack(preparedInput,order[i+1].first,order[i+1].second);
        }catch(...){npu.wait();throw;}
        const double waitStart=hexClockMs();npu.get();waitMs+=hexClockMs()-waitStart;npuMs+=npuTile;
        net.output.swap(finishedOutput);
        if(i+1<order.size()){net.input.swap(preparedInput);current=next;}
    }
    assemble(order.back().first,order.back().second,finishedOutput);
    require(samples>0&&double(holes)/samples<.40,"Too much motion / unreliable Quad registration");
    scam_nn::log("QUAD ALIGN: missing sparse samples="+std::to_string(double(holes)/samples));
    scam_nn::log("QUAD OUTPUT TOTAL: "+frameStats.summary());
    const double writeStart=hexClockMs();
    const scam_hexquad::CfaOrientation orientation(b.w,b.h,b.red);
    std::ofstream file(output,std::ios::binary|std::ios::trunc);require(bool(file),"Cannot open Quad output");
    scam_hexquad::SignalStats outputSignal;std::vector<uint16_t> row(b.w);
    for(int y=0;y<b.h;++y){for(int x=0;x<b.w;++x){
        const size_t i=size_t(orientation.y(y))*b.w+orientation.x(x);
        require(weight[i]>0&&std::isfinite(sum[i]),"Hole in Quad tile assembly");
        row[x]=scam_hexquad::encodeLinearBayer16(sum[i]/weight[i]);
        if(((x|y)&63)<2)outputSignal.add(row[x]/65535.f,scam_hexquad::bayerColor(x,y,b.red));
    }file.write(reinterpret_cast<const char*>(row.data()),b.w*2);}
    file.close();require(bool(file),"Quad output write failed");
    scam_nn::log("QUAD SIGNAL OUTPUT: Bayer16 black=0 white=65535; "+outputSignal.summary());
    scam_nn::log("QUAD TIMING ms: response="+std::to_string(responseDone-started)+" alignment="+std::to_string(aligned-responseDone)+
        " packing="+std::to_string(packingMs)+" npu="+std::to_string(npuMs)+" assembly="+std::to_string(assemblyMs)+
        " exposed_npu_wait="+std::to_string(waitMs)+" write="+std::to_string(hexClockMs()-writeStart)+
        " total="+std::to_string(hexClockMs()-started));
    scam_nn::log("QUAD FRAME COMPLETE: "+std::to_string(b.w)+"x"+std::to_string(b.h)+" tiles="+std::to_string(order.size())+
        " frames=4 ISO="+std::to_string(b.iso));
}
} // namespace scam_quad
