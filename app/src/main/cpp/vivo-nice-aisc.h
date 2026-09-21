#pragma once
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <stdexcept>

namespace vivo_nice {
struct AiscClassScore { char name[32]; float score; };
struct AiscResult {
    AiscClassScore classes[7];
    char category[30];
    char label[30];
    float confidence;
    float backlight;
    uint32_t count;
};
static_assert(sizeof(AiscResult)==0x144);
static_assert(offsetof(AiscResult,backlight)==0x13c);

// Postprocessing only: inputs are the original network's two output tensors.
inline AiscResult decodeAisc(std::array<float,7> scene,std::array<float,2> light,bool softmax) {
    auto normalize=[softmax](auto& values) {
        float sum=0.f;
        for(float& v:values) {
            if(!std::isfinite(v))throw std::invalid_argument("Invalid AISC tensor");
            if(softmax) { v=std::exp(v);sum+=v; }
        }
        if(softmax) {
            if(!(sum>0.f)||!std::isfinite(sum))
                throw std::invalid_argument("AISC softmax outside finite range");
            for(float& v:values)v/=sum;
        }
    };
    normalize(scene);normalize(light);
    static constexpr const char* names[]={"indoor","store","landscape","night","snow","caixia","sunset"};
    static constexpr float thresholds[]={.75f,0.f,.65f,.75f,.5f,.78f,.85f};
    AiscResult result{};
    size_t selected=0;
    float maximum=0.f;
    for(size_t i=0;i<scene.size();++i) {
        std::strcpy(result.classes[i].name,names[i]);
        result.classes[i].score=scene[i];
        if(scene[i]>maximum) { maximum=scene[i];selected=i; }
    }
    if(maximum>=thresholds[selected])std::strcpy(result.label,names[selected]);
    std::strcpy(result.category,selected<=1 ? "indoor" : selected==3 ? "night" : "outdoor");
    result.confidence=maximum;
    result.backlight=selected==5||selected==6 ? .9f : light[1];
    result.count=7;
    return result;
}
inline bool aiscBacklight(const AiscResult& result) {
    if(result.count!=7||!std::isfinite(result.backlight))
        throw std::invalid_argument("Invalid AISC result");
    return result.backlight>.7f;
}
} // namespace vivo_nice
