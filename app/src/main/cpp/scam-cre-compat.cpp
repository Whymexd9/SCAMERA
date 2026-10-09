// Stand-ins for the vivo vendor libraries libvivo_nice_cre.so links against, so the
// bundled CRE copy can be loaded on devices without them (OPPO etc.). Only the CPU
// motion detector/tracker is used; the shared-buffer and memory-pool entry points
// belong to CRE's GPU/ION paths. Each of those reports its name and aborts if it is
// ever reached, so an unexpected call fails loudly instead of corrupting memory.
// Built three times with -DCOMPAT_LOG / -DCOMPAT_PLATFORM / -DCOMPAT_MEMPOOL and the
// matching -soname.
#include <cstdio>
#include <cstdlib>

namespace {
[[noreturn]] void unsupported(const char* name) {
    std::fprintf(stderr, "SCAMERA CRE compat: unsupported vivo call %s\n", name);
    std::fflush(stderr);
    std::abort();
}
}

#define UNSUPPORTED(name) extern "C" __attribute__((visibility("default"))) void name() { unsupported(#name); }

#ifdef COMPAT_LOG
// Logging is harmless to drop.
extern "C" __attribute__((visibility("default"))) int __v_android_log_print(int, const char*, const char*, ...) { return 0; }
#endif

#ifdef COMPAT_PLATFORM
UNSUPPORTED(scamCreateShareBufAllocHandler)
UNSUPPORTED(scamReleaseShareBufAllocHandler)
UNSUPPORTED(scamShareBufAllocWithCache)
UNSUPPORTED(scamShareBufCPUSyncEnd)
UNSUPPORTED(scamShareBufCPUSyncStart)
UNSUPPORTED(scamShareBufRelease)
#endif

#ifdef COMPAT_MEMPOOL
UNSUPPORTED(CreateMemPool)
UNSUPPORTED(DestroyMemPool)
UNSUPPORTED(MemPoolAlloc)
UNSUPPORTED(MemPoolCpuCacheSyncEnd)
UNSUPPORTED(MemPoolCpuCacheSyncStart)
UNSUPPORTED(MemPoolFree)
UNSUPPORTED(MemPoolGetCL2Context)
UNSUPPORTED(MemPoolGetCL2Device)
UNSUPPORTED(MemPoolGetCL2Quene)
UNSUPPORTED(MemPoolGetCLMem)
UNSUPPORTED(MemPoolGetCLPlatformId)
UNSUPPORTED(MemPoolGetMemBlock)
UNSUPPORTED(MemPoolclCreateSubBuffer)
UNSUPPORTED(SetMemPoolSize)
#endif
