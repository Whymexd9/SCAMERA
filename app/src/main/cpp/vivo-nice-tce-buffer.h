#pragma once
#include "vivo-nice-shared-buffer.h"
#include "vivo-nice-tce-contract.h"
#include <cstring>
#include <limits>

namespace vivo_nice {
// Interleaved RGB16, CRE allocator format 0x1004, depth 14. The image's
// unknown60 prefix is its shared-memory fd (CRE descriptor+28 -> TCE+60).
class TceRgbBuffer {
    static size_t checkedSize(int width,int height) {
        if(width<=0 || height<=0 || uint64_t(width)*uint64_t(height)*6>
                uint64_t(std::numeric_limits<int32_t>::max()))
            throw std::invalid_argument("TCE RGB allocation extent overflow");
        return size_t(width)*size_t(height)*6;
    }
    SharedBuffer memory;
    tce_contract::Image descriptor{};
public:
    TceRgbBuffer(SharedBufferApi api,int width,int height)
        :memory(api,checkedSize(width,height),false) {
        descriptor.format=0x1004;
        descriptor.width=width;descriptor.height=height;
        descriptor.data[0]=reinterpret_cast<uint64_t>(memory.nativeAddress());
        descriptor.stride[0]=width*6;descriptor.scanline[0]=height;
        const int fd=memory.fd();
        std::memcpy(descriptor.unknown60.data(),&fd,sizeof(fd));
        // CRE 392d70..392d7c explicitly supplies zero dataSize and nativeHandle.
    }
    TceRgbBuffer(const TceRgbBuffer&)=delete;
    TceRgbBuffer& operator=(const TceRgbBuffer&)=delete;
    tce_contract::Image& image()noexcept{return descriptor;}
    const tce_contract::Image& image()const noexcept{return descriptor;}
    uint16_t* beginCpuAccess(){return static_cast<uint16_t*>(memory.beginCpuAccess());}
    void endCpuAccess(){memory.endCpuAccess();}
};
} // namespace vivo_nice
