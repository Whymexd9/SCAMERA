#include "../app/src/main/cpp/vivo-nice-capture.h"
#include <cassert>
#include <iostream>
using namespace vivo_nice;
static void checkWarpColorContinuity() {
    Burst b;b.w=64;b.h=64;b.white=16383;b.black.fill(0);
    std::vector<uint16_t> raw(64*64);b.raw[0]=raw.data();
    for(int cfa=0;cfa<4;++cfa) {
        b.cfa=cfa;
        // Separate, sloped colour planes expose both channel swaps and steps
        // at half-pixel contours; the previous uniform mock could not do so.
        for(int y=0;y<64;++y)for(int x=0;x<64;++x)
            raw[y*64+x]=1000+4000*b.color(x,y)+8*x+16*y;
        for(int y=24;y<26;++y)for(int x=24;x<26;++x) {
            int previous=-1;
            for(int i=-400;i<=400;++i) {
                const Shift shift{i*.01f,i*.003f};
                const auto value=warpBayer(b,0,x,y,shift);
                assert((value>>14)==b.color(x,y));
                const float expected=1000+4000*b.color(x,y)+8*(x+shift.x)+16*(y+shift.y);
                assert(std::abs(float(value&0x3fff)-expected)<=.51f);
                if(previous>=0)assert(std::abs(int(value&0x3fff)-previous)<=1);
                previous=value&0x3fff;
            }
        }
        for(int y=0;y<64;++y)for(int x=0;x<64;++x) {
            auto v=warpBayer(b,0,x,y,{0,0});
            assert((v&0x3fff)==raw[y*64+x]);assert((v>>14)==b.color(x,y));
        }
        assert(warpBayer(b,0,0,0,{-1,0})==0xc000);
        assert(warpBayer(b,0,63,63,{1,0})==0xc000);
        assert(warpBayer(b,0,20,20,{NAN,0})==0xc000);
        for(int x:{0,1,62,63})for(int y:{0,1,62,63}) {
            auto v=warpBayer(b,0,x,y,{x<32?.2f:-.2f,y<32?.2f:-.2f});
            assert((v>>14)==b.color(x,y));
        }
    }
    std::cout<<"PASS: fractional Bayer warp retains all four CFA layouts and continuous colour ramps\n";
}
int main(){
    checkWarpColorContinuity();
    // Constant HDR radiance, clipped in N and L, retained in S/ES. Mock graph
    // forwards the short-frame VST samples to RGB, testing real pre/post/tile
    // code without pretending to execute network weights on the host.
    Burst b;b.w=640;b.h=600;b.white=16383;b.cfa=3;b.black.fill(0);b.iso.fill(100);
    b.exposure={1,1,1,1,4,.25f,.25f};
    std::array<std::vector<uint16_t>,7> raw;
    for(int f=0;f<7;++f){raw[f].assign(size_t(b.w)*b.h,uint16_t(std::round(std::min(1.6f*b.exposure[f],1.f)*16383)));b.raw[f]=raw[f].data();}
    int tiles=0;
    auto out=reconstruct(b,[&](const std::vector<float>& in,std::vector<float>& result){
        ++tiles;for(size_t i=0;i<result.size()/3;++i){
            float v=std::max({in[i*22+15],in[i*22+16],in[i*22+17]});
            for(int c=0;c<3;++c)result[i*3+c]=v;
        }
    },[](const std::string&){});
    assert(tiles==4);float worst=0;
    for(float v:out){assert(std::isfinite(v));worst=std::max(worst,std::abs(v-1.6f));}
    assert(worst<.001f);
    for(int cfa=0;cfa<4;++cfa){b.cfa=cfa;assert(b.color(cfa&1,cfa>>1)==0);assert(b.color((cfa^3)&1,(cfa^3)>>1)==2);}
    for(int x=-32;x<b.w+32;++x)assert((reflectCfa(x,b.w)&1)==(x&1));
    // Transport rejects truncation and malformed calibration before inference.
    char file[]="/tmp/scamera-nice-XXXXXX";int fd=mkstemp(file);assert(fd>=0);close(fd);
    std::vector<uint8_t> bytes(128+64*64*14);uint32_t header[32]={0x3143484e,1,64,64,0,7};
    float white=16383;std::memcpy(header+6,&white,4);
    for(int i=0;i<7;++i){float ev=i>=5?.25f:1.f;std::memcpy(header+11+i,&ev,4);header[18+i]=100;}
    std::memcpy(bytes.data(),header,128);
    auto save=[&]{std::ofstream f(file,std::ios::binary|std::ios::trunc);f.write((const char*)bytes.data(),bytes.size());};
    save();{MappedNiceBurst mapped(file);assert(mapped.burst.w==64&&mapped.burst.iso[3]==100);}
    auto rejected=[&]{bool failed=false;try{MappedNiceBurst mapped(file);}catch(const std::exception&){failed=true;}assert(failed);};
    bytes.pop_back();save();rejected();bytes.push_back(0);
    uint32_t nan=0x7fc00000;std::memcpy(bytes.data()+44,&nan,4);save();rejected();
    // Version 2 uses this camera's measured noise, without a sensor-ID or
    // IMX ISO-range gate. Corrupt calibration never reaches inference.
    header[1]=2;header[18+3]=25600;header[27]=1;
    float slope=.00015f,offset=.000002f;
    std::memcpy(header+25,&slope,4);std::memcpy(header+26,&offset,4);
    std::memcpy(bytes.data(),header,128);save();
    {MappedNiceBurst mapped(file);assert(mapped.burst.cameraNoise&&mapped.burst.diagnostics);
     assert(mapped.burst.iso[3]==25600&&mapped.burst.noise.slope==slope&&mapped.burst.noise.offset==offset);}
    auto invalidWord=[&](int index,uint32_t value){auto valid=bytes;std::memcpy(bytes.data()+index*4,&value,4);save();rejected();bytes=valid;};
    invalidWord(25,0);invalidWord(25,nan);invalidWord(26,0xbf800000);invalidWord(27,2);invalidWord(28,1);
    unlink(file);
    // The generic profile must round-trip calibrated radiance through the
    // actual VST/IVST; snapshots must observe graph values, not alter output.
    b.cameraNoise=true;b.noise={slope,offset};b.iso.fill(25600);int snapshots=0;tiles=0;
    bool checkedTensor=false;
    auto generic=reconstruct(b,[&](const std::vector<float>& in,std::vector<float>& result){
        // Independent expected scalar scale: baseline is the model's ISO 50,
        // even though the current Camera2 slope is .00015 at ISO 25600.
        const double baseSlope=(0.0001242085*50-0.0014234833)/255;
        const double baseOffset=(0.0272538637+0.0000000158*50*50+0.0000376323*50)/65025;
        const double norm=2*std::sqrt(1/baseSlope+baseOffset/(baseSlope*baseSlope)+.375);
        const double currentOffset=double(offset)/(double(slope)*slope)+.375;
        const double expectedMask=2*std::sqrt(1/double(slope)+currentOffset)/norm;
        assert(std::abs(in[21]-expectedMask)<.00004);checkedTensor=true;
        ++tiles;for(size_t i=0;i<result.size()/3;++i)for(int c=0;c<3;++c)
            result[i*3+c]=std::max({in[i*22+15],in[i*22+16],in[i*22+17]});
    },[](const std::string&){},[&](const std::string&,const std::vector<float>& values,int w,int h){
        ++snapshots;assert(w==544&&h==544&&values.size()==size_t(w)*h*3);assert(std::isfinite(values[0]));
    });
    assert(tiles==4&&snapshots==2&&checkedTensor);
    for(float v:generic)assert(std::isfinite(v)&&std::abs(v-1.6f)<.001f);

    std::cout<<"PASS: NICE full tile path, HDR 1.6 retained, 4-tile overlap/crop coverage; error="<<worst<<" (mock graph)\n";
}
