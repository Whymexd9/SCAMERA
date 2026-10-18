#include "../app/src/main/cpp/scam-capture.h"
#include "../app/src/main/cpp/scam-hybrid.h"
#include <cassert>
#include <cstdio>
#include <cstring>
#include <random>

int main(){
    std::mt19937 random(20261016);
    std::uniform_real_distribution<float> noise(-0.003f,0.003f);
    float worst=0;
    for(int block:{2,4,8})for(bool withOsc:{false,true}){
        const int w=96,h=311;
        std::vector<float> input(size_t(w)*h*3),osc(size_t(w/block)*(h/block));
        for(int y=0;y<h;++y)for(int x=0;x<w;++x){
            const float step=y<151?0.05f:1.5f;
            for(int c=0;c<3;++c)input[(size_t(y)*w+x)*3+c]=step*(1+0.35f*std::sin(float(x+y*(c+1))))+noise(random);
        }
        for(size_t i=0;i<osc.size();++i)osc[i]=float(i%19)/24.f;
        auto reference=input,banded=input;
        scam::mosaicChromaMedian(reference,w,h,block,withOsc?&osc:nullptr,false,nullptr,0);
        scam::mosaicChromaMedian(banded,w,h,block,withOsc?&osc:nullptr,false,nullptr,7);
        // P213b: the stream in steps of 7 rows gives the floats of its default steps bit for bit (this compared the bounded-rows
        // strips with the full-frame planes, <= 3e-5 relative; tools/check_chroma_band.cpp compares the stream with those planes)
        assert(std::memcmp(reference.data(),banded.data(),reference.size()*sizeof(float))==0);
        for(size_t i=0;i<reference.size();++i){
            assert(std::isfinite(banded[i]));
            worst=std::max(worst,std::abs(banded[i]-input[i]));
            if(i%3==1)assert(banded[i]==input[i]);
        }
    }
    std::printf("PASS: chroma in steps of 7 rows == the default steps bit for bit, Quad/Tetra/2x blocks, borders, oscillation; largest change of R/B %.9g\n",worst);
}
