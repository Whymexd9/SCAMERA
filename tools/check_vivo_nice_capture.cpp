#include "../app/src/main/cpp/vivo-nice-capture.h"
#include <cassert>
#include <iostream>
using namespace vivo_nice;
static void checkRecoveredWarpContracts() {
    Burst b;b.w=64;b.h=64;b.white=16383;b.black.fill(0);b.canonicalRggb=true;
    std::vector<uint16_t> raw(64*64);b.raw[0]=raw.data();
    for(int cfa=0;cfa<4;++cfa){
        b.cfa=cfa;
        for(int y=0;y<64;++y)for(int x=0;x<64;++x){
            const int phase=(y%2)*2+x%2;
            raw[y*64+x]=phase==cfa?1200:phase==(3-cfa)?9200:4800;
        }
        // Input must be RGGB independently of the Camera2 sensor enumeration.
        assert(raw14(b,0,20,20)==1200);assert(raw14(b,0,21,20)==4800);
        assert(raw14(b,0,20,21)==4800);assert(raw14(b,0,21,21)==9200);
        const int expected[]={1200,4800,9200};
        for(int y:{0,1,30,31,62,63})for(int x:{0,1,30,31,62,63})
            for(float dx:{-2.2f,0.f,.49f,.51f,2.2f})for(float dy:{-2.2f,0.f,2.2f}){
                auto v=warpOrderBayer(b,0,x,y,{dx,dy});
                assert((v>>14)<3);assert((v&16383)==expected[v>>14]);
                auto rgb=warpShortRgb(b,0,x,y,{dx,dy});
                for(int c=0;c<3;++c){assert((rgb[c]>>14)==c);assert((rgb[c]&16383)==expected[c]);}
            }
    }
    // warp=2 rounds the cell origin, not four independent positions. An odd
    // translation must carry the donor tags, including its two green sites.
    b.cfa=0;
    for(int y=0;y<64;++y)for(int x=0;x<64;++x)raw[y*64+x]=uint16_t(y*64+x);
    auto v=warpOrderBayer(b,0,21,21,{.6f,0});
    assert((v&16383)==21*64+22);assert((v>>14)==1);
    // swarp=6's half-pixel and per-row green addressing, evaluated by hand
    // for identity at (20,20): R(20,20), G average of (21,20)/(20,21), B(21,21).
    auto rgb=warpShortRgb(b,0,20,20,{0,0});
    assert((rgb[0]&16383)==1300);assert((rgb[1]&16383)==1333);assert((rgb[2]&16383)==1365);
    for(int cfa=0;cfa<4;++cfa){
        std::vector<float> values(8*8*3);
        for(size_t i=0;i<values.size();++i)values[i]=float(i);
        restoreSensorOrigin(values,8,8,cfa);
        for(int y=0;y<8;++y)for(int x=0;x<8;++x)for(int c=0;c<3;++c)
            assert(values[(y*8+x)*3+c]==float((std::max(0,y-(cfa>>1))*8+std::max(0,x-(cfa&1)))*3+c));
    }
    std::cout<<"PASS: canonical RGGB, ordered Bayer warp=2, dense short swarp=6, inverse origin shift\n";
}
int main(){
    checkRecoveredWarpContracts();
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

    // Exercise the real seven-frame tile packer with unequal R/G/B signals.
    // The old achromatic mock did not catch missing dense S/ES channels or
    // the BGGR input passed to an RGGB-trained graph.
    b.w=64;b.h=64;b.cameraNoise=false;b.iso.fill(100);
    const float scene[]={.12f,.24f,.48f};
    for(int cfa=0;cfa<4;++cfa){
        b.cfa=cfa;
        for(int f=0;f<7;++f){
            raw[f].resize(64*64);b.raw[f]=raw[f].data();
            for(int y=0;y<64;++y)for(int x=0;x<64;++x){
                const int phase=(y%2)*2+x%2;
                const int c=phase==cfa?0:phase==(3-cfa)?2:1;
                raw[f][y*64+x]=uint16_t(std::round(std::min(scene[c]*b.exposure[f],1.f)*16383));
            }
        }
        const auto color=reconstruct(b,[&](const std::vector<float>& in,std::vector<float>& result){
            for(int y=0;y<544;++y)for(int x=0;x<544;++x){
                const size_t i=size_t(y)*544+x;
                const int phase=(y%2)*2+x%2,c=phase==0?0:phase==3?2:1;
                for(int k=0;k<3;++k){
                    assert((in[i*22+9+k]>0)==(k==c));
                    assert(in[i*22+15+k]>0&&in[i*22+18+k]>0);
                    result[i*3+k]=in[i*22+15+k];
                }
            }
        },[](const std::string&){});
        for(size_t i=0;i<color.size();++i)assert(std::abs(color[i]-scene[i%3])<.001f);
    }

    std::cout<<"PASS: NICE full tile path, HDR 1.6 retained, 4-tile overlap/crop coverage; error="<<worst<<" (mock graph)\n";
}
