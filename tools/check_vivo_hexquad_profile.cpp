#include "../app/src/main/cpp/vivo-hexquad-profile-check.h"
#include <cassert>
using namespace vivo_hexquad;
struct ProfileReference {
    int iso,red,calls=0,fault=0,scale=2;bool cached=false;NoiseScale profile;
    std::vector<float> input=std::vector<float>(288u*288u*18),output=std::vector<float>(576u*576u*3);
    const float* bound=input.data();
    ProfileReference(int i,int r,int f=0,bool cache=false,int model=2,NoiseScale noise={}):iso(i),red(r),fault(f),scale(model),cached(cache),profile(noise){output.resize(size_t(288*scale)*288*scale*3);}
    void execute(){
        assert(input.data()==bound);
        int sensitivity=calls<18?100:calls<36?400:iso,chart=(calls/2)%9;
        if(cached){sensitivity=iso;chart=calls<2?0:2;}
        ++calls;
        // Independent packing oracle: six tagged RAW arrays passed through
        // the same sparse packer as preprocessing, not direct tensor indexing.
        auto transfer=NormalVst(sensitivity,profile);NormalVst physical(sensitivity);VstLuts luts;
        for(auto& lut:luts)lut=transfer.forward();
        ProfileNoise noise(calls%2?1:2);
        std::array<std::vector<uint16_t>,Frames> raw;
        std::array<TaggedFrame,Frames> frames;
        for(size_t f=0;f<Frames;++f){
            raw[f].resize(288*288);
            for(int y=0;y<288;++y)for(int x=0;x<288;++x){
                const int phase=((y/4)%2)*2+(x/4)%2;
                int c=phase==red?0:phase==(red^3)?2:1;
                float v=chartValue(chart,c,float(x),float(y));
                v+=noise.normal()*std::sqrt(physical.shot*v+physical.variance);
                auto value=uint16_t(std::lround(std::max(0.f,std::min(1.f,v))*16383.f));
                int xx=(red&1)?287-x:x,yy=(red&2)?287-y:y;
                raw[f][yy*288+xx]=uint16_t(value|(c<<14));
            }
            frames[f]={raw[f].data(),raw[f].size(),288,288,288};
        }
        assert(input==packTile(frames,luts,0,0,288,288,{1.f/65535.f,1.f,0}));
        auto lut=transfer.forward();
        int side=288*scale;
        for(int y=0;y<side;++y)for(int x=0;x<side;++x)for(int c=0;c<3;++c){
            int xx=(red&1)?side-1-x:x,yy=(red&2)?side-1-y:y;
            int channel=fault==1?2-c:c;
            float v=chartValue(chart,channel,(xx+.5f)/scale-.5f,(yy+.5f)/scale-.5f);
            if(fault==2 && c==2)v*=.5f;
            output[(size_t(y)*side+x)*3+c]=lut[unsigned(std::lround(v*16383.f))]/65535.f;
        }
        if(fault==3)output[(size_t(100)*side+100)*3]=2;
        if(fault==4)output[0]=std::numeric_limits<float>::quiet_NaN();
        if(fault==5)input[0]+=.01f;
    }
};
int main(){
    std::ostringstream messages;auto* previous=std::cout.rdbuf(messages.rdbuf());
    for(int red=0;red<4;++red){
        ProfileReference good(800,red);requireHexCaptureCharts(good,800,red);
        assert(good.calls==54);
    }
    for(int scale:{1,2})for(NoiseScale noise:std::vector<NoiseScale>{{1,.75f,1.5f},{1.25f,1,1}}){
        ProfileReference custom(800,3,0,true,scale,noise);requireHexCaptureCharts(custom,800,3,true,scale,noise);assert(custom.calls==4);
        ProfileReference bad(800,3,1,true,scale,noise);assert(!checkHexProfileCharts(bad,800,3,true,scale,noise));
    }
    ProfileReference cached(800,3,0,true);requireHexCaptureCharts(cached,800,3,true);assert(cached.calls==4);
    ProfileReference badCached(800,3,1,true);assert(!checkHexProfileCharts(badCached,800,3,true));
    ProfileReference duplicate(400,3);assert(checkHexProfileCharts(duplicate,400,3));assert(duplicate.calls==36);
    for(int fault:{1,2,3}){
        ProfileReference bad(800,3,fault);assert(!checkHexProfileCharts(bad,800,3));
    }
    for(int fault:{4,5}){
        ProfileReference bad(800,3,fault);bool rejected=false;
        try{requireHexCaptureCharts(bad,800,3);}catch(const std::invalid_argument&){rejected=true;}
        assert(rejected && bad.calls==1);
    }
    std::cout.rdbuf(previous);
    std::cout<<"Profiled capture gate: all CFAs, six independent RAW arrays, fixed buffer, strict colour/range and nonfinite checks PASS\n";
}
