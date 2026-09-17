#include "../app/src/main/cpp/vivo-hexquad-check.h"
#include <cassert>
using namespace vivo_hexquad;
struct ReferenceNetwork {
    int scale, red, calls=0;
    std::vector<float> input=std::vector<float>(288*288*18), output;
    const float* bound;
    ReferenceNetwork(int s,int r):scale(s),red(r),output(size_t(288*s)*288*s*3),bound(input.data()){}
    void execute() {
        assert(input.data()==bound);
        int chart=calls%6,iso=calls<6?100:400,side=288*scale;
        auto lut=NormalVst(iso).forward();
        for(size_t p=0;p<input.size();p+=18) {
            int count=0;for(size_t c=0;c<18;++c)if(input[p+c]>0)++count;
            assert(count==6); // Six sparse source pixels, not six RGB images.
            int x=int(p/18)%288,y=int(p/18)/288;
            // Independent oracle: canonical R is always at top-left 4x4.
            int c=(x%8<4 && y%8<4)?0:(x%8>=4 && y%8>=4)?2:1;
            int px=(red&1)?287-x:x,py=(red&2)?287-y:y;
            auto raw=unsigned(std::lround(chartValue(chart,c,float(px),float(py))*16383.f));
            for(int f=0;f<6;++f)assert(input[p+f*3+c]==lut[c*Levels+raw]*(1.f/65535.f));
        }
        for(int y=0;y<side;++y)for(int x=0;x<side;++x)for(int c=0;c<3;++c) {
            int px=(red&1)?side-1-x:x,py=(red&2)?side-1-y:y;
            float v=chartValue(chart,c,(px+.5f)/scale-.5f,(py+.5f)/scale-.5f);
            size_t q=size_t(std::lround(std::max(0.f,std::min(1.f,v))*16383.f));
            output[(size_t(y)*side+x)*3+c]=lut[q]/65535.f;
        }
        ++calls;
    }
};
int main() {
    std::ostringstream messages;auto* old=std::cout.rdbuf(messages.rdbuf());
    for(int scale:{1,2})for(int red=0;red<4;++red) {
        ReferenceNetwork network(scale,red);assert(checkHexCharts(network,scale,red));assert(network.calls==12);
        auto inverse=NormalVst(100).inverse();
        std::fill(network.output.begin(),network.output.end(),0.f);
        assert(!scoreChart(network.output,inverse,scale,2).pass());
        // A finite range error cannot be hidden by IVST clipping.
        network.output[(size_t(100)*288*scale+100)*3]=10;
        assert(scoreChart(network.output,inverse,scale,2).badRange==1);
        network.output[0]=std::numeric_limits<float>::quiet_NaN();
        bool rejected=false;try{scoreChart(network.output,inverse,scale,2);}catch(const std::invalid_argument&){rejected=true;}
        assert(rejected); // Nonfinite even in unused halo is fatal.
    }
    std::cout.rdbuf(old);
    assert(messages.str().find("diagnostic_only=1")!=std::string::npos);
    std::cout<<"HexQuad chart validation and fixed-buffer checks passed\n";
}
