#ifndef HEX_CAPTURE_HEADER
#define HEX_CAPTURE_HEADER "../app/src/main/cpp/vivo-hexquad-capture.h"
#endif
#include HEX_CAPTURE_HEADER
#include <cassert>
#include <cstdio>
#include <iomanip>
#include <thread>
using namespace vivo_hexquad;
// Hash every actual packed input byte, including holes/halo/frame order.
// A deterministic mock permits comparing the full assembler to pre-speed v14;
// it makes no claim about NPU execution time or image quality.
struct SpeedNetwork {
    std::vector<float> input=std::vector<float>(288*288*18),output;
    uint64_t hash=14695981039346656037ull;
    unsigned calls=0;
    std::thread::id owner=std::this_thread::get_id();
    explicit SpeedNetwork(int scale):output(size_t(288*scale)*288*scale*3){}
    void execute(){
        assert(std::this_thread::get_id()==owner);++calls;
        for(float v:input){uint32_t bits;std::memcpy(&bits,&v,4);for(int i=0;i<4;++i){hash^=(bits>>(i*8))&255;hash*=1099511628211ull;}}
        // Includes finite overshoot, channel variation and spatial detail.
        // Entire-frame packing hash above detects input regressions independently.
        for(size_t i=0;i<output.size();++i){
            float v=.1f+float((i*17+calls*13)%1001)*(1.f/2000.f);
            output[i]=i%4093==0?-.01f:i%4099==0?1.01f:v;
        }
#ifdef HEXQUAD_TEST_EXECUTED
        HEXQUAD_TEST_EXECUTED(calls);
#endif
    }
};
static void fixture(const std::string& folder,int red,int mode,bool blend,int width=296,int height=304,bool gpu=false){
    RawBurst b;b.useGpu=gpu;b.w=width;b.h=height;b.red=red;b.black=64;b.white=1023;b.iso=800;
    b.scale=mode==0?1:2;b.fullResolution=mode==2;b.response=red==1;
    b.noise={.85f,1.15f,.75f};b.neutral={{.57f,1.f,.73f}};
    if(blend){b.luma=std::array<float,4>{{0.f,.37f,.6f,1.f}}[red];b.chroma=std::array<float,4>{{1.f,.2f,0.f,.65f}}[red];b.texture=.63f;}
    CfaOrientation orientation(b.w,b.h,red);
    std::array<std::vector<uint16_t>,6> data;
    for(int f=0;f<6;++f){
        data[f].resize(size_t(b.w)*b.h);
        for(int y=0;y<b.h;++y)for(int x=0;x<b.w;++x){
            int sx=x-f,sy=y+f;
            int wave=((sx*sx+sy*sy+sy*sx)/64)%240;if(wave<0)wave+=240;
            int value=250+wave+25*b.color(x,y)+(x+f)%7;
            data[f][size_t(orientation.y(y))*b.w+orientation.x(x)]=uint16_t(value);
        }
        b.raw[f]=data[f].data();
    }
    SpeedNetwork net(b.scale);
    const std::string name=std::to_string(red)+"-"+std::to_string(mode)+"-"+std::to_string(blend);
    captureHex(net,b,folder+"/"+name+".raw");
    std::ofstream meta(folder+"/"+name+".input");meta<<std::hex<<net.hash<<" "<<std::dec<<net.calls<<'\n';
}
int main(int argc,char** argv){
    if(argc!=2&&argc!=3)return 2;
    if(argc==3){
        if(std::string(argv[2])=="--race"){fixture(argv[1],3,1,true);fixture(argv[1],3,2,true);}
        else fixture(argv[1],3,1,true,1024,768);
        return 0;
    }
    std::ostringstream quiet;auto* old=std::cout.rdbuf(quiet.rdbuf());
    for(int red=0;red<4;++red)for(int mode=0;mode<3;++mode)for(bool blend:{false,true})fixture(argv[1],red,mode,blend);
    std::cout.rdbuf(old);
    std::puts("24 speed regression fixtures: x1/x2/full, all CFA, six moving inputs, texture and independent L/C completed");return 0;
}
