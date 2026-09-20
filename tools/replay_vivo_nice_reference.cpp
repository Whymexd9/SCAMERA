// Replay a saved NCH burst through the actual capture preparation code.
// No neural execution: compare the NEW first model triplet to the previously
// saved, unwarped N-reference triplet. The old code put a warped donor there.
#include "../app/src/main/cpp/vivo-nice-capture.h"
#include <iostream>
#include <cassert>
using namespace vivo_nice;
struct Prepared {};
int main(int argc,char** argv) {
    if(argc!=3) {std::cerr<<"Usage: replay input.nch old-N-ref.pfm\n";return 2;}
    try {
        MappedNiceBurst mapped(argv[1]);
        std::ifstream f(argv[2],std::ios::binary);
        std::string magic;int w=0,h=0;float scale=0;
        f>>magic>>w>>h>>scale;f.get();
        if(magic!="PF"||w!=544||h!=544||scale!=-1)throw std::runtime_error("PFM contract");
        std::vector<float> saved(size_t(w)*h*3);
        f.read(reinterpret_cast<char*>(saved.data()),saved.size()*sizeof(float));
        if(!f)throw std::runtime_error("Incomplete PFM");
        float maximum=0;
        try {
            reconstruct(mapped.burst,[&](const std::vector<float>& in,std::vector<float>&){
                for(int y=0;y<h;++y)for(int x=0;x<w;++x)for(int c=0;c<3;++c){
                    const float expected=saved[(size_t(h-1-y)*w+x)*3+c];
                    const float actual=in[(size_t(y)*w+x)*22+c];
                    if(!std::isfinite(actual))throw std::runtime_error("Nonfinite input");
                    maximum=std::max(maximum,std::abs(actual-expected));
                }
                if(maximum>1e-6f)throw std::runtime_error("First triplet differs from the saved unwarped reference");
                throw Prepared{};
            },[](const std::string& line){std::cout<<line<<'\n';});
            throw std::runtime_error("No first tile");
        }catch(const Prepared&){}
        std::cout<<"PASS: 887808 first-triplet values match the captured unwarped reference; max error="<<maximum<<"\n";
        std::cout<<"This does not execute NICE or verify the final photograph.\n";
    }catch(const std::exception& e){std::cerr<<e.what()<<'\n';return 1;}
}
