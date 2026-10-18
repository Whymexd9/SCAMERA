// P219: the frame pick of the mosaic merges (hybridMosaicPick) against the picks before P219, on random bursts: the sub-frame
// split keeps its P63 pick frame for frame (order too); the native merge with mosaicNativePick 0 keeps its single-ultrashort /
// single-bracketed pick; with mosaicNativePick 1 (default) it takes every ultrashort frame the Bento merges (bentoFrames, at most
// 3, within x1.3 of the shortest) and every bracketed frame within the budget, and its pick always holds the old one (the same N
// frames, never fewer). Speed B1: a header block 2 / 4 skips the mosaic detector (said in the log), a block 0 still measures.
#include "../app/src/main/cpp/scam-capture.h"
#include "../app/src/main/cpp/scam-hybrid.h"
#include <cassert>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <random>
#include <set>
#include <unistd.h>

using namespace scam;

// hybridReconstructMosaic's pick before P219 (P63)
static std::vector<int> oldSplit(const HybridInput& input,const HybridTuning& tune,int budget){
    const int n=int(input.frames.size());
    std::vector<int> normals;std::vector<int> usList,brList;
    for(int f=1;f<n;++f){
        const auto& fr=input.frames[f];
        if(fr.role==kRoleNormal)normals.push_back(f);
        else if(fr.role==kRoleUltrashort&&tune.bento>0)usList.push_back(f);
        else if(fr.role==kRoleBracketed&&tune.shastaEnable)brList.push_back(f);
    }
    const float t0=input.frames[0].orderMs;
    std::stable_sort(normals.begin(),normals.end(),[&](int a,int c){return std::abs(input.frames[a].orderMs-t0)<std::abs(input.frames[c].orderMs-t0);});
    std::stable_sort(usList.begin(),usList.end(),[&](int a,int c){return input.frames[a].exposure<input.frames[c].exposure;});
    std::stable_sort(brList.begin(),brList.end(),[&](int a,int c){return std::abs(input.frames[a].orderMs-t0)<std::abs(input.frames[c].orderMs-t0);});
    const int maxUs=std::clamp(tune.bentoFrames,1,3);
    int wantUs=0;
    for(int f:usList){
        if(wantUs>=maxUs)break;
        if(wantUs==0||std::abs(std::log(input.frames[f].exposure/input.frames[usList[0]].exposure))<std::log(1.3f))wantUs++;
    }
    const int wantBr=int(brList.size());
    const int extras=(wantUs>0&&budget>=4?wantUs:0)+(wantBr>0&&budget>=6?std::min(wantBr,std::max(0,budget-(wantUs>0&&budget>=4?wantUs:0)-1)):0);
    std::vector<int> pick{0};
    for(int f:normals)if(int(pick.size())<budget-extras)pick.push_back(f);
    int usAdded=0;
    for(int f:usList){
        if(usAdded>=wantUs||int(pick.size())>=budget||budget<4)break;
        if(usAdded==0||std::abs(std::log(input.frames[f].exposure/input.frames[usList[0]].exposure))<std::log(1.3f)){
            pick.push_back(f);
            usAdded++;
        }
    }
    for(int f:brList){
        if(int(pick.size())>=budget||budget<6)break;
        pick.push_back(f);
    }
    return pick;
}
// hybridReconstructMosaicNativeMerge's pick before P219
static std::vector<int> oldNative(const HybridInput& input,const HybridTuning& tune,int budget,int usFrom){
    const int n=int(input.frames.size());
    std::vector<int> normals;int us=-1,br=-1;
    for(int f=1;f<n;++f){
        const auto& fr=input.frames[f];
        if(fr.role==kRoleNormal)normals.push_back(f);
        else if(fr.role==kRoleUltrashort&&tune.bento>0&&(us<0||fr.exposure<input.frames[us].exposure))us=f;
        else if(fr.role==kRoleBracketed&&tune.shastaEnable&&(br<0||std::abs(fr.orderMs-input.frames[0].orderMs)<std::abs(input.frames[br].orderMs-input.frames[0].orderMs)))br=f;
    }
    const float t0=input.frames[0].orderMs;
    std::stable_sort(normals.begin(),normals.end(),[&](int a,int c){return std::abs(input.frames[a].orderMs-t0)<std::abs(input.frames[c].orderMs-t0);});
    const int extras=(us>=0&&budget>=usFrom?1:0)+(br>=0&&budget>=6?1:0);
    std::vector<int> pick{0};
    for(int f:normals)if(int(pick.size())<budget-extras)pick.push_back(f);
    if(us>=0&&budget>=usFrom&&int(pick.size())<budget)pick.push_back(us);
    if(br>=0&&budget>=6&&int(pick.size())<budget)pick.push_back(br);
    return pick;
}
// the native merge's call (hybridReconstructMosaicNativeMerge)
static std::vector<int> nativePick(const HybridInput& input,const HybridTuning& tune,int budget,int usFrom){
    const bool every=tune.mosaicNativePick!=0;
    return hybridMosaicPick(input,tune,budget,usFrom,every?std::clamp(tune.bentoFrames,1,3):1,every?std::numeric_limits<int>::max():1,1,1);
}
static int count(const HybridInput& in,const std::vector<int>& pick,int role){int c=0;for(int f:pick)c+=in.frames[f].role==role?1:0;return c;}

