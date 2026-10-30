// Lab stage view: with hybridStageDir() set the merge writes stages.bin (the base and merged previews, the mean acceptance and per
// donor the rejection before / after the spread and the F6 offset) and merges exactly as without it. A burst on Mesa llvmpipe:
// the base + 3 normal donors at known offsets, donor 2 with a square that moved; its maps reject the square, the others keep it.
#include "../app/src/main/cpp/scam-capture.h"
#include "../app/src/main/cpp/scam-hybrid.h"
#include <cassert>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <map>
#include <random>
#include <unistd.h>

using namespace scam;

static double scene(double x,double y,bool moved){
    double v=0.30+0.12*std::sin(x*0.21)*std::cos(y*0.17);
    const double sx=moved?300:180; // the square: x [sx, sx + 80), y [140, 220)
    if(x>=sx&&x<sx+80&&y>=140&&y<220)v=0.85;
    return v;
}
static const double kCg[4]={0.55,1.0,1.0,0.75};
static std::vector<uint16_t> bayer(int w,int h,double dx,double dy,bool moved,unsigned seed){
    std::vector<uint16_t> out(size_t(w)*h);std::mt19937 r(seed);std::normal_distribution<double> n(0,1);
    for(int y=0;y<h;++y)for(int x=0;x<w;++x){
        const int ph=((y&1)<<1)|(x&1);
        double v=scene(x+dx,y+dy,moved)*kCg[ph];
        v+=n(r)*std::sqrt(4e-4*v+2e-6);
        out[size_t(y)*w+x]=uint16_t(std::lround(64+std::clamp(v,0.0,1.0)*(4095-64)));
    }
    return out;
}
struct Stage {std::string name;int frame,role,w,h,ch;std::vector<uint8_t> px;};
static std::vector<Stage> readStages(const std::string& path){
    std::ifstream f(path,std::ios::binary);std::vector<Stage> out;
    for(;;){
        int32_t magic=0,n=0;if(!f.read(reinterpret_cast<char*>(&magic),4))break;
        assert(magic==0x31475453);f.read(reinterpret_cast<char*>(&n),4);assert(n>0&&n<64);
        Stage s;s.name.resize(size_t(n));f.read(s.name.data(),n);
        int32_t hd[5];f.read(reinterpret_cast<char*>(hd),sizeof(hd));
        s.frame=hd[0];s.role=hd[1];s.w=hd[2];s.h=hd[3];s.ch=hd[4];
        s.px.resize(size_t(s.w)*s.h*s.ch);f.read(reinterpret_cast<char*>(s.px.data()),std::streamsize(s.px.size()));
        assert(f);out.push_back(std::move(s));
    }
    return out;
}
// the mean of a 1-channel map over cells [x0, x1) x [y0, y1) (RAW px / 2 = cells at step 1)
static double mean(const Stage& s,int x0,int x1,int y0,int y1){
    double a=0;long n=0;
    for(int y=y0;y<y1;++y)for(int x=x0;x<x1;++x){a+=s.px[size_t(y)*s.w+x];++n;}
    return a/n/255.0;
}

