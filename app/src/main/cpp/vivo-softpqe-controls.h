#pragma once
#include "vivo-raisr-controls.h"
#include <cctype>
#include <functional>
#include <stdexcept>
#include <string>

namespace vivo_softpqe {
struct Controls { unsigned luma=100, chroma=100, sharpen=100, strength=100; };

// Edit only named numeric leaves inside the already SHA-pinned Vivo templates.
// Preserve model names, quantization, ISO/DRC tables and all unrelated text.
inline unsigned editElement(std::string& xml,const std::string& tag,
                            const std::function<std::string(const std::string&)>& edit) {
    size_t pos=0;unsigned count=0;
    const std::string open="<"+tag,close="</"+tag+">";
    while((pos=xml.find(open,pos))!=std::string::npos) {
        size_t endName=pos+open.size();
        if(xml[endName]!='>' && !std::isspace(static_cast<unsigned char>(xml[endName]))) {pos=endName;continue;}
        size_t comment=xml.rfind("<!--",pos),commentEnd=xml.rfind("-->",pos);
        if(comment!=std::string::npos && (commentEnd==std::string::npos || comment>commentEnd)) {pos=endName;continue;}
        size_t start=xml.find('>',endName),end=xml.find(close,start);
        if(start==std::string::npos || end==std::string::npos)throw std::runtime_error("Malformed SoftPQE config: "+tag);
        ++start;std::string value=edit(xml.substr(start,end-start));
        xml.replace(start,end-start,value);pos=start+value.size()+close.size();++count;
    }
    return count;
}
// SR models keep their pinned noise/blur conditioning. These values cannot
// undo learned smoothing (and low-ISO UV coefficients are already zero).
inline std::string tuneMain(std::string xml,Controls) {
    for(const char* tag:{"enableUsm","enableSharpen","enablePartialSharpen"})
        if(!editElement(xml,tag,[](const std::string&){return "0";}))
            throw std::runtime_error("Missing SoftPQE sharpening switch");
    return xml;
}
struct Changes { double luma=0, chroma=0, sharpen=0; };
// Restore the input-minus-reduced-output residual separately in Y and V/U.
// Retains the network's new subpixel detail instead of mixing the whole image
// back to interpolation. At zero NR this also restores source noise. This is
// output compensation, not a claim to disable denoising inside the neural net.
// Two cached input-resolution rows make in-place processing O(width) memory.
inline double restorePlane(const uint8_t* input,uint8_t* output,int w,int h,
                           int channels,unsigned denoise) {
    if(denoise==100)return 0;
    const int ow=w*2, oh=h*2, stride=ow*channels;
    std::vector<float> residual[2];int cached[2]={-1,-1};
    for(auto& row:residual)row.resize(size_t(w)*channels);
    auto cache=[&](int y) {
        const int slot=y%2;if(cached[slot]==y)return;
        for(int x=0;x<w;++x)for(int c=0;c<channels;++c) {
            size_t o=size_t(y*2)*stride+x*2*channels+c;
            float reduced=(output[o]+output[o+channels]+output[o+stride]+output[o+stride+channels])*.25f;
            residual[slot][x*channels+c]=input[(size_t(y)*w+x)*channels+c]-reduced;
        }
        cached[slot]=y;
    };
    double change=0;const float amount=(100-denoise)*.01f;
    for(int y=0;y<oh;++y) {
        float sy=std::clamp((y+.5f)*.5f-.5f,0.f,float(h-1));
        int y0=int(sy),y1=std::min(y0+1,h-1);float fy=sy-y0;
        cache(y0);cache(y1); // Read future native rows before overwriting output.
        for(int x=0;x<ow;++x) {
            float sx=std::clamp((x+.5f)*.5f-.5f,0.f,float(w-1));
            int x0=int(sx),x1=std::min(x0+1,w-1);float fx=sx-x0;
            for(int c=0;c<channels;++c) {
                float a=residual[y0%2][x0*channels+c]*(1-fx)+residual[y0%2][x1*channels+c]*fx;
                float b=residual[y1%2][x0*channels+c]*(1-fx)+residual[y1%2][x1*channels+c]*fx;
                size_t i=size_t(y)*stride+x*channels+c;
                uint8_t v=uint8_t(std::clamp(std::lround(output[i]+amount*(a*(1-fy)+b*fy)),0l,255l));
                change+=std::abs(int(v)-output[i]);output[i]=v;
            }
        }
    }
    return change/(size_t(ow)*oh*channels);
}
// Explicit luminance-only USM, not an undocumented model parameter. Threshold
// suppresses tiny quantization/noise residuals; cap prevents large edge halos.
inline double sharpenPlane(uint8_t* output,int w,int h,unsigned percent) {
    if(!percent)return 0;
    std::vector<uint8_t> rows[3];for(auto& row:rows)row.resize(w);
    auto cache=[&](int y){std::copy_n(output+size_t(y)*w,w,rows[y%3].begin());};
    cache(0);if(h>1)cache(1);
    double change=0;const float amount=percent*.01f;
    for(int y=0;y<h;++y) {
        if(y>0 && y+1<h)cache(y+1);
        auto& prev=rows[std::max(0,y-1)%3];auto& row=rows[y%3];auto& next=rows[std::min(h-1,y+1)%3];
        for(int x=0;x<w;++x) {
            int l=std::max(0,x-1),r=std::min(w-1,x+1);
            float blur=(prev[l]+2*prev[x]+prev[r]+2*row[l]+4*row[x]+2*row[r]+next[l]+2*next[x]+next[r])/16.f;
            float detail=row[x]-blur;
            detail=std::copysign(std::max(0.f,std::abs(detail)-1.f),detail);
            uint8_t v=uint8_t(std::clamp(std::lround(row[x]+amount*std::clamp(detail,-12.f,12.f)),0l,255l));
            change+=std::abs(int(v)-row[x]);output[size_t(y)*w+x]=v;
        }
    }
    return change/(size_t(w)*h);
}
// Output mix is separate from the native noise-conditioning controls. 100 is
// bit-exact Vivo output; 0 is ordinary interpolation, with unchanged dimensions.
inline void mix(const uint8_t* input,uint8_t* output,int w,int h,int ow,int oh,unsigned strength) {
    if(strength>100)throw std::runtime_error("Invalid SoftPQE mix");
    if(strength==100)return;
    std::vector<float> row(ow);const float amount=strength*.01f;
    for(int plane=0;plane<2;++plane) {
        int channels=plane?2:1,ih=plane?h/2:h,ohh=plane?oh/2:oh;
        const uint8_t* in=input+(plane?size_t(w)*h:0);
        uint8_t* out=output+(plane?size_t(ow)*oh:0);
        for(int y=0;y<ohh;++y) {
            vivo_raisr::referenceRow(in,w/channels,ih,ow/channels,ohh,y,channels,row);
            for(int x=0;x<ow;++x) {
                size_t i=size_t(y)*ow+x;
                out[i]=uint8_t(std::clamp(std::lround(row[x]+amount*(out[i]-row[x])),0l,255l));
            }
        }
    }
}
inline Changes finish(const uint8_t* input,uint8_t* output,int w,int h,int ow,int oh,Controls c) {
    if(c.luma>100 || c.chroma>100 || c.sharpen>100 || c.strength>100 ||
       w<2 || h<2 || w%2 || h%2 || ow!=w*2 || oh!=h*2)
        throw std::runtime_error("Invalid SoftPQE output controls or geometry");
    Changes changes;
    // Zero overall strength has an exact, independent interpolation endpoint.
    if(c.strength) {
        changes.luma=restorePlane(input,output,w,h,1,c.luma);
        changes.chroma=restorePlane(input+size_t(w)*h,output+size_t(ow)*oh,w/2,h/2,2,c.chroma);
        changes.sharpen=sharpenPlane(output,ow,oh,c.sharpen);
    }
    mix(input,output,w,h,ow,oh,c.strength);
    return changes;
}

}
