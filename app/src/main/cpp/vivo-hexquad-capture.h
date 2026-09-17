#pragma once
#include "vivo-hexquad-check.h"
#include <sys/mman.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <fstream>
#include <limits>

namespace vivo_hexquad {
// Versioned little-endian transport. Six DISTINCT, equal-exposure RAW16 frames.
// Output is Bayer16 at the input size, not an interpolated 4x-larger photograph.
struct RawBurst {
    int w=0,h=0,iso=0,red=0; float black=0,white=0; bool response=false;
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
        if(fstat(fd,&st)||st.st_size<64||st.st_size>64+16000000LL*12){close(fd);throw std::runtime_error("Invalid burst file size");}
        length=size_t(st.st_size);address=mmap(nullptr,length,PROT_READ,MAP_PRIVATE,fd,0);close(fd);
        if(address==MAP_FAILED)throw std::runtime_error("Cannot map burst");
        try {
            uint32_t v[16];std::memcpy(v,address,64);
            require(v[0]==0x32515848&&v[1]==1&&v[2]>=288&&v[3]>=288&&v[2]%8==0&&v[3]%8==0&&
                uint64_t(v[2])*v[3]<=16000000&&v[4]>=50&&v[4]<=12800&&v[5]<=3&&v[6]==0&&v[7]==0,
                "Unsupported HexQuad RAW header / phase");
            burst.w=int(v[2]);burst.h=int(v[3]);burst.iso=int(v[4]);burst.red=int(v[5]);
            std::memcpy(&burst.black,&v[8],4);std::memcpy(&burst.white,&v[9],4);
            require(std::isfinite(burst.black)&&std::isfinite(burst.white)&&burst.black>=0&&
                    burst.white<=65535&&burst.white>burst.black+1&&v[10]<=1&&v[11]==6,
                    "Invalid HexQuad radiometry");
            burst.response=v[10]!=0;
            size_t pixels=size_t(burst.w)*burst.h;
            require(length==64+pixels*Frames*2,"Truncated HexQuad burst");
            const uint16_t* data=reinterpret_cast<const uint16_t*>(static_cast<const uint8_t*>(address)+64);
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
    Guide(const RawBurst& b,int f):w(b.w/8),h(b.h/8),data(size_t(w)*h){
        for(int y=0;y<h;++y)for(int x=0;x<w;++x){float sum=0;
            for(int ky=0;ky<8;++ky)for(int kx=0;kx<8;++kx)sum+=b.sample(f,x*8+kx,y*8+ky);
            data[y*w+x]=sum/64;
        }
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
    Flow(const Guide& ref,const Guide& src):nx((ref.w+15)/16+1),ny((ref.h+15)/16+1),field(size_t(nx)*ny){
        Shift global;global.error=matchCost(ref,src,ref.w/2,ref.h/2,std::max(ref.w,ref.h),0,0,8);
        for(int dy=-12;dy<=12;++dy)for(int dx=-12;dx<=12;++dx){
            float cost=matchCost(ref,src,ref.w/2,ref.h/2,std::max(ref.w,ref.h),dx,dy,8);
            // Mild preference for smaller displacement in flat/repeated patterns.
            if(cost+.00001f*(dx*dx+dy*dy)<global.error+.00001f*(global.x*global.x+global.y*global.y))global={float(dx),float(dy),cost};
        }
        for(int gy=0;gy<ny;++gy)for(int gx=0;gx<nx;++gx){
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
        }
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
// reflect101 keeps physical source coordinates available for CFA tagging.
inline int reflect(int p,int size){int period=2*(size-1);p=((p%period)+period)%period;return p<size?p:period-p;}
constexpr int Halo=32, Core=224, Step=192;
inline float feather(int p){return std::min(1.f,std::min((p+.5f)/32.f,(Core-p-.5f)/32.f));}
inline std::vector<int> origins(int size){std::vector<int> r;for(int x=0;x<size;x+=Step){r.push_back(x);if(x+Core>=size)break;}return r;}
inline int bayerColor(int x,int y,int red){int q=(y&1)*2+(x&1);return q==red?0:q==(red^3)?2:1;}

// A Network template makes tile coverage, halo rejection, scaling, CFA and
// stale client-buffer bugs testable independently from proprietary HTP weights.
template<class Network> void captureHex(Network& net,RawBurst& b,const std::string& output){
    const CfaOrientation orientation(b.w,b.h,b.red);
    vivo_nn::log("HEX CFA: sensor red="+std::to_string(b.red)+
        " -> canonical RGGB; flip_x="+std::to_string(bool(b.red&1))+
        " flip_y="+std::to_string(bool(b.red&2))+"; output restored to sensor orientation/CFA");
    estimateResponse(b);
    std::vector<Guide> guides;for(int f=0;f<6;++f)guides.emplace_back(b,f);
    std::vector<Flow> flows;for(int f=1;f<6;++f)flows.emplace_back(guides[0],guides[f]);
    NormalVst transfer(b.iso);auto lut=transfer.forward();auto inverse=transfer.inverse();
    std::vector<float> sum(size_t(b.w)*b.h,0),weight(sum.size(),0);
    const auto xs=origins(b.w),ys=origins(b.h);size_t done=0,total=xs.size()*ys.size(),holes=0,samples=0;
    require(net.input.size()==288u*288u*18,"Unexpected HexQuad input allocation");
    for(int oy:ys)for(int ox:xs){
        std::fill(net.input.begin(),net.input.end(),0.f);
        for(int ty=0;ty<288;++ty)for(int tx=0;tx<288;++tx){
            int x=reflect(ox-Halo+tx,b.w),y=reflect(oy-Halo+ty,b.h);
            for(int f=0;f<6;++f){
                Shift shift=f?flows[f-1].at(x,y):Shift{};
                int sx=x+int(std::lround(shift.x)),sy=y+int(std::lround(shift.y));
                bool valid=sx>=0&&sx<b.w&&sy>=0&&sy<b.h&&shift.error<.06f;
                if(valid&&f){
                    float a=guides[0].at((x-3.5f)/8,(y-3.5f)/8),v=guides[f].at((sx-3.5f)/8,(sy-3.5f)/8);
                    valid=std::abs(a-v)<.08f;
                }
                if(tx>=Halo&&tx<288-Halo&&ty>=Halo&&ty<288-Halo){++samples;if(!valid)++holes;}
                if(!valid)continue; // sparse hole, never duplicate the base frame
                int c=b.color(sx,sy);unsigned value=unsigned(std::lround(b.sample(f,sx,sy)*16383.f));
                net.input[(size_t(ty)*288+tx)*18+f*3+c]=lut[c*Levels+value]*(1.f/65535.f);
            }
        }
        net.execute();require(net.output.size()==576u*576u*3,"Unexpected HexQuad output shape");
        // Nonfinite anywhere means an invalid execution. Range overshoot is
        // evaluated ONLY in the retained area; halo is discarded, not clamped in.
        for(float v:net.output)require(std::isfinite(v),"Nonfinite HexQuad output");
        for(int ty=0;ty<Core&&oy+ty<b.h;++ty)for(int tx=0;tx<Core&&ox+tx<b.w;++tx){
            int x=ox+tx,y=oy+ty,c=bayerColor(x,y,0);float value=0;
            for(int dy=0;dy<2;++dy)for(int dx=0;dx<2;++dx){
                size_t index=(size_t((ty+Halo)*2+dy)*576+(tx+Halo)*2+dx)*3;
                for(int ch=0;ch<3;++ch)require(net.output[index+ch]>=-.05f&&net.output[index+ch]<=1.25f,"HexQuad range failure in retained image");
                float raw=std::max(0.f,std::min(65535.f,net.output[index+c]*65535.f));
                value+=inverse[c][unsigned(raw)]*.25f;
            }
            float w=feather(tx)*feather(ty);size_t index=size_t(y)*b.w+x;
            sum[index]+=value*w;weight[index]+=w;
        }
        ++done;if(done==1||done%8==0||done==total)vivo_nn::log("HEX TILE: "+std::to_string(done)+"/"+std::to_string(total));
    }
    require(samples>0&&double(holes)/samples<.40,"Too much motion / unreliable HexQuad registration");
    vivo_nn::log("HEX ALIGN: missing sparse samples="+std::to_string(double(holes)/samples));
    std::ofstream file(output,std::ios::binary|std::ios::trunc);require(bool(file),"Cannot open HexQuad output");
    std::vector<uint16_t> row(b.w);
    for(int y=0;y<b.h;++y){for(int x=0;x<b.w;++x){
        size_t i=size_t(orientation.y(y))*b.w+orientation.x(x);
        require(weight[i]>0&&std::isfinite(sum[i]),"Hole in HexQuad tile assembly");
        row[x]=uint16_t(std::lround(std::max(b.black,std::min(b.white,b.black+sum[i]/weight[i]*(b.white-b.black)))));
    }file.write(reinterpret_cast<const char*>(row.data()),b.w*2);}
    file.close();require(bool(file),"HexQuad output write failed");
    vivo_nn::log("HEX FRAME COMPLETE: x2 RGB -> area2x2 -> Bayer16; "+std::to_string(b.w)+"x"+std::to_string(b.h)+" actual_frames=6 ISO="+std::to_string(b.iso));
}
} // namespace vivo_hexquad
