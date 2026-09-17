#include "../app/src/main/cpp/vivo-hexquad-capture.h"
#include <cassert>
#include <cstdio>
using namespace vivo_hexquad;
struct FakeNetwork {
    std::vector<float> input=std::vector<float>(288u*288u*18),output=std::vector<float>(576u*576u*3);
    float* bound=input.data();int w,h,call=0,red;bool bad=false;
    std::vector<int> xs,ys;NormalVst vst{100};
    FakeNetwork(int width,int height,int r):w(width),h(height),red(r),xs(origins(w)),ys(origins(h)){}
    static float truth(int x,int y,int c){return .15f+.17f*c+.0001f*x+.00008f*y;}
    void execute(){
        assert(input.data()==bound);int ox=xs[call%xs.size()],oy=ys[call/xs.size()];++call;
        // Real six-frame channel groups must be independently populated.
        for(int f=0;f<6;++f){int active=0;for(int c=0;c<3;++c)active+=input[(144*288+144)*18+f*3+c]>0;assert(active==1);}
        for(int y=0;y<576;++y)for(int x=0;x<576;++x)for(int c=0;c<3;++c){
            float value=truth(ox+x/2-Halo,oy+y/2-Halo,c);
            float encoded=2.f*std::sqrt(value/vst.shot+vst.offset())/vst.norm;
            if(x<64||y<64||x>=512||y>=512)encoded=10.f; // never retain halo
            output[(size_t(y)*576+x)*3+c]=encoded;
        }
        if(bad)output[(150*576+150)*3]=2.f;
    }
};
static void run(int red){
    RawBurst b;b.w=400;b.h=296;b.iso=100;b.red=red;b.black=64;b.white=16383;
    std::array<std::vector<uint16_t>,6> data;
    for(int f=0;f<6;++f){data[f].resize(size_t(b.w)*b.h);for(int y=0;y<b.h;++y)for(int x=0;x<b.w;++x){
        data[f][y*b.w+x]=uint16_t(64+(7000+f)*(1+.02*std::sin(x*.013)+.03*std::cos(y*.02)));}
        b.raw[f]=data[f].data();}
    std::string path="/tmp/hexquad-capture-test-"+std::to_string(getpid())+".raw";
    FakeNetwork net(b.w,b.h,red);captureHex(net,b,path);
    std::ifstream file(path,std::ios::binary);std::vector<uint16_t> result(size_t(b.w)*b.h);file.read(reinterpret_cast<char*>(result.data()),result.size()*2);assert(file.gcount()==std::streamsize(result.size()*2));
    for(int y=0;y<b.h;++y)for(int x=0;x<b.w;++x){int c=bayerColor(x,y,red);float expected=64+FakeNetwork::truth(x,y,c)*(16383-64);assert(std::abs(result[y*b.w+x]-expected)<4);}
    std::remove(path.c_str());
    FakeNetwork broken(b.w,b.h,red);broken.bad=true;bool rejected=false;
    try{captureHex(broken,b,path);}catch(const std::invalid_argument&){rejected=true;}assert(rejected);assert(access(path.c_str(),F_OK)!=0);
}
static void motion(){
    RawBurst b;b.w=512;b.h=512;b.black=0;b.white=16383;
    std::vector<uint16_t> a(512*512),c(a.size());
    auto scene=[](int x,int y){return uint16_t(7500+2100*std::sin(x*.035)+1700*std::cos(y*.023)+1000*std::sin((x+y)*.047));};
    for(int y=0;y<512;++y)for(int x=0;x<512;++x){a[y*512+x]=scene(x,y);c[y*512+x]=scene(x-16,y+8);}
    b.raw[0]=a.data();b.raw[1]=c.data();Guide ga(b,0),gb(b,1);Flow flow(ga,gb);auto shift=flow.at(256,256);
    assert(std::abs(shift.x-16)<1.1f&&std::abs(shift.y+8)<1.1f);
}
int main(){
    for(int n:{288,296,400,576,4096}){std::vector<float> coverage(n);for(int o:origins(n))for(int i=0;i<Core&&o+i<n;++i)coverage[o+i]+=feather(i);for(float v:coverage)assert(v>0);}
    for(int red=0;red<4;++red)run(red);
    motion();
    for(int p=-1200;p<1200;++p)assert(reflect(p,296)>=0&&reflect(p,296)<296);
    std::puts("HexQuad capture: six slots, 4 CFA layouts, tiling/halo/seams, output rejection, motion PASS");
}