int main(){
    const int w=512,h=384;
    const std::vector<std::array<double,2>> d{{0,0},{1.5,0.5},{-1.0,2.0},{0.5,-1.5}};
    std::vector<std::vector<uint16_t>> raw;
    for(size_t f=0;f<d.size();++f)raw.push_back(bayer(w,h,d[f][0],d[f][1],f==2,100+unsigned(f)));
    const char* temp=std::getenv("TMPDIR");
    std::string dir=std::string(temp?temp:"/tmp")+"/scamera-stages-XXXXXX";
    assert(mkdtemp(dir.data())!=nullptr);
    auto run=[&](bool stages,std::vector<std::string>& log,int rejEdge=0){
        hybridStageDir()=stages?dir:std::string();
        HybridInput in;in.w=w;in.h=h;in.cfa=0;in.white=4095;in.black.fill(64);in.mosaic=1;
        std::map<const uint16_t*,int> who;
        for(size_t f=0;f<d.size();++f){
            HybridFrame fr;fr.raw=raw[f].data();fr.role=kRoleNormal;fr.exposure=1;fr.iso=800;fr.slope=4e-4f;fr.offset=2e-6f;
            fr.orderMs=float(f)*33.f;in.frames.push_back(fr);who[fr.raw]=int(f);
        }
        ScamAlignment align=[&](Burst& burst){
            std::array<BackwardHomography,7> result{};
            for(int s=1;s<7;++s){
                if(burst.raw[s]==burst.raw[0]&&burst.exposure[s]==1)continue;
                const int f=who.at(burst.raw[s]);
                result[s].h={1,0,float(-d[f][0]),0,1,float(-d[f][1]),0,0};
            }
            return result;
        };
        HybridTuning t;t.rejEdge=rejEdge;
        return hybridReconstruct(in,t,align,[&](const std::string& l){log.push_back(l);},nullptr,nullptr);
    };
    std::vector<std::string> la,lb;
    const std::vector<float> plain=run(false,la);
    assert(access((dir+"/stages.bin").c_str(),F_OK)!=0);
    const std::vector<float> staged=run(true,lb);
    hybridStageDir().clear();
    assert(plain.size()==staged.size()&&std::memcmp(plain.data(),staged.data(),plain.size()*4)==0); // the same merge
    bool said=false;for(const auto& l:lb)said|=l.rfind("HYBRID STAGES: 3 donors, 256x192",0)==0;
    if(!said)for(const auto& l:lb)std::printf("| %s\n",l.c_str());
    assert(said);
    const auto st=readStages(dir+"/stages.bin");
    unlink((dir+"/stages.bin").c_str());
    std::map<std::string,int> count;std::map<int,const Stage*> raw1,robust;
    for(const auto& s:st){
        ++count[s.name];assert(s.w==256&&s.h==192);
        if(s.name=="base"||s.name=="merged")assert(s.ch==3&&s.frame==-1);
        if(s.name=="raw"){assert(s.ch==1&&s.role==kRoleNormal);raw1[s.frame]=&s;}
        if(s.name=="robust")robust[s.frame]=&s;
    }
    assert(count["base"]==1&&count["merged"]==1&&count["accepted"]==1&&count["raw"]==3&&count["robust"]==3);
    assert(count["flow"]==0||count["flow"]==3);
    std::printf("records: %zu (F6 offset maps %d)\n",st.size(),count["flow"]);
    assert(raw1.size()==3&&robust.size()==3&&raw1.count(1)&&raw1.count(2)&&raw1.count(3));
    // the moved square: donor 2 shows it at x 300..380 instead of 180..260 (cells / 2), both places refused; donors 1 and 3 keep them
    for(int f=1;f<=3;++f){
        const double at=mean(*robust[f],95,125,75,105),away=mean(*robust[f],20,60,20,60),rawAt=mean(*raw1[f],95,125,75,105);
        std::printf("donor %d: robust at the square %.3f, elsewhere %.3f; raw at the square %.3f\n",f,at,away,rawAt);
        assert(away>0.6);
        if(f==2)assert(at<0.15&&rawAt<0.15&&mean(*robust[f],155,185,75,105)<0.15);
        else assert(at>0.6);
    }
    // the base preview: the square is light, the background darker
    const Stage* base=nullptr;for(const auto& s:st)if(s.name=="base")base=&s;
    auto g=[&](int x,int y){return base->px[(size_t(y)*base->w+x)*3+1];};
    assert(g(110,90)>g(30,30)+40);
    // P418 rejEdge: a donor cell beyond its frame (the donors' shifts of 0.5 - 2 px leave the outer cells without a sample on one
    // side each) is weight 0 as before but not spread by the dilation: the cells beside it keep the donor; 16 px in, the same merge
    {
        std::vector<std::string> lc,ld;
        hybridStageDir()=dir;
        const std::vector<float> off=run(true,lc,0);
        const auto so=readStages(dir+"/stages.bin");unlink((dir+"/stages.bin").c_str());
        const std::vector<float> on=run(true,ld,1);
        const auto sn=readStages(dir+"/stages.bin");unlink((dir+"/stages.bin").c_str());rmdir(dir.c_str());
        hybridStageDir().clear();
        assert(off.size()==on.size());
        const int W=w/2,Hh=h/2; // output pixels = cells x 2 at this grid? the merge's output is w x h
        const int ow=int(std::lround(std::sqrt(double(off.size()/3)*w/h))),oh=int(off.size()/3/size_t(ow));
        double inner=0;
        for(int y=16;y<oh-16;++y)for(int x=16;x<ow-16;++x)for(int c=0;c<3;++c)
            inner=std::max(inner,double(std::abs(off[(size_t(y)*ow+x)*3+c]-on[(size_t(y)*ow+x)*3+c])));
        std::printf("rejEdge: output %dx%d, max change 16 px from the edges %.2e\n",ow,oh,inner);
        assert(inner<1e-6);
        auto find=[](const std::vector<Stage>& v,const char* name,int frame)->const Stage*{
            for(const auto& s:v)if(s.name==name&&s.frame==frame)return &s;return nullptr;};
        int sides=0;
        for(int f=1;f<=3;++f){
            const Stage *ro=find(so,"robust",f),*rn=find(sn,"robust",f),*rw=find(so,"raw",f);
            assert(ro&&rn&&rw);
            struct Side{const char* n;int x0,x1,y0,y1,ix0,ix1,iy0,iy1;};
            const Side sd[4]={{"left",0,1,40,150,2,4,40,150},{"right",W-1,W,40,150,W-4,W-2,40,150},
                              {"top",40,210,0,1,40,210,2,4},{"bottom",40,210,Hh-1,Hh,40,210,Hh-4,Hh-2}};
            for(const auto& q:sd){
                if(mean(*rw,q.x0,q.x1,q.y0,q.y1)>0.2)continue; // a sample there: not this side
                const double a=mean(*ro,q.ix0,q.ix1,q.iy0,q.iy1),b=mean(*rn,q.ix0,q.ix1,q.iy0,q.iy1);
                std::printf("donor %d %s: cells 2-3 from the edge robust %.3f -> %.3f\n",f,q.n,a,b);
                assert(b>0.8&&b>a+0.1);++sides;
            }
        }
        assert(sides>=2);
    }
    std::printf("hybrid stages check PASS\n");
    return 0;
}
