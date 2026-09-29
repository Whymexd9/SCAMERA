#pragma once
// SCAM HDR without the vivo neural network (Hexagon v79 context binaries run only on
// SM8750): the same aligned burst is merged in linear Bayer, the highlights of the
// merged N are rebuilt from the short frames S/ES, and the result is demosaiced.
// Output layout and units are those of the network path (linear RGB in N units,
// canonical RGGB geometry), so the app's WB / tone pipeline is unchanged.
#include <vector>
#include <cmath>
#include <algorithm>
#include <functional>

namespace vivo_nice {
inline float portableSmooth(float a,float b,float x) {
    const float t=std::min(std::max((x-a)/(b-a),0.f),1.f);
    return t*t*(3.f-2.f*t);
}
// Malvar-He-Cutler gradient-corrected demosaic of a canonical RGGB plane. It runs on
// the square root of the HDR values so bright edges do not ring; output is squared back.
template<class Colour,class Reflect>
inline void portableDemosaic(const std::vector<float>& hdr,int w,int h,const Colour& colour,const Reflect& reflect,
                             const std::function<void(int,const std::function<void(int,int)>&)>& bands,std::vector<float>& rgb) {
    std::vector<float> u(hdr.size());
    for(size_t i=0;i<hdr.size();++i)u[i]=std::sqrt(std::max(hdr[i],0.f));
    rgb.assign(size_t(w)*h*3,0.f);
    bands(h,[&](int y0,int y1){
        auto at=[&](int x,int y)->float{
            if(unsigned(x)>=unsigned(w)||unsigned(y)>=unsigned(h)){x=reflect(x,w);y=reflect(y,h);}
            return u[size_t(y)*w+x];
        };
        for(int y=y0;y<y1;++y)for(int x=0;x<w;++x){
            const int c=colour(x,y);
            const float C=at(x,y);
            const float W=at(x-1,y),E=at(x+1,y),N=at(x,y-1),S=at(x,y+1);
            const float WW=at(x-2,y),EE=at(x+2,y),NN=at(x,y-2),SS=at(x,y+2);
            float out[3];out[c]=C;
            const float greenAtRB=(4*C+2*(W+E+N+S)-(WW+EE+NN+SS))*.125f;
            const float diag=at(x-1,y-1)+at(x+1,y-1)+at(x-1,y+1)+at(x+1,y+1);
            if(c!=1) {
                out[1]=greenAtRB;
                out[2-c]=(6*C+2*diag-1.5f*(WW+EE+NN+SS))*.125f;
            } else {
                // Row / column neighbours are red or blue depending on the row parity.
                const float horizontal=(5*C+4*(W+E)-(WW+EE)+.5f*(NN+SS)-diag)*.125f;
                const float vertical=(5*C+4*(N+S)-(NN+SS)+.5f*(WW+EE)-diag)*.125f;
                const bool redRow=colour(x-1,y)==0||colour(x+1,y)==0;
                out[0]=redRow?horizontal:vertical;
                out[2]=redRow?vertical:horizontal;
            }
            for(int k=0;k<3;++k){const float v=std::max(out[k],0.f);rgb[(size_t(y)*w+x)*3+k]=v*v;}
        }
    });
}
} // namespace vivo_nice
