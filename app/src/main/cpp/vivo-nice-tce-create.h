#pragma once
#include "vivo-nice-tce-contract.h"
#include <cstring>
#include <type_traits>

namespace vivo_nice::tce_contract {
// Recovered writes of CRE 38c19c..38c414 (within 38c16c). Not a default-filled Create
// argument: the unassigned bytes must come from the caller's complete context.
// Every string address is borrowed and must remain live until TCE destruction.
struct CreateArgument {
    alignas(8) std::array<uint8_t, createArgumentBytes> bytes;
};
struct CreateBindings {
    int32_t debugLevel;
    uint64_t modelDir, configXml, effectXml, segmentConfig, allInOneConfig;
    uint64_t skyConfig, sunConfig, dumpDir, dumpPrefix, speConfig, faceConfig, outlineConfig;
    int32_t mode, photoMode, args1c, args18;
    uint64_t gpuBinaryPath;
    int32_t config108, referenceEntryCount;
    float uiZoom;
    // Raw camera-info bytes, preserving the donor's layout and embedded name.
    std::array<uint8_t, 0x68> cameraInfo;
    std::array<uint8_t, 0x20> sceneInfo;
    bool hasColorInfo;
    float colorInfoZoom;
};
template<class T> inline void createPut(CreateArgument& a,size_t offset,const T& value) {
    static_assert(std::is_trivially_copyable<T>::value);
    std::memcpy(a.bytes.data()+offset,&value,sizeof(value));
}
inline void bindCreateArguments(CreateArgument& a,const CreateBindings& b) {
    createPut(a,0,b.debugLevel);
    const uint64_t paths[]={b.modelDir,b.configXml,b.effectXml,b.segmentConfig,
        b.allInOneConfig,b.skyConfig,b.sunConfig,b.dumpDir,b.dumpPrefix,
        b.speConfig,b.faceConfig,b.outlineConfig};
    for(size_t i=0;i<12;++i)createPut(a,8+8*i,paths[i]);
    std::memcpy(a.bytes.data()+0x68,b.cameraInfo.data(),b.cameraInfo.size());
    createPut(a,0xd0,b.photoMode);createPut(a,0xd4,b.mode);
    std::memcpy(a.bytes.data()+0xd8,b.sceneInfo.data(),b.sceneInfo.size());
    createPut(a,0xf8,b.referenceEntryCount);createPut(a,0xfc,b.uiZoom);
    if(b.hasColorInfo)createPut(a,0xfc,b.colorInfoZoom);
    createPut(a,0x100,b.gpuBinaryPath);createPut(a,0x108,b.config108);
    std::memset(a.bytes.data()+0x110,0,0x64);
    createPut(a,0x218,uint64_t(0));
    createPut(a,0x220,b.args1c);createPut(a,0x228,b.args18);
}
} // namespace vivo_nice::tce_contract
