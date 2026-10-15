#include "../app/src/main/cpp/scam-capture.h"
#include "../app/src/main/cpp/scam-hybrid.h"
#include <cassert>
#include <cstdio>
#include <cstring>
#include <unistd.h>

using namespace scam;

struct Fixture {
    std::string path;
    Fixture(int grid) {
        const char* temp=std::getenv("TMPDIR");
        path=std::string(temp?temp:"/tmp")+"/scamera-fullres-2x-XXXXXX";
        const int fd=mkstemp(path.data());
        assert(fd>=0);
        uint32_t words[32+8*3]{};
        words[0]=0x3143484e;words[1]=11;words[2]=8192;words[3]=6144;words[5]=3;
        const float white=1023,black=64,exposure=1,slope=0.0001f,offset=0.000001f;
        std::memcpy(words+6,&white,4);
        for(int i=7;i<11;++i)std::memcpy(words+i,&black,4);
        words[11]=8;words[13]=uint32_t(grid);words[14]=1;
        for(int i=0;i<3;++i){
            uint32_t* frame=words+32+8*i;
            frame[0]=kRoleNormal;frame[2]=250;
            std::memcpy(frame+1,&exposure,4);std::memcpy(frame+3,&slope,4);std::memcpy(frame+4,&offset,4);
        }
        assert(write(fd,words,sizeof(words))==ssize_t(sizeof(words)));
        assert(ftruncate(fd,off_t(sizeof(words))+off_t(8192)*6144*2*3)==0);
        assert(close(fd)==0);
    }
    ~Fixture(){unlink(path.c_str());}
};

int main() {
    Fixture full(2);
    MappedHybridBurst burst(full.path);
    assert(burst.hybrid&&burst.input.strict2x&&burst.input.grid==2);
    assert(burst.input.w==8192&&burst.input.h==6144&&burst.input.frames.size()==3);
    assert(burst.input.frames[1].raw-burst.input.frames[0].raw==8192*6144);
    HybridTuning tuning;tuning.grid=1;tuning.auto2x=1;
    assert(hybridRequestedGrid(burst.input,tuning)==2);
    assert(!hybridAuto2x(burst.input,tuning,true));
    HybridInput adaptive=burst.input;adaptive.strict2x=false;
    assert(hybridRequestedGrid(adaptive,tuning)==1);
    assert(hybridAuto2x(adaptive,tuning,true));
    HybridInput nativeTetra=burst.input;nativeTetra.grid=8;
    assert(hybridRequestedGrid(nativeTetra,tuning)==8);
    const auto report=[](const std::string&){};
    try {
        hybridReconstructMosaic(burst.input,4,tuning,{},report,nullptr,nullptr,nullptr,nullptr);
        assert(false&&"a strict 2x shot must not use the interpolated split output");
    } catch(const std::runtime_error& error) {
        assert(std::string(error.what()).find("sub-frame resampling")!=std::string::npos);
    }
    tuning.mosaicTetra=2;
    try {
        hybridReconstructMosaicNative(burst.input,4,tuning,{},report,nullptr,nullptr,nullptr,nullptr);
        assert(false&&"Tetra T2 must not masquerade as native Sabre 2x");
    } catch(const std::runtime_error& error) {
        assert(std::string(error.what()).find("resampling is disabled")!=std::string::npos);
    }
    Fixture wrong(1);
    try { MappedHybridBurst invalid(wrong.path);assert(false&&"strict flag requires a 2x grid"); }
    catch(const std::runtime_error& error) { assert(std::string(error.what()).find("requires output grid 2")!=std::string::npos); }
    std::puts("PASS: SDK50 native 2x transport; no coarse-grid or mosaic-resize fallback");
}
