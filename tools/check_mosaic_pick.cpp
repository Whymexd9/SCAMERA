// P219: the frame pick of the mosaic merges (hybridMosaicPick) against the picks before P219, on random bursts: the sub-frame
// split keeps its P63 pick frame for frame (order too); the native merge with mosaicNativePick 0 keeps its single-ultrashort /
// single-bracketed pick; with mosaicNativePick 1 (default) it takes every ultrashort frame the Bento merges (bentoFrames, at most
// 3, within x1.3 of the shortest) and every bracketed frame within the budget, and its pick holds the old ultrashort and bracketed
// frames. A budget short of the burst: the N frames farthest in time give their slots to the extra ultrashort frames that find no
// free slot only when that lowers the area-weighted noise (the base's clipped share f: f (1 - sqrt(u / (u + 1))) > (1 - f)
// (sqrt(m / (m - 1)) - 1)), never below usFrom - 2 N frames, never to a bracketed frame; else the N frames stay those of the old
// pick. hybridMosaicBaseClipped is bentoBase's clipped share on the base binned without the site-class gains. Speed B1: a header
// block 2 / 4 skips the mosaic detector (said in the log), a block 0 still measures.
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
// the native merge's pick (hybridReconstructMosaicNativeMerge) for a base clipping `clipped` of Bento's cells
static std::vector<int> nativePick(const HybridInput& input,const HybridTuning& tune,int budget,int usFrom,double clipped=0.0){
    return hybridMosaicNativePick(input,tune,budget,usFrom,clipped);
}
static int count(const HybridInput& in,const std::vector<int>& pick,int role){int c=0;for(int f:pick)c+=in.frames[f].role==role?1:0;return c;}
// the ultrashort frames Bento merges: the shortest and those within x1.3 of it, up to bentoFrames (at most 3)
static int eligibleUs(const HybridInput& in,const HybridTuning& tune){
    if(tune.bento<=0)return 0;
    int same=0;float shortest=1e9f;
    for(size_t k=1;k<in.frames.size();++k)if(in.frames[k].role==kRoleUltrashort)shortest=std::min(shortest,in.frames[k].exposure);
    for(size_t k=1;k<in.frames.size();++k)
        if(in.frames[k].role==kRoleUltrashort&&std::abs(std::log(in.frames[k].exposure/shortest))<std::log(1.3f))same++;
    return std::min(same,std::clamp(tune.bentoFrames,1,3));
}

