#include "../app/src/main/cpp/vivo-hexquad-capture.h"
#include <cassert>
#include <cstdio>
using namespace vivo_hexquad;
static void near(float a,float b,float eps=2.e-6f){assert(std::abs(a-b)<eps);}
static void blend(){
    Rgb wp{{.48f,1.f,.71f}},a{{.17f,.39f,.22f}},n{{.20f,.34f,.28f}};
    assert(mixDetail(a,n,1,1,wp)==n);
    auto zero=mixDetail(a,n,0,0,wp);for(int c=0;c<3;++c)near(zero[c],a[c]);
    for(float l:{0.f,.2f,.5f,1.f})for(float chroma:{0.f,.3f,.8f,1.f}){
        auto v=mixDetail(a,n,l,chroma,wp);Rgb av{},nv{},vv{};
        for(int c=0;c<3;++c){av[c]=a[c]/wp[c];nv[c]=n[c]/wp[c];vv[c]=v[c]/wp[c];}
        float ya=detailLuma(av),yn=detailLuma(nv),yv=detailLuma(vv);
        near(yv,ya+(yn-ya)*l);
        for(int c=0;c<3;++c)near(vv[c]-yv,(av[c]-ya)*(1-chroma)+(nv[c]-yn)*chroma);
    }
}
struct Fixture {
    RawBurst b;std::array<std::vector<uint16_t>,6> storage;
    Fixture(int red,bool texture){
        b.w=296;b.h=304;b.red=red;b.iso=100;b.black=64;b.white=16383;b.neutral={{.55f,1.f,.72f}};
        CfaOrientation o(b.w,b.h,red);
        for(int f=0;f<6;++f){auto& s=storage[f];s.resize(size_t(b.w)*b.h);
            for(int y=0;y<b.h;++y)for(int x=0;x<b.w;++x){
                int c=b.color(x,y);float v=texture?.35f+.025f*std::sin(x*.28f)+.017f*std::cos(y*.19f):.24f+.08f*c;
                s[size_t(o.y(y))*b.w+o.x(x)]=uint16_t(std::lround(64+v*b.neutral[c]*(16383-64)));
            }b.raw[f]=s.data();
        }
    }
};
static void reference(int red){
    for(bool texture:{false,true}){
        Fixture f(red,texture);TetraDetailReference<RawBurst> ref(f.b);
        auto a=ref.tile(0,0,224),b=ref.tile(192,192,224);
        double detailEnergy=0;int count=0;
        for(auto* t:{&a,&b}){
            for(int y=std::max(0,t->y+4);y<std::min(f.b.h,t->y+t->h-4);y+=3)
                for(int x=std::max(0,t->x+4);x<std::min(f.b.w,t->x+t->w-4);x+=3){
                    Rgb v=ref.rgb(*t,x,y);int c=f.b.color(x,y);
                    near(v[c],f.b.sample(0,x,y)); // measured sites are preserved, including G
                    for(int k=0;k<3;++k){assert(std::isfinite(v[k])&&v[k]>=0&&v[k]<=1);
                        if(!texture)near(v[k],(.24f+.08f*k)*f.b.neutral[k],.00015f);}
                    if(texture){Rgb balanced=v;for(int k=0;k<3;++k)balanced[k]/=f.b.neutral[k];
                        float d=detailLuma(balanced)-.35f;detailEnergy+=d*d;++count;}
                }
        }
        if(texture)assert(std::sqrt(detailEnergy/count)>.012);
        // Coordinate-only reconstruction must agree in overlapping tiles.
        for(int y=192;y<220;y+=3)for(int x=192;x<220;x+=3){auto av=ref.rgb(a,x,y),bv=ref.rgb(b,x,y);assert(av==bv);}
        // Physical image edges and all CFA phases have measured colour support.
        for(auto xy:std::array<std::array<int,2>,4>{{{{0,0}},{{295,0}},{{0,303}},{{295,303}}}}){
            auto t=ref.tile(xy[0],xy[1],8);auto v=ref.rgb(t,xy[0],xy[1]);
            for(float z:v)assert(std::isfinite(z)&&z>=0&&z<=1);
        }
    }
}
static void transport(){
    const std::string path="/tmp/hex-detail-header-"+std::to_string(getpid());
    auto write=[&](int version,float l,float c,float neutral,bool truncate){
        std::array<uint32_t,20> h{};h[0]=0x32515848;h[1]=version;h[2]=h[3]=288;h[4]=100;h[5]=3;h[11]=6;
        float black=64,white=1023;std::memcpy(&h[8],&black,4);std::memcpy(&h[9],&white,4);
        float p[5]={l,c,neutral,1.f,.7f};std::memcpy(&h[12],p,sizeof(p));
        std::ofstream out(path,std::ios::binary);out.write(reinterpret_cast<const char*>(h.data()),version==1?64:80);
        if(!truncate){std::vector<uint16_t> data(288*288*6,64);out.write(reinterpret_cast<const char*>(data.data()),data.size()*2);}
    };
    write(1,0,0,0,false);{MappedBurst m(path);near(m.burst.luma,1);near(m.burst.chroma,1);assert(m.burst.raw[0][0]==64);}
    write(2,.3f,.8f,.5f,false);{MappedBurst m(path);near(m.burst.luma,.3f);near(m.burst.chroma,.8f);near(m.burst.neutral[0],.5f);assert(m.burst.raw[5][0]==64);}
    for(int k=0;k<6;++k){write(2,k==0?-1.f:k==1?NAN:.3f,k==2?2.f:.8f,k==3?0.f:k==4?INFINITY:.5f,k==5);
        bool rejected=false;try{MappedBurst m(path);}catch(const std::exception&){rejected=true;}assert(rejected);}
    std::remove(path.c_str());
}
struct FlatNetwork {
    std::vector<float> input=std::vector<float>(288*288*18),output=std::vector<float>(576*576*3);
    NormalVst vst{100};Rgb wp{{.55f,1.f,.72f}};
    void execute(){for(size_t i=0;i<output.size();++i)output[i]=2.f*std::sqrt(.35f*wp[i%3]/vst.shot+vst.offset())/vst.norm;}
};
static void endToEnd(){
    Fixture f(3,true);FlatNetwork net;std::string path="/tmp/hex-detail-image-"+std::to_string(getpid());
    std::array<double,3> energy{};
    for(int setting=0;setting<3;++setting){f.b.luma=setting*.5f;f.b.chroma=1.f;captureHex(net,f.b,path);
        std::ifstream in(path,std::ios::binary);std::vector<uint16_t> out(size_t(f.b.w)*f.b.h);in.read(reinterpret_cast<char*>(out.data()),out.size()*2);
        assert(in.gcount()==std::streamsize(out.size()*2));
        for(int y=32;y<f.b.h-32;++y)for(int x=32;x<f.b.w-32;++x){int c=bayerColor(x,y,3);float v=out[size_t(y)*f.b.w+x]/65535.f/f.b.neutral[c]-.35f;energy[setting]+=v*v;}
    }
    assert(energy[0]>1.);assert(energy[1]>energy[2]*10);assert(energy[0]>energy[1]*3.9&&energy[0]<energy[1]*4.1);
    std::remove(path.c_str());
}
int main(){blend();transport();for(int cfa=0;cfa<4;++cfa)reference(cfa);endToEnd();std::puts("HexQuad detail: independent L/C, WB, real CFA samples, overlapping tiles, v1/v2 headers and real capture blend PASS");}
