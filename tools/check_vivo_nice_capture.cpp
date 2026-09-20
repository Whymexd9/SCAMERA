#include "../app/src/main/cpp/vivo-nice-capture.h"
#include <cassert>
#include <iostream>
using namespace vivo_nice;
int main(){
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
    auto generic=reconstruct(b,[&](const std::vector<float>& in,std::vector<float>& result){
        ++tiles;for(size_t i=0;i<result.size()/3;++i)for(int c=0;c<3;++c)
            result[i*3+c]=std::max({in[i*22+15],in[i*22+16],in[i*22+17]});
    },[](const std::string&){},[&](const std::string&,const std::vector<float>& values,int w,int h){
        ++snapshots;assert(w==544&&h==544&&values.size()==size_t(w)*h*3);assert(std::isfinite(values[0]));
    });
    assert(tiles==4&&snapshots==2);
    for(float v:generic)assert(std::isfinite(v)&&std::abs(v-1.6f)<.001f);

    std::cout<<"PASS: NICE full tile path, HDR 1.6 retained, 4-tile overlap/crop coverage; error="<<worst<<" (mock graph)\n";
}
