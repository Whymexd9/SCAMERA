#pragma once
#include <array>
#include <cstddef>
#include <cstdint>
#include <cstring>

// Pinned VAF adapter 0x4b80..0x4c20, not a public/stable vendor API.
namespace vivo_softpqe {
struct alignas(8) Init {
    std::array<uint8_t, 0x268> bytes{};
    template<class T> void put(size_t offset, T value) {
        std::memcpy(bytes.data()+offset, &value, sizeof(value));
    }
};
inline Init makeInit(uint32_t width, uint32_t height, float gain,
                     const char* config, const char* models) {
    Init p;
    p.put<uint32_t>(0x04,height);p.put<uint32_t>(0x08,width);
    p.put<uint32_t>(0x10,height*2);p.put<uint32_t>(0x14,width*2);
    p.put<uint32_t>(0x30,8);p.put<uint32_t>(0x34,2); // 8-bit, master SAT role
    p.put<float>(0x40,1.f);p.put<float>(0x64,gain);
    // No original capture metadata at the final Bitmap stage: neutral assumptions.
    p.put<float>(0x68,1.f);p.put<float>(0x6c,1.f);
    p.put<float>(0x70,1.f);p.put<float>(0x74,1.f); // DRC gain, UI zoom
    p.put<const char*>(0x100,config);p.put<const char*>(0x108,models);
    p.put<uint32_t>(0x248,width);p.put<uint32_t>(0x24c,height); // full-frame crop
    p.put<float>(0x254,1.f); // digital zoom; no quad, faces, masks or mempool
    return p;
}
struct alignas(8) Process {
    const void* input;
    std::array<uint8_t,0x180> masks{};
    uint32_t maskCount=0, padding=0, bits=8;
};
static_assert(sizeof(Init)==0x268 && sizeof(Process)==0x198, "SoftPQE ARM64 ABI");
static_assert(offsetof(Process,bits)==0x190, "SoftPQE process bits offset");
}
