#pragma once
#include "vivo-nice-tce-contract.h"
#include <cstring>
#include <type_traits>

namespace vivo_nice::tce_contract {
struct ProcessArgument {
    alignas(8) std::array<uint8_t, processArgumentBytes> bytes;
};

// Native CRE input views, not wire formats. Embedded addresses borrow storage
// from the same process. Keeping unnamed fields opaque avoids inventing camera
// metadata equivalents. The caller must provide the complete initialized input.
struct CreSceneInput { std::array<uint8_t, 0x1970> bytes; };
struct CreColorInput { std::array<uint8_t, 0x14c8> bytes; };

template<class T, class Block> inline T toneRead(const Block& b, size_t offset) {
    static_assert(std::is_trivially_copyable<T>::value);
    T value; std::memcpy(&value,b.bytes.data()+offset,sizeof(value)); return value;
}
template<class T> inline void processPut(ProcessArgument& a,size_t offset,T value) {
    static_assert(std::is_trivially_copyable<T>::value);
    std::memcpy(a.bytes.data()+offset,&value,sizeof(value));
}

// Complete scene/color writes of CRE 38d140..38d4b4. Image, face and AE binding
// are separate producers. Preserves all bytes not written by this function,
// including the donor's asymmetric absent-color and disabled-LUT branches.
inline void bindProcessScene(ProcessArgument& destination,const CreSceneInput& s,
                             const CreColorInput* color,float referenceEv0) {
    const float ev = processExposureEv(referenceEv0,toneRead<float>(s,0x1814));
    ProcessArgument a=destination;
    auto copy=[&](size_t node,size_t source,size_t size) {
        std::memcpy(a.bytes.data()+node-0x9d0,s.bytes.data()+source,size);
    };
    copy(0xa90,0x1860,4); copy(0xad0,0x189c,4); copy(0xa98,0x1814,4);
    copy(0xa8c,0x17f0,4); copy(0xa9c,0x1868,4); copy(0xc18,0x1958,4);
    processPut(a,0xd54-0x9d0,uint32_t(1)-toneRead<uint32_t>(s,0x174));
    copy(0xc80,0x1950,8); processPut(a,0x78,ev);
    copy(0xc38,0x1920,4); copy(0xc30,0x1918,8);
    copy(0xc20,0x1908,16); copy(0xc3c,0x1924,16);
    copy(0xc68,0x1934,16); copy(0xc78,0x1944,8);
    if(!color) {
        copy(0xa94,0x1860,4);
        processPut(a,0xd48-0x9d0,uint64_t(0));
        processPut(a,0xd50-0x9d0,uint32_t(0));
        processPut(a,0xc94-0x9d0,uint64_t(0));
        processPut(a,0xc8c-0x9d0,uint64_t(0));
        processPut(a,0xd90-0x9d0,uint64_t(0));
        processPut(a,0xda0-0x9d0,uint64_t(0));
        processPut(a,0xd98-0x9d0,uint64_t(0));
    } else {
        auto fromColor=[&](size_t node,size_t source,size_t size) {
            std::memcpy(a.bytes.data()+node-0x9d0,color->bytes.data()+source,size);
        };
        fromColor(0xa94,0x1478,4);
        if(toneRead<uint8_t>(*color,0x1480)&2) {
            fromColor(0xd48,0x1470,8); fromColor(0xd40,0x1468,4);
            const uint32_t n=toneRead<uint32_t>(*color,0x1468);
            processPut(a,0xd44-0x9d0,n*n*n*uint32_t(3));
            fromColor(0xd98,0x14a4,4); fromColor(0xd90,0x14b0,8);
        } else {
            processPut(a,0xd98-0x9d0,uint32_t(0));
            processPut(a,0xd48-0x9d0,uint64_t(0));
            processPut(a,0xd40-0x9d0,uint64_t(0));
            processPut(a,0xd90-0x9d0,uint64_t(0));
        }
        for(size_t i=0;i<6;++i)
            processPut(a,0xd10-0x9d0+8*i,double(toneRead<float>(*color,0x1450+4*i)));
        processPut(a,0xc88-0x9d0,uint8_t(toneRead<uint32_t>(*color,0x148c)!=0));
        fromColor(0xc8c,0x1488,4); fromColor(0xc90,0x1490,4);
        fromColor(0xc94,0x1494,4); fromColor(0xc98,0x1498,4);
        fromColor(0xd50,0x147c,4); fromColor(0xd9c,0x14bc,4);
        fromColor(0xda0,0x14c0,8); fromColor(0xd58,0x149c,4);
    }
    destination=a;
}
} // namespace vivo_nice::tce_contract
