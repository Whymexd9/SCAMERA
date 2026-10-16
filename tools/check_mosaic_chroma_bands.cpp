#include "../app/src/main/cpp/scam-capture.h"
#include "../app/src/main/cpp/scam-hybrid.h"
#include <cassert>
#include <cstdio>
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
        scam::mosaicChromaMedian(reference,w,h,block,withOsc?&osc:nullptr,false,nullptr,-1);
        scam::mosaicChromaMedian(banded,w,h,block,withOsc?&osc:nullptr,false,nullptr,32);
        for(size_t i=0;i<reference.size();++i){
            assert(std::isfinite(banded[i]));
            const float error=std::abs(reference[i]-banded[i]);worst=std::max(worst,error);
            assert(error<=3e-5f*std::max(1.f,std::abs(reference[i])));
            if(i%3==1)assert(banded[i]==input[i]);
        }
    }
    std::printf("PASS: bounded chroma vs full-plane, Quad/Tetra/2x blocks, borders, strip seams, oscillation; max abs %.9g\n",worst);
}
