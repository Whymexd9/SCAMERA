#pragma once
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <stdexcept>
#include <vector>

namespace vivo_nice {
// CPU optical-flow guide arithmetic recovered from CRE 0x26fde4,
// 0x26fa70 and 0x26d010. Input is sensor RAW with a 64/1023 black level,
// not black-subtracted network input. Gamma and exposure gain come from the
// caller; this function does not invent capture policy or sensor calibration.
inline std::vector<uint8_t> stockMotionGuide4(const uint16_t* raw, int width,
        int height, int stride, int bits, float gain, float gamma) {
    if(!raw || width<4 || height<4 || width>32760 || height>32760 || stride<width ||
       int64_t(width)*height>16000000 || bits<10 || bits>14 || !std::isfinite(gain) || gain<=0 ||
       !std::isfinite(gamma) || gamma<=0)
        throw std::invalid_argument("Invalid NICE motion guide input");
    const int shift=bits-10;
    auto signal=[&](int x,int y) {
        const int code=std::clamp(int(raw[size_t(y)*stride+x]>>shift),64,1023);
        return int(std::clamp(float(code-64)*gain,0.f,1023.f));
    };
    int64_t sum=0;int count=0;
    for(int y=0;y<height;y+=8)for(int x=0;x<width;x+=8){sum+=signal(x,y);++count;}
    const int mean=std::max(int(sum/count),2);
    const int ratio=130944/mean;
    const int weight=ratio>65479?128:ratio>>3;
    std::array<uint8_t,1024> lut{};
    for(int i=0;i<1024;++i)lut[i]=uint8_t(std::pow(double(i)/1023.0,double(gamma))*255.0);
    std::vector<uint8_t> out(size_t(width/4)*(height/4));
    for(int y=0;y<height/4;++y)for(int x=0;x<width/4;++x){
        int total=0;
        for(int j=0;j<4;++j)for(int i=0;i<4;++i)total+=signal(x*4+i,y*4+j);
        const int index=std::clamp(((total*weight+1024)>>7)/16,0,1023);
        out[size_t(y)*(width/4)+x]=lut[index];
    }
    return out;
}
} // namespace vivo_nice
