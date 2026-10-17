// P190: a FULLRES series (tuning seriesFrames, an explicit «Кадров в снимке 50 / 200 Мп») floors the mosaic frame pick; without the
// key every budget is the one before P190 (the same frames, so the same picture); the series lines the app reads.
#include "../app/src/main/cpp/scam-capture.h"
#include "../app/src/main/cpp/scam-hybrid.h"
#include <cassert>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <unistd.h>

using namespace scam;

// The budgets as they were before P190 (cc70eb27), with MemAvailable given
static int oldNative(int n,int W,int H,int bb,bool t2,int mosaicFrames,uint64_t avail){
    int budget=std::max(1,std::min(kHybridGpuFrames,std::max(2,mosaicFrames)));
    if(int64_t(W)*H>kHybridClassicPixels&&avail>0){
        const double P=double(W)*H,perFrame=2.0*P/bb+(t2?0.5*P:0.0);
        const int byMemory=std::max(2,int(std::max(0.0,0.5*double(avail)-20.0*P)/perFrame));
        if(byMemory<budget)budget=byMemory;
    }
    if(int64_t(W)*H>kHybridHugePixels&&budget<std::min(n,kHybridHugeFrames))budget=std::min(n,kHybridHugeFrames);
    return budget;
}
static int oldSplit(int W,int H,int per,int mosaicFrames,uint64_t avail){
    int budget=std::max(1,std::min(kHybridGpuFrames/per,std::max(2,mosaicFrames)));
    if(int64_t(W)*H>kHybridClassicPixels&&avail>0){
        const double P=double(W)*H,perFrame=2.0*P*(1.0+1.0/per);
        const int byMemory=std::max(2,int(std::max(0.0,0.5*double(avail)-20.0*P)/perFrame));
        if(byMemory<budget)budget=byMemory;
    }
    return budget;
}

int main() {
    std::vector<std::string> lines;
    const auto report=[&](const std::string& line){lines.push_back(line);};
    const uint64_t MB=1ull<<20;
    HybridTuning tune;
    assert(tune.seriesFrames==0);
    // no seriesFrames: the budgets of before, a 4-frame 201 MP Tetra burst and a 30-frame 50 MP Quad burst, any memory
    for(uint64_t avail:std::vector<uint64_t>{0,1024*MB,3060*MB,6656*MB,8192*MB,16384*MB}){
        const uint64_t a201=avail,a50=avail; // both above kHybridClassicPixels
        assert(hybridMosaicNativeBudget(4,16320,12288,16,false,tune,a201,report)==oldNative(4,16320,12288,16,false,tune.mosaicFrames,avail));
        assert(hybridMosaicNativeBudget(30,8160,6144,4,false,tune,a50,report)==oldNative(30,8160,6144,4,false,tune.mosaicFrames,avail));
        assert(hybridMosaicNativeBudget(30,16320,12288,16,true,tune,a201,report)==oldNative(30,16320,12288,16,true,tune.mosaicFrames,avail));
        assert(hybridMosaicSplitBudget(30,8160,6144,4,tune,a50,report)==oldSplit(8160,6144,4,tune.mosaicFrames,avail));
        assert(hybridMosaicSplitBudget(12,16320,12288,16,tune,a201,report)==oldSplit(16320,12288,16,tune.mosaicFrames,avail));
    }
    for(const auto& l:lines)assert(l.find("HYBRID SERIES")==std::string::npos);
    // a default 200 MP shot (4 frames): merged whole, said so
    lines.clear();
    assert(hybridMosaicNativeBudget(4,16320,12288,16,false,tune,6656*MB,report)==4);
    assert(lines.back().find("merges whole")!=std::string::npos);
    // P190: 8 frames without the key keep 4 - no longer called «whole»
    lines.clear();
    assert(hybridMosaicNativeBudget(8,16320,12288,16,false,tune,6656*MB,report)==4);
    assert(lines.back().find("keeps at least 4 frames")!=std::string::npos&&lines.back().find("whole")==std::string::npos);
    // seriesFrames 8 at MemAvailable 6.5 GB: all 8 (7 N + the ultrashort)
    tune.seriesFrames=8;lines.clear();
    assert(hybridMosaicNativeBudget(8,16320,12288,16,false,tune,6656*MB,report)==8);
    assert(lines.back()=="HYBRID SERIES: budget 4 -> 8 (the app sized the series)");
    // a 50 MP series of 44 frames above mosaicFrames (30) and its memory budget
    tune.seriesFrames=44;
    assert(hybridMosaicNativeBudget(44,8160,6144,4,false,tune,3060*MB,report)==44);
    // the split holds kHybridGpuFrames / b^2 frames: Tetra 8 of 12, cut and said so
    tune.seriesFrames=12;lines.clear();
    const int split=hybridMosaicSplitBudget(12,16320,12288,16,tune,6656*MB,report);
    assert(split==8);
    hybridSeriesCut(tune,split,12,"split holds "+std::to_string(kHybridGpuFrames/16)+" frames",report);
    assert(lines.back()=="HYBRID SERIES CUT: 8 of 12 (split holds 8 frames)");
    lines.clear();hybridSeriesCut(tune,12,12,"memory",report);assert(lines.empty());
    HybridTuning none;hybridSeriesCut(none,2,12,"memory",report);assert(lines.empty());
    // the line the app parses (ScamHybridBurst.notice)
    HybridInput in;in.frames.resize(8);
    for(auto& f:in.frames)f.role=kRoleNormal;
    in.frames[7].role=kRoleUltrashort;
    tune.seriesFrames=8;
    hybridSeriesMerged(tune,in,7,7,report);
    assert(lines.back()=="HYBRID SERIES: merged 7 of 8 sent (N frames 7 of 7)");
    lines.clear();hybridSeriesMerged(none,in,7,7,report);assert(lines.empty());
    // the tuning key
    const char* temp=std::getenv("TMPDIR");
    std::string dir=std::string(temp?temp:"/tmp")+"/scamera-series-XXXXXX";
    assert(mkdtemp(dir.data())!=nullptr);
    {std::ofstream t(dir+"/hybrid_tuning.txt");t<<"seriesFrames 20\n";}
    assert(loadHybridTuning(dir,[](const std::string&){}).seriesFrames==20);
    unlink((dir+"/hybrid_tuning.txt").c_str());rmdir(dir.c_str());
    std::printf("hybrid series check PASS\n");
    return 0;
}
