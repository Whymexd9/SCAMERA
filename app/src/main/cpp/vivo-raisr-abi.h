#pragma once
#include <cstdint>
#include <cstddef>
#include <cstring>
#include <stdexcept>
#include <string>
namespace vivo_raisr {
// Pinned VAF fillInitParams 0x4a88..0x4b24; destination is object+0x20.
struct Init {
    uint32_t height, width, outHeight, outWidth, cropType;
    int32_t left, top, right, bottom;
    float zoom;
    uint32_t role;
    char modelPath[0x104];
    uint32_t debug, disable, iso, faceCount;
    uint8_t reserved[0x18];
    uint8_t affinity[8];
};
static_assert(sizeof(Init)==0x160 && offsetof(Init,modelPath)==0x2c &&
              offsetof(Init,iso)==0x138 && offsetof(Init,affinity)==0x158,"RAISR ABI");
inline Init makeInit(uint32_t w,uint32_t h,uint32_t ow,uint32_t oh,uint32_t iso,
                     uint32_t role,const std::string& models) {
    Init p{};p.width=w;p.height=h;p.outWidth=ow;p.outHeight=oh;
    p.cropType=2;p.zoom=float(ow)/w;p.role=role;p.iso=iso;p.debug=4;
    if(models.size()>=sizeof(p.modelPath))throw std::runtime_error("Model path too long");
    std::memcpy(p.modelPath,models.c_str(),models.size()+1);return p;
}
}