int main() {
    std::mt19937 rng(219);
    auto uni=[&](int a,int b){return std::uniform_int_distribution<int>(a,b)(rng);};
    long cases=0,grown=0;
    for(int it=0;it<200000;++it){
        HybridInput in;
        const int nN=uni(0,30),nU=uni(0,4),nL=uni(0,5);
        in.frames.resize(size_t(1+nN+nU+nL));
        // the app's order: ring N (before the press, negative times), then after the shutter ultrashort, then bracketed; shuffled
        // sometimes, with ties in time and exposure
        std::vector<int> roles;for(int i=0;i<nN;++i)roles.push_back(kRoleNormal);for(int i=0;i<nU;++i)roles.push_back(kRoleUltrashort);
        for(int i=0;i<nL;++i)roles.push_back(kRoleBracketed);
        if(uni(0,3)==0)std::shuffle(roles.begin(),roles.end(),rng);
        for(size_t k=1;k<in.frames.size();++k){
            auto& f=in.frames[k];
            f.role=roles[k-1];
            f.orderMs=uni(0,4)==0?float(uni(-3,3))*40.f:float(int(k)-nN)*41.7f+float(uni(-5,5));
            f.exposure=f.role==kRoleUltrashort?(uni(0,3)==0?1.f/16:uni(0,4)==0?0.125f*1.25f:0.125f):f.role==kRoleBracketed?2.f:1.f;
        }
        HybridTuning tune;
        tune.bento=uni(0,5)==0?0:1;tune.shastaEnable=uni(0,5)==0?0:1;tune.bentoFrames=uni(0,5);
        const int budget=uni(1,40),usFrom=uni(0,1)?3:4;
        // the split: unchanged
        constexpr int kAll=std::numeric_limits<int>::max();
        assert(hybridMosaicPick(in,tune,budget,4,std::clamp(tune.bentoFrames,1,3),kAll,kAll,kAll)==oldSplit(in,tune,budget));
        // native, mosaicNativePick 0: the pick before P219, frame for frame
        tune.mosaicNativePick=0;
        const std::vector<int> before=oldNative(in,tune,budget,usFrom);
        assert(nativePick(in,tune,budget,usFrom)==before);
        // native, mosaicNativePick 1
        tune.mosaicNativePick=1;
        const std::vector<int> now=nativePick(in,tune,budget,usFrom);
        assert(int(now.size())<=std::max(1,budget));
        assert(now[0]==0);
        const std::set<int> nowSet(now.begin(),now.end()),beforeSet(before.begin(),before.end());
        assert(nowSet.size()==now.size()); // no frame twice
        for(int f:before)assert(nowSet.count(f)); // the old pick is inside
        assert(count(in,now,kRoleNormal)==count(in,before,kRoleNormal)); // the same N frames
        // the order: base, N, ultrashort, bracketed
        for(size_t k=2;k<now.size();++k){
            auto rank=[&](int f){const int r=in.frames[f].role;return r==kRoleNormal?0:r==kRoleUltrashort?1:2;};
            assert(rank(now[k-1])<=rank(now[k]));
        }
        // room for the whole burst: every ultrashort frame of the shortest exposure (up to bentoFrames, 3) and every bracketed one
        if(budget>=int(in.frames.size())&&budget>=6){
            int sameUs=0;float shortest=1e9f;
            for(const auto& f:in.frames)if(f.role==kRoleUltrashort)shortest=std::min(shortest,f.exposure);
            for(const auto& f:in.frames)if(f.role==kRoleUltrashort&&std::abs(std::log(f.exposure/shortest))<std::log(1.3f))sameUs++;
            assert(count(in,now,kRoleUltrashort)==(tune.bento>0?std::min(sameUs,std::clamp(tune.bentoFrames,1,3)):0));
            assert(count(in,now,kRoleBracketed)==(tune.shastaEnable?nL:0));
            assert(count(in,now,kRoleNormal)==1+nN); // the base and every N frame
        }
        grown+=now.size()>before.size()?1:0;
        ++cases;
    }
    assert(grown>cases/4);
    // the owner's FULLRES shot (P204: 12 N + 3 ultrashort + 3 bracketed, «HYBRID SERIES CUT: 14 of 18 (frame rules)»): all 18
    {
        auto shot=[](int longs){ // the base and 11 N from the ring, then 3 ultrashort and `longs` bracketed after the shutter
            HybridInput in;in.frames.resize(size_t(15+longs));
            for(int k=1;k<15+longs;++k){
                auto& f=in.frames[k];
                f.role=k<12?kRoleNormal:k<15?kRoleUltrashort:kRoleBracketed;
                f.exposure=f.role==kRoleUltrashort?0.125f:f.role==kRoleBracketed?2.f:1.f;
                f.orderMs=k<12?-41.7f*float(k):41.7f*float(k-11);
            }
            return in;
        };
        HybridInput in=shot(3);
        HybridTuning tune;tune.bentoFrames=3;
        assert(oldNative(in,tune,30,4).size()==14);
        const std::vector<int> pick=nativePick(in,tune,30,4);
        assert(pick.size()==18&&count(in,pick,kRoleUltrashort)==3&&count(in,pick,kRoleBracketed)==3);
        // P219's FULLRES plan (one long frame): 12 N + 3 ultrashort + 1 bracketed, all merged
        assert(nativePick(shot(1),tune,30,4).size()==16);
        // a memory budget of 14: the old pick's 12 N + 1 + 1, the other ultrashort frames left out
        const std::vector<int> cut=nativePick(in,tune,14,4);
        assert(cut==oldNative(in,tune,14,4));
        assert(count(in,cut,kRoleNormal)==12&&count(in,cut,kRoleUltrashort)==1&&count(in,cut,kRoleBracketed)==1);
        // budget 16: two more, the second and third ultrashort frames first
        const std::vector<int> two=nativePick(in,tune,16,4);
        assert(count(in,two,kRoleNormal)==12&&count(in,two,kRoleUltrashort)==3&&count(in,two,kRoleBracketed)==1);
    }
    // the tuning key
    {
        const char* temp=std::getenv("TMPDIR");
        std::string dir=std::string(temp?temp:"/tmp")+"/scamera-pick-XXXXXX";
        assert(mkdtemp(dir.data())!=nullptr);
        {std::ofstream t(dir+"/hybrid_tuning.txt");t<<"mosaicNativePick 0\n";}
        assert(HybridTuning().mosaicNativePick==1);
        assert(loadHybridTuning(dir,[](const std::string&){}).mosaicNativePick==0);
        unlink((dir+"/hybrid_tuning.txt").c_str());rmdir(dir.c_str());
    }
    // speed B1: the header's block is used without the detector; block 0 (unknown) is still measured
    {
        const int w=256,h=256;
        std::vector<uint16_t> raw(size_t(w)*h);
        std::mt19937 noise(7);std::normal_distribution<float> g(0.f,3.f);
        for(int y=0;y<h;++y)for(int x=0;x<w;++x){ // Quad RGGB: 2x2 blocks of one colour
            const int p=(((y>>1)&1)<<1)|((x>>1)&1);
            raw[size_t(y)*w+x]=uint16_t(std::lround(64.f+(p==0?300.f:p==3?200.f:500.f)+g(noise)));
        }
        HybridInput in;in.w=w;in.h=h;in.white=1023;in.black={64,64,64,64};in.frames.resize(1);in.frames[0].raw=raw.data();
        HybridTuning tune;
        std::vector<std::string> lines;
        const auto report=[&](const std::string& line){lines.push_back(line);};
        for(int header:{2,4}){
            in.mosaic=header;lines.clear();
            assert(hybridMosaicBlock(in,tune,report)==header);
            assert(lines.size()==1&&lines[0]=="HYBRID MOSAIC: header block "+std::to_string(header)+", detection skipped (the app's block)");
        }
        in.mosaic=0;lines.clear();
        assert(hybridMosaicBlock(in,tune,report)==2);
        assert(lines.size()==1&&lines[0].find("HYBRID MOSAIC: header block 0, measured block 2 (confident")==0);
        tune.mosaicBlock=4;in.mosaic=2;lines.clear();
        assert(hybridMosaicBlock(in,tune,report)==4&&lines[0]=="HYBRID MOSAIC: block 4 forced by tuning");
    }
    std::printf("mosaic pick check PASS (%ld random bursts, %ld with more frames than before P219)\n",cases,grown);
    return 0;
}
