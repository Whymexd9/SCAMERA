#pragma once
#include "vivo-neural-runtime.h"

// Separate pinned QNN 2.29.8/System 1.2 session. Never mix with TELE/QNN 2.25
// in one process. Tensor V1 and provider prefixes checked against supplied ELF.
namespace vivo_hexquad {
using namespace vivo_nn;
class HexSession {
    std::vector<void*> libraries;
    const Provider* api=nullptr; const SystemProvider* sys=nullptr;
    Handle backend=nullptr,device=nullptr,context=nullptr,metadata=nullptr,graph=nullptr;
    std::vector<uint8_t> model;
    Tensor in{},out{};
    unsigned executions=0;
    template<class T> T fn(int i) { if(!api->slots[i]) throw std::runtime_error("Missing QNN function"); return reinterpret_cast<T>(api->slots[i]); }
    void* load(const char* p) {
        log(std::string("LOAD: ")+p); void* h=dlopen(p,RTLD_NOW|RTLD_LOCAL);
        if(!h) { const char* error=dlerror(); throw std::runtime_error(error?error:"dlopen failed"); }
        libraries.push_back(h); return h;
    }
    static Tensor tensor(Tensor* t,uint32_t side,uint32_t channels,uint32_t expectedType) {
        if(!t || t->version!=1) throw std::runtime_error("Expected firmware tensor version 1");
        const auto& v=t->v1;
        if(v.type!=expectedType || v.format!=0 || v.dataType!=0x232 || v.rank!=4 || !v.dimensions || !v.name)
            throw std::runtime_error("Unsupported tensor descriptor");
        if(v.dimensions[0]!=1 || v.dimensions[1]!=side || v.dimensions[2]!=side || v.dimensions[3]!=channels)
            throw std::runtime_error("Unexpected network dimensions");
        log("TENSOR: "+std::string(v.name)+" id="+std::to_string(v.id)+" FLOAT32 1x"+std::to_string(side)+"x"+std::to_string(side)+"x"+std::to_string(channels));
        // Use the public V1 descriptor for this single, dense, static tensor.
        // No array stride from a newer/larger tensor record is assumed.
        return Tensor{1,v};
    }
public:
    std::vector<float> input,output;
    explicit HexSession(int scale) : input(288*288*18),output(size_t(288)*288*3*(scale==2?4:1)), scale_(scale) { if(scale!=1 && scale!=2)throw std::runtime_error("Invalid HexQuad scale"); }
    int scale_;
    void init(const std::string& directory) {
        model=read(directory+(scale_==1?"/hexquad-x1-v79.bin":"/hexquad-x2-v79.bin"));
        if(model.size()!=(scale_==1?7931360u:9758632u)) throw std::runtime_error("Unexpected embedded model length");
        auto system=load((directory+"/libQnnSystem.so").c_str());
        auto getSystem=reinterpret_cast<Error(*)(const SystemProvider***,uint32_t*)>(dlsym(system,"QnnSystemInterface_getProviders"));
        if(!getSystem) throw std::runtime_error("No System provider entry");
        const SystemProvider** systems=nullptr;uint32_t n=0;check(getSystem(&systems,&n),"System providers");
        if(!systems||!n||n>16) throw std::runtime_error("System provider count");
        for(uint32_t i=0;i<n;i++) if(systems[i]&&systems[i]->version.major==1&&systems[i]->version.minor==2)sys=systems[i];
        if(!sys||!sys->create||!sys->info||!sys->free)throw std::runtime_error("System 1.2 required");
        Handle created=nullptr;check(sys->create(&created),"System create");metadata=created;
        const void* info=nullptr;uint64_t bytes=0;check(sys->info(metadata,model.data(),model.size(),&info,&bytes),"System metadata");
        // The supplied System 1.2 returns a valid owned metadata pointer but
        // leaves infoSize=0. Confirmed by executing its ARM64 parser on host.
        if(!info||(bytes && bytes<8))throw std::runtime_error("Empty binary metadata");
        uint32_t version;std::memcpy(&version,info,4);log("BINARY INFO: v"+std::to_string(version));
        GraphPrefix* g=nullptr;uint32_t graphs=0;
        auto body=static_cast<const uint8_t*>(info)+8;
        if((version==1||version==2)&&(!bytes||bytes>=8+sizeof(BinaryV1Prefix))) {auto b=reinterpret_cast<const BinaryV1Prefix*>(body);g=b->graph;graphs=b->graphs;}
        else if(version==3&&(!bytes||bytes>=8+sizeof(BinaryV3Prefix))){auto b=reinterpret_cast<const BinaryV3Prefix*>(body);g=b->graph;graphs=b->graphs;}
        else throw std::runtime_error("Unsupported binary metadata version/size");
        if(graphs!=1||!g||g->version<1||g->version>3||g->inputs!=1||g->outputs!=1||!g->name)
            throw std::runtime_error("Unexpected graph metadata");
        if(std::strcmp(g->name,scale_==1?"vmcc_1000_quant_8w16a32b":"vmcc_2000_quant_8w16a32b"))throw std::runtime_error("Wrong graph name");
        log("GRAPH: "+std::string(g->name));
        in=tensor(g->input,288,18,0);out=tensor(g->output,288*scale_,3,1);
        // FastRPC is the device driver interface, not a Vivo algorithm. It
        // must match the running phone; never ship another phone's driver.
        log("DRIVER: platform FastRPC (device compatibility required)");
        load("libcdsprpc.so");
        load((directory+"/libQnnHtpV79Stub.so").c_str());
        auto htp=load((directory+"/libQnnHtp.so").c_str());
        auto get=reinterpret_cast<Error(*)(const Provider***,uint32_t*)>(dlsym(htp,"QnnInterface_getProviders"));
        if(!get)throw std::runtime_error("No HTP providers");
        const Provider** providers=nullptr;n=0;check(get(&providers,&n),"HTP providers");
        if(!providers||!n||n>16)throw std::runtime_error("HTP provider count");
        for(uint32_t i=0;i<n;i++)if(providers[i]&&providers[i]->core.major==2&&providers[i]->core.minor==22&&providers[i]->core.patch==0)api=providers[i];
        if(!api||api->id!=6)throw std::runtime_error("Expected HTP Core API 2.22.0");
        for(int slot:{1,4,8,13,14,20,21,40,43})if(!api->slots[slot])throw std::runtime_error("Missing required QNN function");
        const char* build=nullptr;check(fn<Error(*)(const char**)>(4)(&build),"Build ID");log(std::string("SDK: ")+(build?build:"unknown"));
        if(!build || std::strcmp(build,"v2.29.8.250123143957_105779"))throw std::runtime_error("Unverified HTP build");
        created=nullptr;check(fn<Error(*)(Handle,const void**,Handle*)>(1)(nullptr,nullptr,&created),"Backend create");backend=created;
        created=nullptr;check(fn<Error(*)(Handle,const void**,Handle*)>(40)(nullptr,nullptr,&created),"Device create");device=created;
        created=nullptr;check(fn<Error(*)(Handle,Handle,const void**,const void*,uint64_t,Handle*,Handle)>(13)(backend,device,nullptr,model.data(),model.size(),&created,nullptr),"Context create");context=created;
        check(fn<Error(*)(Handle,const char*,Handle*)>(20)(context,g->name,&graph),"Graph retrieve");
        if(!backend||!device||!context||!graph)throw std::runtime_error("QNN returned an empty handle");
        in.v1.memType=0;in.v1.client={input.data(),static_cast<uint32_t>(input.size()*4)};
        out.v1.memType=0;out.v1.client={output.data(),static_cast<uint32_t>(output.size()*4)};
    }
    void execute() {
        // captureHex swaps only COMPLETED input storage, between synchronous
        // calls. Refresh public client descriptors before each graphExecute.
        if(input.size()!=288u*288u*18||output.size()!=size_t(288)*288*3*scale_*scale_)
            throw std::runtime_error("HexQuad client buffer shape changed");
        in.v1.client={input.data(),static_cast<uint32_t>(input.size()*sizeof(float))};
        out.v1.client={output.data(),static_cast<uint32_t>(output.size()*sizeof(float))};
        poisonOutput(output);
        ++executions;
        if(executions<=8)log("GRAPH EXECUTE #"+std::to_string(executions)+": begin");
        check(fn<Error(*)(Handle,const Tensor*,uint32_t,Tensor*,uint32_t,Handle,Handle)>(21)(graph,&in,1,&out,1,nullptr,nullptr),"Graph execute");
        if(executions<=8)log("GRAPH EXECUTE #"+std::to_string(executions)+": status=0");
        for(float value:output)if(!std::isfinite(value))throw OutputError("HexQuad output nonfinite or unwritten");
    }
    ~HexSession() {
        // The process exits after one job; do not dlclose live vendor runtime code.
        if(api){if(context && api->slots[14])reinterpret_cast<Error(*)(Handle,Handle)>(api->slots[14])(context,nullptr);if(device && api->slots[43])reinterpret_cast<Error(*)(Handle)>(api->slots[43])(device);if(backend && api->slots[8])reinterpret_cast<Error(*)(Handle)>(api->slots[8])(backend);}
        if(sys&&metadata)sys->free(metadata);
    }
};

}
