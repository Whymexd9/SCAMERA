#pragma once
#include "vivo-nice-shared-buffer.h"
#include "vivo-nice-tce-session.h"
#include <dlfcn.h>
#include <string>

namespace vivo_nice {
// Used only in the native worker, outside the app JNI linker namespace.
// Entry offsets are an additional ABI check; the launcher must verify the full
// donor SHA256 before activating this path, just as for the stock motion code.
class NativeToneRuntime {
    std::shared_ptr<void> platformLibrary,tceLibrary;
    static std::shared_ptr<void> open(const char* path) {
        void* handle=dlopen(path,RTLD_NOW|RTLD_LOCAL);
        if(!handle) {
            const char* error=dlerror();
            throw std::runtime_error(std::string("Cannot load Vivo tone dependency: ")+
                path+": "+(error?error:"unknown loader error"));
        }
        return {handle,[](void* p){dlclose(p);}};
    }
    template<class F> static F symbol(const std::shared_ptr<void>& library,
                                      const char* name,uintptr_t expected) {
        void* address=dlsym(library.get(),name);Dl_info info{};
        if(!address || !dladdr(address,&info) || !info.dli_fbase ||
           reinterpret_cast<uintptr_t>(address)-reinterpret_cast<uintptr_t>(info.dli_fbase)!=expected)
            throw std::runtime_error(std::string("Unsupported Vivo tone ABI: ")+name);
        return reinterpret_cast<F>(address);
    }
public:
    SharedBufferApi buffers;
    TceApi tone;
    NativeToneRuntime()
        :platformLibrary(open("/vendor/lib64/libvivo_platform_common.so")),
         tceLibrary(open("/vendor/lib64/libvivo_nicetce.so")) {
        buffers.create=symbol<decltype(buffers.create)>(platformLibrary,"vivoCreateShareBufAllocHandler",0x8020);
        buffers.allocate=symbol<decltype(buffers.allocate)>(platformLibrary,"vivoShareBufAllocWithCache",0x8430);
        buffers.release=symbol<decltype(buffers.release)>(platformLibrary,"vivoShareBufRelease",0x84e8);
        buffers.destroy=symbol<decltype(buffers.destroy)>(platformLibrary,"vivoReleaseShareBufAllocHandler",0x86a4);
        buffers.syncStart=symbol<decltype(buffers.syncStart)>(platformLibrary,"vivoShareBufCPUSyncStart",0x8734);
        buffers.syncEnd=symbol<decltype(buffers.syncEnd)>(platformLibrary,"vivoShareBufCPUSyncEnd",0x8744);
        buffers.libraryLifetime=platformLibrary;
        tone.create=symbol<decltype(tone.create)>(tceLibrary,"vivoNiceTceCreate",0x38424c);
        tone.process=symbol<decltype(tone.process)>(tceLibrary,"vivoNiceTceProcess",0x391340);
        tone.destroy=symbol<decltype(tone.destroy)>(tceLibrary,"vivoNiceTceDestroy",0x385988);
        tone.setParam=symbol<decltype(tone.setParam)>(tceLibrary,"vivoNiceTceSetParam",0x39751c);
        tone.libraryLifetime=tceLibrary;
    }
};
} // namespace vivo_nice
