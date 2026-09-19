#pragma once
#include "lanczos-downscale.h"
#include <cctype>
#include <functional>
#include <sstream>
#include <stdexcept>
#include <string>

namespace vivo_softpqe {
// Edit only verified private XML copies, never the firmware files.
inline unsigned editElement(std::string& xml,const std::string& tag,
                            const std::function<std::string(const std::string&)>& edit) {
    size_t pos=0;unsigned count=0;
    const std::string open="<"+tag,close="</"+tag+">";
    while((pos=xml.find(open,pos))!=std::string::npos) {
        size_t endName=pos+open.size();
        if(endName>=xml.size())throw std::runtime_error("Truncated SoftPQE tag");
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

inline std::string constantList(const std::string& text, const std::string& value) {
    std::stringstream stream(text);std::string item,out;
    while(std::getline(stream,item,',')) {
        size_t consumed=0;double n=std::stod(item,&consumed);
        while(consumed<item.size() && std::isspace(static_cast<unsigned char>(item[consumed])))++consumed;
        if(consumed!=item.size() || !std::isfinite(n))throw std::runtime_error("Invalid SoftPQE numeric list");
        if(!out.empty())out+=",";
        out+=value;
    }
    if(out.empty())throw std::runtime_error("Empty SoftPQE numeric list");
    return out;
}
inline std::string tuneMain(std::string xml) {
    for(const char* tag:{"enableUsm","enableSharpen","enablePartialSharpen"})
        if(!editElement(xml,tag,[](const std::string&){return "0";}))
            throw std::runtime_error("Missing SoftPQE sharpening switch");
    // Documented native mode: 2 = Y only. UV is enlarged separately from source.
    if(editElement(xml,"processMode",[](const std::string&){return "2";})!=1)
        throw std::runtime_error("Missing SoftPQE process mode");
    return xml;
}
inline std::string tuneProfile(std::string xml) {
    for(const char* section:{"yModelParameters","auxYModelParameters"}) {
        const unsigned count=editElement(xml,section,[](std::string block) {
            for(const char* tag:{"noise1Level","noise2Level","maxNoise1Level"}) {
                const std::string value=std::string(tag)=="maxNoise1Level"?"0.000001":"0";
                if(!editElement(block,tag,[&](const std::string& text){return constantList(text,value);}))
                    throw std::runtime_error("Missing SoftPQE noise conditioning");
            }
            return block;
        });
        // Skin overrides inherit the auxiliary-Y table from the main profile.
        if(!count && std::string(section)=="yModelParameters")throw std::runtime_error("Missing SoftPQE Y profile");
    }
    editElement(xml,"postSharpeningParameters",[](std::string block) {
        for(const char* tag:{"weight1O","weight1U","weight2O","weight2U","usmStrength"})
            if(!editElement(block,tag,[](const std::string& text){return constantList(text,"0");}))
                throw std::runtime_error("Missing SoftPQE sharp weight");
        return block;
    });
    return xml;
}
// Independent chroma interpolation. No denoising, residual restoration, USM or
// blending with the native UV output. The Y plane remains byte-for-byte native.
inline void upscaleChroma(const uint8_t* input,uint8_t* output,int w,int h) {
    if(!input || !output || w<2 || h<2 || w%2 || h%2)throw std::runtime_error("Invalid SoftPQE chroma geometry");
    const int iw=w/2,ih=h/2,ow=w,oh=h;
    const auto xs=scamera_lanczos::coefficients(iw,ow,2),ys=scamera_lanczos::coefficients(ih,oh,2);
    size_t capacity=1;for(const auto& t:ys)capacity=std::max(capacity,t.weights.size());
    std::vector<std::vector<float>> rows(capacity,std::vector<float>(size_t(ow)*2));
    std::vector<int> tags(capacity,-1);std::vector<float> sum(size_t(ow)*2);
    const uint8_t* source=input+size_t(w)*h;uint8_t* dest=output+size_t(w)*h*4;
    for(int y=0;y<oh;++y) {
        std::fill(sum.begin(),sum.end(),0);const auto& vertical=ys[y];
        for(size_t k=0;k<vertical.weights.size();++k) {
            int sy=vertical.first+int(k);size_t slot=size_t(sy)%capacity;auto& row=rows[slot];
            if(tags[slot]!=sy) {
                for(int x=0;x<ow;++x)for(int c=0;c<2;++c) {
                    float value=0;const auto& horizontal=xs[x];
                    for(size_t j=0;j<horizontal.weights.size();++j)
                        value+=source[size_t(sy)*w+(size_t(horizontal.first)+j)*2+c]*horizontal.weights[j];
                    row[x*2+c]=value;
                }
                tags[slot]=sy;
            }
            for(size_t i=0;i<sum.size();++i)sum[i]+=row[i]*vertical.weights[k];
        }
        for(int x=0;x<ow*2;++x)dest[size_t(y)*ow*2+x]=uint8_t(std::max(0l,std::min(255l,std::lround(sum[x]))));
    }
}
}
