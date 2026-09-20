#pragma once
#include <cstddef>
#include <cstdint>
#include <stdexcept>
#include <memory>

namespace vivo_nice {
// libvivo_platform_common.so 530eb4c1... . Creation returns a handle, while
// allocation/release/destruction take its ADDRESS. CPU sync takes the handle.
struct SharedBufferApi {
    using SyncCallback=int(*)(int,int,void*);
    void* (*create)();
    int (*allocate)(void**,size_t,int,int*,void**);
    int (*release)(void**,int*,void**);
    int (*destroy)(void**);
    int (*syncStart)(void*,int,int,SyncCallback,void*);
    int (*syncEnd)(void*,int,int,SyncCallback,void*);
    std::shared_ptr<void> libraryLifetime{};
};

// The API's shared library must remain loaded longer than this owner. The
// TCE context and imported GPU handles must be released before its buffers.
class SharedBuffer {
    SharedBufferApi api;
    void* handler=nullptr;
    void* address=nullptr;
    int descriptor=-1;
    size_t length;
    bool cpuAccess=false;
    void cleanup() noexcept {
        if(!handler)return;
        if(cpuAccess) { api.syncEnd(handler,descriptor,3,nullptr,nullptr);cpuAccess=false; }
        if(address || descriptor>=0)api.release(&handler,&descriptor,&address);
        api.destroy(&handler);
        handler=nullptr;address=nullptr;descriptor=-1;
    }
public:
    SharedBuffer(SharedBufferApi functions,size_t bytes,bool cached):api(functions),length(bytes) {
        if(!bytes || !api.create || !api.allocate || !api.release || !api.destroy
            || !api.syncStart || !api.syncEnd)throw std::invalid_argument("Invalid Vivo shared-buffer API");
        handler=api.create();
        if(!handler)throw std::runtime_error("Vivo shared-buffer handler creation failed");
        const int result=api.allocate(&handler,bytes,cached?1:0,&descriptor,&address);
        if(result || !address || address==reinterpret_cast<void*>(~uintptr_t(0)) || descriptor<0) {
            cleanup();throw std::runtime_error("Vivo shared-buffer allocation failed");
        }
    }
    ~SharedBuffer(){cleanup();}
    SharedBuffer(const SharedBuffer&)=delete;
    SharedBuffer& operator=(const SharedBuffer&)=delete;
    int fd()const noexcept{return descriptor;}
    size_t size()const noexcept{return length;}
    // Borrow for native image descriptors, without authorizing CPU access.
    void* nativeAddress()const noexcept{return address;}
    void* beginCpuAccess() {
        if(cpuAccess)throw std::logic_error("Nested Vivo CPU access");
        if(api.syncStart(handler,descriptor,3,nullptr,nullptr))
            throw std::runtime_error("Vivo CPU sync start failed");
        cpuAccess=true;return address;
    }
    void endCpuAccess() {
        if(!cpuAccess)throw std::logic_error("Vivo CPU access was not started");
        if(api.syncEnd(handler,descriptor,3,nullptr,nullptr))
            throw std::runtime_error("Vivo CPU sync end failed");
        cpuAccess=false;
    }
};
} // namespace vivo_nice
