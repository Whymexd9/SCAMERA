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
struct CreReferenceInput { std::array<uint8_t, 0x198> bytes; };
struct ToneFaces {
    std::array<int32_t,160> roiRects, jsonRects, maskRects;
    std::array<int32_t,40> roiIds, jsonIds;
    std::array<uint8_t,40> maskValid;
    int32_t count;
};
// Process contains addresses into this storage. Do not move or destroy it until
// the native Process call and all use of its arguments have finished.
struct ToneFaceStorage {
    std::array<int32_t,160> roiRects, jsonRects, maskRects;
    std::array<int32_t,40> roiIds, jsonIds;
    std::array<uint8_t,40> maskValid;
    ToneFaceStorage()=default;
    ToneFaceStorage(const ToneFaceStorage&)=delete;
    ToneFaceStorage& operator=(const ToneFaceStorage&)=delete;
};

template<class T, class Block> inline T toneRead(const Block& b, size_t offset) {
    static_assert(std::is_trivially_copyable<T>::value);
    T value; std::memcpy(&value,b.bytes.data()+offset,sizeof(value)); return value;
}
template<class T> inline void processPut(ProcessArgument& a,size_t offset,T value) {
    static_assert(std::is_trivially_copyable<T>::value);
    std::memcpy(a.bytes.data()+offset,&value,sizeof(value));
}

// CRE 38ccb8..38d138, after the native image accessors. All crop coordinates
// have the native inclusive convention. This is not an Android Rect binding.
inline void bindProcessReference(ProcessArgument& a,ToneFaceStorage& storage,
        const CreReferenceInput& image,const ToneFaces& faces,
        int32_t originalWidth,int32_t originalHeight,
        const std::array<int32_t,2>& roiOffset,const std::array<int32_t,4>& crop) {
    if(faces.count<0 || faces.count>40)
        throw std::invalid_argument("TCE face storage capacity exceeded");
    const size_t count=static_cast<size_t>(faces.count);
    std::memcpy(storage.roiRects.data(),faces.roiRects.data(),count*16);
    std::memcpy(storage.jsonRects.data(),faces.jsonRects.data(),count*16);
    std::memcpy(storage.maskRects.data(),faces.maskRects.data(),count*16);
    std::memcpy(storage.roiIds.data(),faces.roiIds.data(),count*4);
    std::memcpy(storage.jsonIds.data(),faces.jsonIds.data(),count*4);
    std::memcpy(storage.maskValid.data(),faces.maskValid.data(),count);
    auto put=[&](size_t node,auto value){processPut(a,node-0x9d0,value);};
    put(0xd5c,originalWidth);put(0xd60,originalHeight);
    put(0xd64,roiOffset[0]);put(0xd68,roiOffset[1]);
    put(0xaa0,reinterpret_cast<uint64_t>(storage.roiRects.data()));
    put(0xaa8,uint64_t(uint32_t(faces.count)));put(0xab8,faces.count);
    put(0xab0,reinterpret_cast<uint64_t>(storage.roiIds.data()));
    put(0xc50,reinterpret_cast<uint64_t>(storage.jsonRects.data()));
    put(0xc58,uint64_t(uint32_t(faces.count)));
    put(0xc60,reinterpret_cast<uint64_t>(storage.jsonIds.data()));
    put(0xac0,reinterpret_cast<uint64_t>(storage.maskRects.data()));
    put(0xac8,reinterpret_cast<uint64_t>(storage.maskValid.data()));
    put(0xccc,uint32_t(crop[2])-uint32_t(crop[0])+1);
    put(0xcd0,uint32_t(crop[3])-uint32_t(crop[1])+1);
    put(0xcd4,crop[0]);put(0xcd8,crop[1]);
    auto copy=[&](size_t node,size_t source,size_t size) {
        std::memcpy(a.bytes.data()+node-0x9d0,image.bytes.data()+source,size);
    };
    copy(0xa58,0x118,4);copy(0xa60,0x110,8);copy(0xa68,0x110,8);
    copy(0xa70,0x128,4);copy(0xa78,0x120,8);
    copy(0xca8,0x148,4);copy(0xca4,0x14c,4);copy(0xcb0,0x140,8);
    copy(0xcc8,0xb4,4);copy(0xcc4,0xbc,4);copy(0xcc0,0xb8,4);
    copy(0xb04,0xb0,4);put(0xafc,50.f*toneRead<float>(image,0xb4));
    copy(0xaf8,0x7c,4);copy(0xb08,0x78,4);
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
