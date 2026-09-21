#pragma once
#include "vivo-nice-tce-create.h"
#include "vivo-nice-tce-process.h"
#include <stdexcept>
#include <string>
#include <memory>

namespace vivo_nice {
struct TceApi {
    void* (*create)(void*);
    int (*process)(void*,void*,void*);
    int (*destroy)(void*);
    std::shared_ptr<void> libraryLifetime{};
    int (*setParam)(void*,int32_t,void*)=nullptr;
};

// CRE uses output descriptors inside a 0x6e0-byte node region. Keep that region
// rather than treating the known 0x2e0-byte prefix as the complete TCE ABI.
struct TceOutputStorage {
    tce_contract::OutputPrefix prefix;
    std::array<uint8_t,0x6e0-sizeof(tce_contract::OutputPrefix)> remainder;
};
static_assert(sizeof(TceOutputStorage)==0x6e0);

// Complete initialized arguments must be provided by the capture adapter.
// Paths, LUTs and buffers remain borrowed and must outlive the session. This
// wrapper does not manufacture absent scene information or enable capture.
class TceSession {
    TceApi api;
    tce_contract::CreateArgument creation;
    void* handle=nullptr;
public:
    TceSession(TceApi functions,const tce_contract::CreateArgument& initialized)
        :api(functions),creation(initialized) {
        if(!api.create || !api.process || !api.destroy)
            throw std::invalid_argument("Incomplete Vivo TCE API");
        handle=api.create(creation.bytes.data());
        if(!handle)throw std::runtime_error("Vivo TCE Create failed");
    }
    TceSession(const TceSession&)=delete;
    TceSession& operator=(const TceSession&)=delete;
    ~TceSession(){if(handle)api.destroy(handle);}
    void setCopiedParameters(int32_t parameter4, std::array<uint8_t,0x55> parameter8) {
        if(!api.setParam)throw std::runtime_error("Missing Vivo TCE SetParam API");
        // These two donor branches copy their payloads; other keys may retain pointers.
        int status=api.setParam(handle,4,&parameter4);
        if(status)throw std::runtime_error("Vivo TCE SetParam 4 failed: "+std::to_string(status));
        status=api.setParam(handle,8,parameter8.data());
        if(status)throw std::runtime_error("Vivo TCE SetParam 8 failed: "+std::to_string(status));
    }
    void process(const tce_contract::ProcessArgument& initialized,TceOutputStorage& output) {
        // TCE mutates input zoom and image handles. Reusing that mutation for
        // another frame would compound zoom; always start from fresh arguments.
        auto argument=initialized;
        const int status=api.process(argument.bytes.data(),&output,handle);
        if(status)throw std::runtime_error("Vivo TCE Process failed: "+std::to_string(status));
    }
};
} // namespace vivo_nice
