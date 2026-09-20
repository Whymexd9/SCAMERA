#include "../app/src/main/cpp/vivo-nice-stock-motion.h"
#include <cassert>
#include <iostream>
using namespace vivo_nice;
int main() {
    // Non-affine donor->reference guide H. Verify inversion direction and
    // conversion to RAW coordinates against the defining correspondence.
    std::array<float,9> h{1.02f,.04f,2,-.03f,.98f,-1,.0002f,-.0001f,1};
    auto backward=StockMotion::inverseToRaw(h);
    for(int y=0;y<80;y+=11)for(int x=0;x<100;x+=13) {
        // inverseToRaw expects integer destinations; invert the resulting
        // donor position through the original H instead of rounding the target.
        auto d=backward.project(x*4,y*4);
        double dx=d.x/4,dy=d.y/4,den=h[6]*dx+h[7]*dy+h[8];
        assert(std::abs((h[0]*dx+h[1]*dy+h[2])/den-x)<.0001);
        assert(std::abs((h[3]*dx+h[4]*dy+h[5])/den-y)<.0001);
    }
    for(auto bad: {std::array<float,9>{},std::array<float,9>{1,0,0,0,0,0,0,0,1}}) {
        bool rejected=false;try{StockMotion::inverseToRaw(bad);}catch(const std::exception&){rejected=true;}
        assert(rejected);
    }
    StockMotion::validateFrameMap(backward,400,320);
    BackwardHomography horizon;horizon.h[6]=-.02f;
    // All corners project to finite values, but x=50 is singular inside.
    for(int y:{0,99})for(int x:{0,99})BackwardHomography::checkCoordinate(horizon.project(x,y));
    bool rejectedHorizon=false;
    try{StockMotion::validateFrameMap(horizon,100,100);}catch(const std::exception&){rejectedHorizon=true;}
    assert(rejectedHorizon);
    Burst b;b.w=64;b.h=64;b.cfa=0;b.white=16383;b.black.fill(0);b.iso.fill(100);b.exposure.fill(1);
    std::vector<uint16_t> raw(64*64);
    for(int y=0;y<64;++y)for(int x=0;x<64;++x)raw[y*64+x]=uint16_t(1000+x*80+y*32);
    for(auto& ptr:b.raw)ptr=raw.data();
    bool called=false;
    auto out=reconstruct(b,[](const std::vector<float>& input,std::vector<float>& output){
        for(size_t i=0;i<output.size()/3;++i) {
            float sample=std::max({input[i*22+3],input[i*22+4],input[i*22+5]});
            for(int c=0;c<3;++c)output[i*3+c]=sample;
        }
    },[](const std::string&){},{},[&](Burst& canonical){
        assert(canonical.canonicalRggb);called=true;
        std::array<BackwardHomography,7> plan{};plan[1].h[2]=4;return plan;
    });
    assert(called);
    std::array<bool,7> failed{};failed[4]=true;
    std::array<BackwardHomography,7> replacement{};
    b.cameraNoise=true;b.iso[4]=800;b.exposure[4]=4;
    bool missingNoise=false;
    try {StockMotion::replaceFailed(b,failed,replacement);}catch(const std::exception&){missingNoise=true;}
    assert(missingNoise && b.exposure[4]==4 && b.iso[4]==800);
    b.hasNormalNoise=true;b.normalNoise={.0001f,.000001f};
    StockMotion::replaceFailed(b,failed,replacement);
    assert(b.raw[4]==b.raw[0] && b.iso[4]==b.iso[0] && b.exposure[4]==1);
    assert(b.noise.slope==b.normalNoise.slope && b.noiseReferenceSlot==0);
    auto domains=forwardExposureDomains(b.exposure);
    assert(domains.normalizationEV==domains.normalEV);
    float error=0;
    for(int y=0;y<60;++y)for(int x=0;x<58;++x)
        for(int c=0;c<3;++c)error=std::max(error,std::abs(out[(y*64+x)*3+c]-raw[y*64+x+4]/16383.f));
    assert(error<.0002f);
    std::cout<<"PASS: original matrix direction, RAW scaling, singular/horizon rejection and projective capture dispatch; mock graph error="<<error<<"\n";
}
