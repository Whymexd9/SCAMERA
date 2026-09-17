#include "../app/src/main/cpp/vivo-hexquad-capture.h"
#include <cassert>
#include <cstdio>
using namespace vivo_hexquad;
struct FakeNetwork {
    std::vector<float> input=std::vector<float>(288u*288u*18),output=std::vector<float>(576u*576u*3);
    float* bound=input.data();int w,h,call=0,red,fault=0;
    std::vector<int> xs,ys;NormalVst vst{100};
    FakeNetwork(int width,int height,int r):w(width),h(height),red(r),xs(origins(w)),ys(origins(h)){}
    static float truth(int x,int y,int c){return .15f+.17f*c+.0001f*x+.00008f*y;}
    static uint16_t source(int x,int y,int c){return uint16_t(std::lround(64+
        (truth(x,y,c)+.03f*std::sin(x*.037f)+.02f*std::cos(y*.051f))*(16383-64)));}
    void execute(){
        assert(input.data()==bound);int ox=xs[call%xs.size()],oy=ys[call/xs.size()];++call;
        // Real six-frame channel groups must be independently populated.
        for(int f=0;f<6;++f){int active=0;for(int c=0;c<3;++c)active+=input[(144*288+144)*18+f*3+c]>0;assert(active==1);}
        const auto forward=vst.forward();
        for(int ty=0;ty<288;++ty)for(int tx=0;tx<288;++tx){
            int sx=reflect(ox+tx-Halo,w),sy=reflect(oy+ty-Halo,h);
            int c=(sx%8<4 && sy%8<4)?0:(sx%8>=4 && sy%8>=4)?2:1;
            int px=(red&1)?w-1-sx:sx,py=(red&2)?h-1-sy:sy;
            auto raw=source(px,py,c);
            auto value=unsigned(std::lround((raw-64.f)/(16383-64)*16383.f));
            for(int ch=0;ch<3;++ch){float expected=ch==c?forward[c*Levels+value]*(1.f/65535.f):0.f;
                assert(input[(ty*288+tx)*18+ch]==expected);}
        }
        for(int y=0;y<576;++y)for(int x=0;x<576;++x)for(int c=0;c<3;++c){
            int sx=ox+x/2-Halo,sy=oy+y/2-Halo;
            float value=truth((red&1)?w-1-sx:sx,(red&2)?h-1-sy:sy,c);
            float encoded=2.f*std::sqrt(value/vst.shot+vst.offset())/vst.norm;
            if(x<64||y<64||x>=512||y>=512)encoded=10.f; // never retain halo
            output[(size_t(y)*576+x)*3+c]=encoded;
        }
        if(fault==1)output[0]=std::numeric_limits<float>::quiet_NaN(); // unused halo still fatal
        if(fault==2)output[(150*576+150)*3]=std::numeric_limits<float>::quiet_NaN();
        if(fault==3)output[(150*576+150)*3+1]=std::numeric_limits<float>::infinity();
        if(fault==4)for(int dy=0;dy<2;++dy)for(int dx=0;dx<2;++dx)for(int c=0;c<3;++c)
            output[(size_t((Halo+40)*2+dy)*576+(Halo+40)*2+dx)*3+c]=dx?1.5f:-.125f;
    }
};
static void run(int red){
    RawBurst b;b.w=400;b.h=296;b.iso=100;b.red=red;b.black=64;b.white=16383;
    std::array<std::vector<uint16_t>,6> data;
    for(int f=0;f<6;++f){data[f].resize(size_t(b.w)*b.h);for(int y=0;y<b.h;++y)for(int x=0;x<b.w;++x){
        int q=(y%8/4)*2+x%8/4,c=q==red?0:q==(red^3)?2:1;
        data[f][y*b.w+x]=FakeNetwork::source(x,y,c)+f;}
        b.raw[f]=data[f].data();}
    std::string path="/tmp/hexquad-capture-test-"+std::to_string(getpid())+".raw";
    FakeNetwork net(b.w,b.h,red);captureHex(net,b,path);
    std::ifstream file(path,std::ios::binary);std::vector<uint16_t> result(size_t(b.w)*b.h);file.read(reinterpret_cast<char*>(result.data()),result.size()*2);assert(file.gcount()==std::streamsize(result.size()*2));
    for(int y=0;y<b.h;++y)for(int x=0;x<b.w;++x){int c=bayerColor(x,y,red);float expected=64+FakeNetwork::truth(x,y,c)*(16383-64);assert(std::abs(result[y*b.w+x]-expected)<4);}
    std::remove(path.c_str());
    FakeNetwork overshoot(b.w,b.h,red);overshoot.fault=4;captureHex(overshoot,b,path);
    std::ifstream clipped(path,std::ios::binary);clipped.read(reinterpret_cast<char*>(result.data()),result.size()*2);
    assert(clipped.gcount()==std::streamsize(result.size()*2));
    for(int oy:origins(b.h))for(int ox:origins(b.w)){
        int x=ox+40,y=oy+40,px=(red&1)?b.w-1-x:x,py=(red&2)?b.h-1-y:y;
        float expected=64+.5f*(16383-64); // Stock clip -> IVST -> area2x2, not average then clip.
        assert(std::abs(result[py*b.w+px]-expected)<2);
    }
    std::remove(path.c_str());
    if(red==3)for(int fault:{1,2,3}){
        FakeNetwork broken(b.w,b.h,red);broken.fault=fault;bool rejected=false;
        try{captureHex(broken,b,path);}catch(const std::invalid_argument&){rejected=true;}
        assert(rejected);assert(access(path.c_str(),F_OK)!=0);
    }
}
static void motion(int red){
    RawBurst b;b.w=512;b.h=512;b.black=0;b.white=16383;b.red=red;
    std::vector<uint16_t> a(512*512),c(a.size());
    auto scene=[](int x,int y){return uint16_t(7500+2100*std::sin(x*.035)+1700*std::cos(y*.023)+1000*std::sin((x+y)*.047));};
    for(int y=0;y<512;++y)for(int x=0;x<512;++x){a[y*512+x]=scene(x,y);c[y*512+x]=scene(x-16,y+8);}
    b.raw[0]=a.data();b.raw[1]=c.data();Guide ga(b,0),gb(b,1);Flow flow(ga,gb);auto shift=flow.at(256,256);
    assert(std::abs(shift.x-((red&1)?-16:16))<1.1f&&std::abs(shift.y-((red&2)?8:-8))<1.1f);
}
int main(){
    for(float v:{-10.f,-.051f,0.f,.123456f,.5f,1.f,1.251f,10.f})
        assert(normalizedIvstIndex(v)==unsigned(std::max(0.f,std::min(65535.f,v*65535.f))));
    assert(normalizedIvstIndex(std::numeric_limits<float>::max())==65535);
    assert(normalizedIvstIndex(-std::numeric_limits<float>::max())==0);
    for(float v:{std::numeric_limits<float>::quiet_NaN(),std::numeric_limits<float>::infinity()}){
        bool rejected=false;try{normalizedIvstIndex(v);}catch(const std::invalid_argument&){rejected=true;}assert(rejected);
    }
    OutputStats stats;stats.add(-.125f,1,2,0);stats.add(1.5f,3,4,1);stats.add(.5f,5,6,2);
    assert(stats.count==3&&stats.below==1&&stats.above==1&&stats.outsideChart==2&&stats.firstX==1&&stats.firstChannel==0);
    for(int n:{288,296,400,576,4096}){std::vector<float> coverage(n);for(int o:origins(n))for(int i=0;i<Core&&o+i<n;++i)coverage[o+i]+=feather(i);for(float v:coverage)assert(v>0);}
    for(int red=0;red<4;++red)run(red);
    for(int red=0;red<4;++red)motion(red);
    for(int size:{8,16,288,296,4080})for(int p=-1200;p<size+1200;++p){
        int mapped=reflect(p,size);assert(mapped>=0&&mapped<size&&mapped%8==((p%8)+8)%8);
        if(p>=0&&p<size)assert(mapped==p);
    }
    std::puts("HexQuad capture: six slots, 4 CFA layouts, phase-safe halo, stock IVST saturation, NaN/Inf rejection, motion PASS");
}
