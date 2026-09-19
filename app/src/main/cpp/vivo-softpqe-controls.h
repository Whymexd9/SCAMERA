#pragma once
#include "vivo-raisr-controls.h"
#include <cctype>
#include <functional>
#include <iomanip>
#include <locale>
#include <sstream>
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
inline std::string scaleNumbers(const std::string& text,unsigned percent,bool positive=false) {
    std::string values=text;std::replace(values.begin(),values.end(),',',' ');
    std::istringstream in(values);in.imbue(std::locale::classic());
    std::ostringstream out;out.imbue(std::locale::classic());out<<std::setprecision(9);
    double v;unsigned count=0;
    while(in>>v) {
        if(!std::isfinite(v) || v<0 || v>10)throw std::runtime_error("Invalid SoftPQE coefficient");
        v*=percent*.01;if(positive)v=std::max(v,1.e-6);
        if(count++)out<<',';
        out<<v;
    }
    if(!in.eof() || !count)throw std::runtime_error("Invalid SoftPQE numeric table");
    return out.str();
}
inline void scaleTag(std::string& xml,const std::string& tag,unsigned amount,bool positive=false) {
    if(!editElement(xml,tag,[&](const std::string& value){return scaleNumbers(value,amount,positive);}))
        throw std::runtime_error("Missing SoftPQE coefficient: "+tag);
}
inline std::string tuneProfile(std::string xml,Controls c) {
    if(c.luma>100 || c.chroma>100 || c.sharpen>100 || c.strength>100)
        throw std::runtime_error("SoftPQE controls must be 0..100");
    if(c.luma!=100)for(const char* section:{"yModelParameters","auxYModelParameters"}) {
        unsigned n=editElement(xml,section,[&](std::string block){
            scaleTag(block,"noise1Level",c.luma);scaleTag(block,"noise2Level",c.luma);
            // The native config requires maxNoise1Level > noise1Level, even at zero.
            scaleTag(block,"maxNoise1Level",c.luma,true);return block;
        });
        // Portrait/skin overrides do not contain an auxiliary-model section.
        if(!n && std::string(section)=="yModelParameters")throw std::runtime_error("Missing SoftPQE Y settings");
    }
    if(c.chroma!=100 && !editElement(xml,"uvModelParameters",[&](std::string block){
        scaleTag(block,"minLevel",c.chroma);scaleTag(block,"anchorLevel",c.chroma);
        scaleTag(block,"maxLevel",c.chroma);scaleTag(block,"noise2Level",c.chroma);return block;
    }))throw std::runtime_error("Missing SoftPQE UV settings");
    if(c.sharpen!=100)editElement(xml,"postSharpeningParameters",[&](std::string block){
        // Scale USM and conventional sharpening once each. BlurScale must stay
        // fixed at 1 for these SR models, as required by the original config.
        for(const char* tag:{"weight1O","weight1U","weight2O","weight2U","usmStrength"})
            scaleTag(block,tag,c.sharpen);
        return block;
    });
    return xml;
}
inline std::string tuneMain(std::string xml,Controls c) {
    if(c.sharpen==0 && !editElement(xml,"enableUsm",[](const std::string&){return "0";}))
        throw std::runtime_error("Missing SoftPQE USM switch");
    return xml;
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
}
