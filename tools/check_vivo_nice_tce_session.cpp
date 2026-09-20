#include "vivo-nice-tone-runtime.h"
#include <cassert>
#include <vector>
using namespace vivo_nice;
using namespace vivo_nice::tce_contract;
static int nativeHandle;
static std::vector<int> events;
static bool failCreate=false,failProcess=false;
static void* create(void* argument) {
    events.push_back(1);auto* bytes=static_cast<uint8_t*>(argument);
    assert(bytes[0]==0x42);bytes[0]=0x24;
    return failCreate?nullptr:&nativeHandle;
}
static int process(void* argument,void* output,void* handle) {
    assert(handle==&nativeHandle);events.push_back(2);
    float zoom;std::memcpy(&zoom,static_cast<uint8_t*>(argument)+0x104,4);
    assert(zoom==1.5f);zoom*=2;
    std::memcpy(static_cast<uint8_t*>(argument)+0x104,&zoom,4);
    static_cast<TceOutputStorage*>(output)->prefix.toneMode=7;
    return failProcess?9:0;
}
static int destroy(void* handle) {assert(handle==&nativeHandle);events.push_back(3);return 0;}
int main() {
    CreateArgument creation{};creation.bytes[0]=0x42;
    ProcessArgument input{};processPut(input,0x104,1.5f);TceOutputStorage output{};
    TceApi api{create,process,destroy};
    api.libraryLifetime=std::shared_ptr<void>(&nativeHandle,[](void*){events.push_back(4);});
    {
        TceSession session(api,creation);api.libraryLifetime.reset();
        session.process(input,output);session.process(input,output);
        assert(toneRead<float>(input,0x104)==1.5f&&creation.bytes[0]==0x42);
        assert(output.prefix.toneMode==7);
    }
    assert((events==std::vector<int>{1,2,2,3,4}));events.clear();
    failCreate=true;
    try{TceSession session(api,creation);assert(false);}catch(const std::runtime_error&){}
    assert((events==std::vector<int>{1}));events.clear();failCreate=false;
    failProcess=true;
    try{TceSession session(api,creation);session.process(input,output);assert(false);}
    catch(const std::runtime_error&){}
    assert((events==std::vector<int>{1,2,3}));
}