int main() {
    std::mt19937 rng(219);
    auto uni=[&](int a,int b){return std::uniform_int_distribution<int>(a,b)(rng);};
    long cases=0,grown=0,gaveWay=0;
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
        // native, mosaicNativePick 0: the pick before P219, frame for frame (with or without highlights)
        tune.mosaicNativePick=0;
        const std::vector<int> before=oldNative(in,tune,budget,usFrom);
        assert(nativePick(in,tune,budget,usFrom,0.0)==before&&nativePick(in,tune,budget,usFrom,1.0)==before);
        // native, mosaicNativePick 1
        tune.mosaicNativePick=1;
        const int keepN=usFrom-2,oldN=count(in,before,kRoleNormal); // oldN: the base and its N frames
        for(bool usAhead:{false,true}){ // a base without clipping, a base clipped all over
            const std::vector<int> now=nativePick(in,tune,budget,usFrom,usAhead?1.0:0.0);
            assert(int(now.size())<=std::max(1,budget));
            assert(now[0]==0);
            const std::set<int> nowSet(now.begin(),now.end());
            assert(nowSet.size()==now.size()); // no frame twice
            for(int f:before)if(f==0||in.frames[f].role!=kRoleNormal)assert(nowSet.count(f)); // the old ultrashort / bracketed frames
            for(int f:now)if(in.frames[f].role==kRoleNormal)assert(std::find(before.begin(),before.end(),f)!=before.end()); // N: old ones
            const int nowN=count(in,now,kRoleNormal),extraUs=count(in,now,kRoleUltrashort)-count(in,before,kRoleUltrashort);
            const int freeBefore=std::max(1,budget)-int(before.size());
            if(!usAhead)assert(nowN==oldN); // no highlights: the same N frames as before
            else {
                // the N frames give way only to the extra ultrashort frames that found no free slot, never below keepN
                assert(oldN-nowN==std::max(0,extraUs-freeBefore));
                assert(nowN>=std::min(oldN,1+keepN));
                // fewer ultrashort frames than Bento merges (with room for them): the budget is full and N stand at the floor
                if(budget>=usFrom&&count(in,now,kRoleUltrashort)<eligibleUs(in,tune))
                    assert(int(now.size())==budget&&nowN<=1+keepN);
            }
            // the order: base, N, ultrashort, bracketed
            for(size_t k=2;k<now.size();++k){
                auto rank=[&](int f){const int r=in.frames[f].role;return r==kRoleNormal?0:r==kRoleUltrashort?1:2;};
                assert(rank(now[k-1])<=rank(now[k]));
            }
            // room for the whole burst: every ultrashort frame of the shortest exposure (up to bentoFrames, 3) and every bracketed one
            if(budget>=int(in.frames.size())&&budget>=6){
                assert(count(in,now,kRoleUltrashort)==eligibleUs(in,tune));
                assert(count(in,now,kRoleBracketed)==(tune.shastaEnable?nL:0));
                assert(count(in,now,kRoleNormal)==1+nN); // the base and every N frame
            }
            if(usAhead)grown+=now.size()>before.size()?1:0;
            if(usAhead&&nowN<oldN)++gaveWay;
        }
        ++cases;
    }
    assert(grown>cases/4&&gaveWay>cases/20);
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
        // a memory budget of 14: the old pick's 12 N + 1 + 1 for the shot itself (0.5 % of its cells clipped: an N frame less would
        // raise the noise of 99.5 % of the frame more than a second ultrashort frame lowers it in the rest); a base clipped over 13 %
        // gives the second ultrashort frame an N slot (11 N + 2 + 1), over 21 % the third (10 N + 3 + 1, as the split and the plain
        // Bayer burst keep their extras)
        const std::vector<int> cut=nativePick(in,tune,14,4,0.25),old14=oldNative(in,tune,14,4);
        assert(count(in,cut,kRoleNormal)==10&&count(in,cut,kRoleUltrashort)==3&&count(in,cut,kRoleBracketed)==1);
        assert(std::equal(cut.begin(),cut.begin()+10,old14.begin())); // the base and the 9 N frames closest in time
        const std::vector<int> mid=nativePick(in,tune,14,4,0.15);
        assert(count(in,mid,kRoleNormal)==11&&count(in,mid,kRoleUltrashort)==2&&count(in,mid,kRoleBracketed)==1);
        assert(nativePick(in,tune,14,4,0.13)==old14&&nativePick(in,tune,14,4,0.14)==mid&&nativePick(in,tune,14,4,0.20)==mid);
        assert(nativePick(in,tune,14,4,0.22)==cut);
        for(double f:{0.0,0.005,0.05})assert(nativePick(in,tune,14,4,f)==old14);
        assert(count(in,old14,kRoleNormal)==12&&count(in,old14,kRoleUltrashort)==1&&count(in,old14,kRoleBracketed)==1);
        // budget 16: room for all 12 N + 3 + 1 either way
        for(double f:{0.0,0.005,1.0}){
            const std::vector<int> two=nativePick(in,tune,16,4,f);
            assert(count(in,two,kRoleNormal)==12&&count(in,two,kRoleUltrashort)==3&&count(in,two,kRoleBracketed)==1);
        }
    }
    // v10_1 / v10_2 (35 frames: 28 N, 5 bracketed, 2 ultrashort at 1/16) in the 30 frames of mosaicFrames: the old 28 N + 1 + 1
    // up to 5.9 % of the cells clipped (the night bursts: 0 / 0.004 %), 27 N + 2 + 1 above
    {
        HybridInput in;in.frames.resize(35);
        for(int k=1;k<35;++k){
            auto& f=in.frames[k];
            f.role=k<28?kRoleNormal:k<33?kRoleBracketed:kRoleUltrashort;
            f.exposure=f.role==kRoleUltrashort?0.0625f:f.role==kRoleBracketed?1.857f:1.f;
            f.orderMs=k<28?-33.3f*float(k):33.3f*float(k-27);
        }
        HybridTuning tune;tune.bentoFrames=3;
        const std::vector<int> lit=nativePick(in,tune,30,4,0.06),night=nativePick(in,tune,30,4,0.058);
        assert(lit.size()==30&&count(in,lit,kRoleNormal)==27&&count(in,lit,kRoleUltrashort)==2&&count(in,lit,kRoleBracketed)==1);
        assert(night==oldNative(in,tune,30,4)&&count(in,night,kRoleNormal)==28&&count(in,night,kRoleUltrashort)==1);
    }
    // short budgets keep the N frames the first ultrashort frame needs: two (one for a huge burst, usFrom 3), the base aside (a base
    // clipped all over)
    {
        HybridInput in;in.frames.resize(15);
        for(int k=1;k<15;++k){
            auto& f=in.frames[k];
            f.role=k<11?kRoleNormal:k<14?kRoleUltrashort:kRoleBracketed;
            f.exposure=f.role==kRoleUltrashort?0.125f:f.role==kRoleBracketed?2.f:1.f;
            f.orderMs=k<11?-41.7f*float(k):41.7f*float(k-10);
        }
        HybridTuning tune;tune.bentoFrames=3;
        auto has=[&](const std::vector<int>& p,int nN,int nU,int nL){
            return count(in,p,kRoleNormal)==nN&&count(in,p,kRoleUltrashort)==nU&&count(in,p,kRoleBracketed)==nL;
        };
        assert(has(nativePick(in,tune,4,4,1.0),3,1,0));  // base + 2 N + 1 (the split would take base + 3 ultrashort)
        assert(has(nativePick(in,tune,5,4,1.0),3,2,0));
        assert(has(nativePick(in,tune,6,4,1.0),3,2,1));
        assert(has(nativePick(in,tune,7,4,1.0),3,3,1));
        assert(has(nativePick(in,tune,3,3,1.0),2,1,0));  // huge: base + 1 N + 1
        assert(has(nativePick(in,tune,4,3,1.0),2,2,0));
    }
    // the base scan (hybridMosaicBaseClipped): Bento's clipped share on the base binned as the native merge bins it (gains 1): a
    // block with a clipped site (white - 1) or a mean at bentoHighlight makes its 2x2 cell clipped (Quad 128 x 128: 1024 cells;
    // 1024 x 1024: 65536, Bento's gate 26 of them; Tetra 1024 x 1024: 16384); on random bases (any CFA, black per phase) it is
    // bentoBase's own clippedFraction of that binned base
    {
        HybridTuning tune;
        auto gate=[&](const HybridInput& in,int b){return hybridMosaicBaseClipped(in,tune,b)>tune.bentoMinClipped;};
        {
            const int w=128,h=128;
            std::vector<uint16_t> raw(size_t(w)*h,300);
            HybridInput in;in.w=w;in.h=h;in.white=1023;in.black={64,64,64,64};in.frames.resize(1);in.frames[0].raw=raw.data();
            assert(!gate(in,2));
            raw[size_t(77)*w+5]=1021;assert(!gate(in,2)); // one bright site: its block mean is low
            raw[size_t(77)*w+5]=1022;assert(gate(in,2));  // clipped: the block is white
            raw[size_t(77)*w+5]=300;
            for(int c=0;c<2;++c)for(int a=0;a<2;++a)raw[size_t(40+c)*w+20+a]=1003; // a block at 0.979 of the range
            assert(!gate(in,2));
            for(int c=0;c<2;++c)for(int a=0;a<2;++a)raw[size_t(40+c)*w+20+a]=1005; // 0.981
            assert(gate(in,2));
        }
        {
            const int w=1024,h=1024;
            std::vector<uint16_t> raw(size_t(w)*h,300);
            HybridInput in;in.w=w;in.h=h;in.white=1023;in.black={64,64,64,64};in.frames.resize(1);in.frames[0].raw=raw.data();
            for(int k=0;k<25;++k)raw[size_t(37*k+11)*w+29*k+3]=1023; // 25 hot sites: within the gate's 0.039 % of 65536 cells
            assert(!gate(in,2));
            raw[size_t(1000)*w+1000]=1023;assert(gate(in,2));
            assert(gate(in,4)); // Tetra: 16384 cells, 7 needed
        }
        const int w=512,h=512;
        std::vector<uint16_t> raw(size_t(w)*h),binned;
        HybridInput in;in.w=w;in.h=h;in.white=1023;in.frames.resize(1);in.frames[0].raw=raw.data();
        std::mt19937 r2(4219);
        long pass=0,fail=0;
        for(int it=0;it<300;++it){
            const int b=it%3?2:4,vw=w/b,vh=h/b;
            in.cfa=it%4;
            for(float& bl:in.black)bl=float(std::uniform_int_distribution<int>(60,68)(r2));
            std::fill(raw.begin(),raw.end(),uint16_t(std::uniform_int_distribution<int>(100,700)(r2)));
            const int spots=std::uniform_int_distribution<int>(0,40)(r2);
            for(int k=0;k<spots;++k){ // bright spots: one site, a block, or a patch, clipped or just below
                const int x0=std::uniform_int_distribution<int>(0,w-9)(r2),y0=std::uniform_int_distribution<int>(0,h-9)(r2);
                const int size=std::uniform_int_distribution<int>(1,8)(r2),v=std::uniform_int_distribution<int>(990,1023)(r2);
                for(int y=y0;y<y0+size;++y)for(int x=x0;x<x0+size;++x)raw[size_t(y)*w+x]=uint16_t(v);
            }
            binned.assign(size_t(vw)*vh,0); // the native merge's binning with gains 1
            for(int J=0;J<vh;++J)for(int I=0;I<vw;++I){
                const float vb=in.black[((J&1)<<1)|(I&1)];
                double acc=0;bool clipped=false;
                for(int c=0;c<b;++c)for(int a=0;a<b;++a){
                    const int x=b*I+a,y=b*J+c;
                    const float v=float(raw[size_t(y)*w+x]),sb=in.black[((y&1)<<1)|(x&1)];
                    acc+=v>=in.white-1.f?in.white:std::clamp(vb+(v-sb),0.f,in.white);clipped|=v>=in.white-1.f;
                }
                binned[size_t(J)*vw+I]=clipped?uint16_t(std::lround(in.white)):uint16_t(std::lround(acc/(b*b)));
            }
            Burst bb;bb.w=vw;bb.h=vh;bb.cfa=in.cfa;bb.white=in.white;bb.black=in.black;bb.canonicalRggb=true;bb.raw[0]=binned.data();
            HybridTuning t1=tune;t1.bento=1;
            const double share=bentoBase(bb,t1).clippedFraction;
            assert(hybridMosaicBaseClipped(in,tune,b)==share);
            (share>tune.bentoMinClipped?pass:fail)++;
        }
        assert(pass>30&&fail>30);
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
    // the chroma context built ahead only for a result the median takes on the GPU (16.8 MP): a 12 MP Quad / Tetra result and a
    // 50 MP Tetra T2 one (a quarter), not a 50 MP Quad result nor a 12 MP burst on the natively merged 2x grid
    assert(chromaGpuPossible(int64_t(4096)*3072)&&chromaGpuPossible(int64_t(8160)*6144/4)&&chromaGpuPossible(kChromaGpuPixels));
    assert(!chromaGpuPossible(int64_t(8192)*6144)&&!chromaGpuPossible(int64_t(4096)*3072*4)&&!chromaGpuPossible(kChromaGpuPixels+1));
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
    std::printf("mosaic pick check PASS (%ld random bursts, with highlights %ld with more frames than before P219, %ld with N frames"
                " given to ultrashort frames)\n",cases,grown,gaveWay);
    return 0;
}
