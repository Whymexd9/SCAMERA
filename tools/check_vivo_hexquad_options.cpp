#include "../app/src/main/cpp/vivo-hexquad-capture.h"
#include "../app/src/main/cpp/vivo-hexquad-iso-diagnostics.h"
#include <cassert>
#include <cstdio>
using namespace vivo_hexquad;
static void near(float a,float b,float eps=2.e-5f){assert(std::abs(a-b)<eps);}
static void profiles(){
    for(int iso:{50,100,800,3200,12800}){
        NormalVst stock(iso),same(iso,{1,1,1});assert(stock.forward()==same.forward());assert(stock.inverse()==same.inverse());
        for(NoiseScale n:std::vector<NoiseScale>{{.5f,1,1},{2,1,1},{1,.5f,2},{1,2,.5f},{2,2,2}}){
            NormalVst v(iso,n);near(v.shot,stock.shot*n.overall*n.photon);near(v.variance,stock.variance*n.overall*n.readout);
            assert(v.norm==stock.norm);auto f=v.forward();auto inv=v.inverse();
            for(int i=0;i<16384;i+=13){if(f[i]<65500)near(inv[0][f[i]],i/16383.f,.001f);}
            for(size_t i=1;i<16384;++i)assert(f[i]>=f[i-1]);
        }
    }
    // Changing assumed noise changes packed data, not the physical noise drawn.
    auto a=makeIsoInput(800,3,{{.2f,.3f,.4f}},1),b=makeIsoInput(800,3,{{.2f,.3f,.4f}},1,-1,{1,.5f,2});
    assert(a.mean==b.mean&&a.variance==b.variance&&a.packed!=b.packed);
    for(float v:{0.f,-1.f,NAN,INFINITY,2.01f}){bool fail=false;try{NormalVst n(800,{v,1,1});}catch(const std::exception&){fail=true;}assert(fail);}
}
struct RawFixture{
    RawBurst b;std::array<std::vector<uint16_t>,6> data;
    RawFixture(int red,bool texture=false,bool noisy=false){
        b.w=296;b.h=304;b.iso=100;b.red=red;b.black=64;b.white=16383;
        CfaOrientation o(b.w,b.h,red);NormalVst profile(b.iso);ProfileNoise rng(9);
        for(int f=0;f<6;++f){data[f].resize(size_t(b.w)*b.h);
            for(int y=0;y<b.h;++y)for(int x=0;x<b.w;++x){float v=.35f;
                if(texture)v+=.17f*std::sin(x*.7f)+.09f*std::cos(y*.51f);
                if(noisy)v+=rng.normal()*std::sqrt(profile.shot*v+profile.variance);
                data[f][size_t(o.y(y))*b.w+o.x(x)]=uint16_t(std::lround(64+v*(16383-64)));
            }b.raw[f]=data[f].data();
        }
    }
};
static float pattern(int x,int y,int c){return .3f+.07f*c+.012f*std::sin(x*.05f)+.008f*std::cos(y*.04f)+(x%2?.018f:-.018f);}
struct PatternNetwork{
    int scale,w,h,calls=0;NormalVst transfer;
    std::vector<float> input=std::vector<float>(288*288*18),output;
    std::vector<uint16_t> lut;
    PatternNetwork(const RawBurst& b):scale(b.scale),w(b.w),h(b.h),transfer(b.iso,b.noise),output(size_t(288*scale)*288*scale*3),lut(transfer.forward()){}
    void execute(){
        auto xs=origins(w),ys=origins(h);int ox=xs[calls%xs.size()],oy=ys[calls/xs.size()];++calls;
        int side=288*scale;
        for(int y=0;y<side;++y)for(int x=0;x<side;++x)for(int c=0;c<3;++c){
            int px=(ox-Halo)*scale+x,py=(oy-Halo)*scale+y;
            float v=pattern(px,py,c);output[(size_t(y)*side+x)*3+c]=lut[unsigned(std::lround(v*16383))]/65535.f;
        }
    }
};
static std::vector<uint16_t> run(RawBurst& b){
    PatternNetwork net(b);std::string path="/tmp/hex-options-image-"+std::to_string(getpid());captureHex(net,b,path);
    std::ifstream in(path,std::ios::binary|std::ios::ate);int os=b.fullResolution?2:1;size_t pixels=size_t(b.w)*b.h*os*os;
    assert(in.tellg()==std::streamoff(pixels*2));in.seekg(0);std::vector<uint16_t> out(pixels);in.read(reinterpret_cast<char*>(out.data()),pixels*2);std::remove(path.c_str());return out;
}
static void assembly(){
    for(int red=0;red<4;++red)for(int mode=0;mode<3;++mode){
        RawFixture f(red);f.b.scale=mode==0?1:2;f.b.fullResolution=mode==2;f.b.noise={1,.8f,1.2f};auto out=run(f.b);
        int os=f.b.fullResolution?2:1,ow=f.b.w*os,oh=f.b.h*os;CfaOrientation o(ow,oh,red);
        int area=f.b.fullResolution?1:f.b.scale;
        for(int y=0;y<oh;++y)for(int x=0;x<ow;++x){int cx=o.x(x),cy=o.y(y),c=bayerColor(x,y,red);float expected=0;
            for(int dy=0;dy<area;++dy)for(int dx=0;dx<area;++dx)expected+=pattern(cx*area+dx,cy*area+dy,c)/float(area*area);
            near(out[size_t(y)*ow+x]/65535.f,expected,.0004f);
        }
    }
    // Full-resolution blending exercises the reference halo at physical edges and tile seams.
    RawFixture f(3);f.b.fullResolution=true;f.b.luma=f.b.chroma=0;f.b.texture=1;auto out=run(f.b);
    for(auto v:out)near(v/65535.f,.35f,.0002f);
    RawFixture t(0,true);t.b.scale=1;t.b.texture=1;auto a=run(t.b);t.b.texture=0;auto b=run(t.b);assert(a!=b);
}
static void textures(){
    for(bool noisy:{false,true})for(bool textured:{false,true}){
        RawFixture f(0,textured,noisy);TetraDetailReference<RawBurst> r(f.b);NormalVst n(f.b.iso);double mean=0;int count=0;
        for(int y=32;y<f.b.h-32;y+=3)for(int x=32;x<f.b.w-32;x+=3){float v=r.textureConfidence(x,y,n.shot,n.variance);assert(v>=0&&v<=1);mean+=v;++count;}
        mean/=count;if(textured)assert(mean>.8);else assert(mean<.03);
    }
}
static void header(){
    const std::string path="/tmp/hex-options-header-"+std::to_string(getpid());std::array<uint32_t,28> h{};
    h[0]=0x32515848;h[1]=3;h[2]=h[3]=288;h[4]=800;h[5]=3;h[11]=6;h[20]=2;h[25]=1;
    auto set=[&](int at,float v){std::memcpy(&h[at],&v,4);};
    set(8,64);set(9,1023);set(12,.3f);set(13,.8f);set(14,.5f);set(15,1);set(16,.7f);
    set(21,.75f);set(22,1.25f);set(23,1.5f);set(24,.4f);
    auto write=[&](int bytes=112){std::ofstream o(path,std::ios::binary);o.write(reinterpret_cast<char*>(h.data()),bytes);std::vector<uint16_t> d(288*288*6,64);o.write(reinterpret_cast<char*>(d.data()),d.size()*2);};
    write();{MappedBurst m(path);assert(m.burst.scale==2&&m.burst.fullResolution);near(m.burst.noise.photon,1.25f);near(m.burst.texture,.4f);assert(m.burst.raw[5][0]==64);}
    auto original=h;
    h[1]=4;h[26]=1;write();{MappedBurst m(path);assert(m.burst.useGpu);}
    h[26]=2;write();{bool failed=false;try{MappedBurst m(path);}catch(const std::exception&){failed=true;}assert(failed);}
    h[26]=1;h[27]=1;write();{bool failed=false;try{MappedBurst m(path);}catch(const std::exception&){failed=true;}assert(failed);}
    h=original;
    for(int bad=0;bad<7;++bad){h=original;if(bad==0)h[20]=3;if(bad==1)h[20]=1;if(bad==2)set(21,NAN);if(bad==3)set(24,2);if(bad==4)h[26]=1;if(bad==5)h[25]=2;
        write(bad==6?100:112);bool rejected=false;try{MappedBurst m(path);}catch(const std::exception&){rejected=true;}assert(rejected);}
    std::remove(path.c_str());
}
int main(int argc,char** argv){if(argc==2){MappedBurst m(argv[1]);assert(m.burst.useGpu==(std::string(argv[1]).find(".gpu")!=std::string::npos));assert(m.burst.scale==2&&m.burst.fullResolution&&m.burst.response);near(m.burst.luma,.56f);near(m.burst.chroma,.94f);near(m.burst.noise.overall,.75f);near(m.burst.noise.photon,1.25f);near(m.burst.noise.readout,1.5f);near(m.burst.texture,.4f);std::puts("Java -> native v3 capture header PASS");return 0;}std::ostringstream quiet;auto* old=std::cout.rdbuf(quiet.rdbuf());profiles();header();textures();assembly();std::cout.rdbuf(old);std::puts("HexQuad options: VST identity/roundtrip, fixed physical noise, texture/noise separation, x1/x2/full CFA/seams and v3 transport PASS");}
