#include "../app/src/main/cpp/vivo-nice-preprocess.h"
#include <cassert>
#include <iostream>
#include <limits>
using namespace vivo_nice;
int main() {
    // Explicit Bayer tags, including invalid warp samples, padded row and ROI.
    constexpr unsigned mask=1023;
    std::vector<uint16_t> lut(3*(mask+1));
    for (unsigned c=0;c<3;++c) for(unsigned i=0;i<=mask;++i) lut[c*(mask+1)+i]=1000*c+i;
    std::vector<uint16_t> raw={555,0x000a,0x4014,999,999,0x801e,0xc028,999};
    std::array<TaggedFrame,7> frames;
    for(auto& f:frames) f={raw.data(),raw.size(),4,1,0,0,lut.data(),lut.size()};
    auto packed=packSevenFrames(frames,2,2,mask,0,.001f,1234,5.f);
    for(unsigned p=0;p<4;++p) {
        const float expected[][3]={{.01f,0,0},{0,1.02f,0},{0,0,2.03f},{0,0,0}};
        for(unsigned f=0;f<7;++f) for(unsigned c=0;c<3;++c)
            assert(std::abs(packed[p*22+f*3+c]-expected[p][c])<1e-6);
        assert(std::abs(packed[p*22+21]-1.234f)<1e-6);
    }
    // Planar RGB input, distinct frame order, uint16 left-shift narrowing, clipping.
    std::array<std::array<uint16_t,3>,7> rgb;
    for(unsigned i=0;i<7;++i){rgb[i]={uint16_t(i+1),uint16_t(0x4000+i+2),uint16_t(0x8000+i+3)};
        frames[i]={rgb[i].data(),3,1,0,1,2,lut.data(),lut.size()};}
    packed=packSevenFrames(frames,1,1,mask,1,.001f,99,3.f);
    for(unsigned i=0;i<7;++i){assert(std::abs(packed[i*3]-.002f*(i+1))<1e-6);
        assert(std::abs(packed[i*3+1]-.002f*(1002+i))<1e-6);assert(packed[i*3+2]==3.f);}
    auto reject=[&](auto fn){bool threw=false;try{fn();}catch(const std::invalid_argument&){threw=true;}assert(threw);};
    frames[0].blueOffset=std::numeric_limits<size_t>::max();
    reject([&]{packSevenFrames(frames,1,1,mask,0,1,0,1);});
    frames[0].blueOffset=2;frames[0].lutCount=1024;
    reject([&]{packSevenFrames(frames,1,1,mask,0,1,0,1);});
    reject([&]{packSevenFrames(frames,545,1,mask,0,1,0,1);});
    VstMode2 vst{.01f,.001f,.001f,.000001f,1,1,100,1,{1,1,1},10,15};
    const auto table=makeVstMode2(vst);assert(table.size()==3072);
    for(unsigned i=1;i<1024;++i)assert(table[i]>=table[i-1]);
    vst.norm=0;reject([&]{makeVstMode2(vst);});
    std::array<std::vector<float>,3> inverse;
    for(unsigned c=0;c<3;++c) {
        inverse[c].resize(16384);
        for(unsigned i=0;i<16384;++i) inverse[c][i]=float(i)/1000+c;
    }
    std::vector<float> model={-1,1.999f,100000,2,4,6};
    auto restored=inverseVstTile(model,2,1,inverse,2,2,0,0,32,{}, {}, {},false);
    assert(restored[0]==0);assert(restored[1]==1); // truncate, then shift; no rounding
    assert(std::abs(restored[2]-18.383f)<1e-5); // upper clamp to LUT, preserve HDR > 1
    auto blended=inverseVstTile(model,2,1,inverse,2,2,0,0,32,{.5f},{.25f,1},
                               std::vector<float>(6,1),true);
    assert(std::abs(blended[2]-(restored[2]*.125f+1))<1e-6);
    model[0]=std::numeric_limits<float>::quiet_NaN();
    reject([&]{inverseVstTile(model,2,1,inverse,2,2,0,0,32,{}, {}, {},false);});
    std::cout<<"PASS: NICE tagged Bayer/planar RGB, invalid tags, frame/channel order, ROI/stride, clipping and bounds\n";
}
