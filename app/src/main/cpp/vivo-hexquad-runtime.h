#pragma once
#include "qnn-htp-perf.h"
#include "vivo-neural-runtime.h"

// Separate pinned QNN 2.29.8/System 1.2 session. Never mix with TELE/QNN 2.25
// in one process. Tensor V1 and provider prefixes checked against supplied ELF.
namespace vivo_hexquad {
using namespace vivo_nn;
// One pinned context binary: file, byte length, graph name and NHWC tensor shape.
struct NetSpec {
    const char* file; size_t bytes; const char* graph;
    uint32_t side, inChannels, outSide;
};
inline NetSpec hexSpec(int scale) {
    if(scale!=1 && scale!=2)throw std::runtime_error("Invalid HexQuad scale");
    return scale==1?NetSpec{"/hexquad-x1-v79.bin",7931360u,"vmcc_1000_quant_8w16a32b",288,18,288}
                   :NetSpec{"/hexquad-x2-v79.bin",9758632u,"vmcc_2000_quant_8w16a32b",288,18,576};
}
// Main camera IMX06C 2x2 Quad (2x ISZ): vendor nice_ldr_imx06c_general_quad_x1,
// 4 frames x RGB sparse input at 544, x1 RGB output (NiceCREConfigQuad.xml).
// model 1: tele HP9 2x ISZ, nice_ldr_hp9_general_roi_quad_x1 (TeleCamera
// NiceCREConfigROIQuad.xml, digital zoom < 1.35, same 4-frame 544 layout).
// model 2: same tele layout, roi_quad_x1_highdrc; the vendor uses it above ISO 2000
// (roi_quad_x1 carries maxiso="2000").
inline NetSpec quadSpec(int model=0) {
    if(model==2)return NetSpec{"/quad-hp9-highdrc-x1-v79.bin",5704792u,"nice_ldr_hp9_general_roi_quad_x1_highdrc_quant_8w16a32b",544,12,544};
    if(model==1)return NetSpec{"/quad-hp9-x1-v79.bin",5704776u,"nice_ldr_hp9_general_roi_quad_x1_quant_8w16a32b",544,12,544};
    return NetSpec{"/quad-x1-v79.bin",5680384u,"nicenormal_quant_8w16a32b",544,12,544};
}
class HexSession {
    std::vector<void*> libraries;
    const Provider* api=nullptr; const SystemProvider* sys=nullptr;
    Handle backend=nullptr,device=nullptr,context=nullptr,metadata=nullptr,graph=nullptr;
    std::vector<uint8_t> model;
    Tensor in{},out{};
    unsigned executions=0;
    // Quantized (UFIXED_16) graph I/O: real = (q + offset) * scale per tensor.
    struct Quantizer { bool on=false; float scale=1; int32_t offset=0; std::vector<uint16_t> data; };
    Quantizer inQ,outQ;
    static void quantizer(const Tensor& t,Quantizer& q,size_t count,const char* what) {
        q.on=t.v1.dataType==0x416;if(!q.on)return;
        std::memcpy(&q.scale,t.v1.quant.payload,4);std::memcpy(&q.offset,t.v1.quant.payload+4,4);
        if(!std::isfinite(q.scale)||q.scale<=0)throw std::runtime_error("Invalid tensor quantization scale");
        q.data.resize(count);
        log(std::string("QUANT ")+what+": scale="+std::to_string(q.scale)+" offset="+std::to_string(q.offset));
    }
    void bind() {
        in.v1.client=inQ.on?ClientBuffer{inQ.data.data(),uint32_t(inQ.data.size()*2)}:ClientBuffer{input.data(),uint32_t(input.size()*4)};
        out.v1.client=outQ.on?ClientBuffer{outQ.data.data(),uint32_t(outQ.data.size()*2)}:ClientBuffer{output.data(),uint32_t(output.size()*4)};
    }
    template<class T> T fn(int i) { if(!api->slots[i]) throw std::runtime_error("Missing QNN function"); return reinterpret_cast<T>(api->slots[i]); }
    void* load(const char* p) {
        log(std::string("LOAD: ")+p); void* h=dlopen(p,RTLD_NOW|RTLD_LOCAL);
        if(!h) { const char* error=dlerror(); throw std::runtime_error(error?error:"dlopen failed"); }
        libraries.push_back(h); return h;
    }
    static Tensor tensor(Tensor* t,uint32_t side,uint32_t channels,uint32_t expectedType) {
        if(!t || t->version!=1) throw std::runtime_error("Expected firmware tensor version 1");
        const auto& v=t->v1;
        // 0x232 FLOAT_32 (HexQuad); 0x416 UFIXED_POINT_16 with one scale/offset (Quad).
        if(v.type!=expectedType || v.format!=0 || (v.dataType!=0x232 && v.dataType!=0x416) || v.rank!=4 || !v.dimensions || !v.name)
            throw std::runtime_error("Unsupported tensor descriptor type="+std::to_string(v.type)+" format="+
                std::to_string(v.format)+" dataType=0x"+[&]{char b[16];std::snprintf(b,sizeof(b),"%x",unsigned(v.dataType));return std::string(b);}()+
                " rank="+std::to_string(v.rank));
        if(v.dimensions[0]!=1 || v.dimensions[1]!=side || v.dimensions[2]!=side || v.dimensions[3]!=channels)
            throw std::runtime_error("Unexpected network dimensions");
        if(v.dataType==0x416 && v.quant.encoding!=0)throw std::runtime_error("Quantized tensor without per-tensor scale/offset");
        log("TENSOR: "+std::string(v.name)+" id="+std::to_string(v.id)+(v.dataType==0x416?" UFIXED16 1x":" FLOAT32 1x")+std::to_string(side)+"x"+std::to_string(side)+"x"+std::to_string(channels));
        // Use the public V1 descriptor for this single, dense, static tensor.
        // No array stride from a newer/larger tensor record is assumed.
        return Tensor{1,v};
    }
public:
    std::vector<float> input,output;
    explicit HexSession(int scale) : HexSession(hexSpec(scale)) {}
    explicit HexSession(const NetSpec& spec) : input(size_t(spec.side)*spec.side*spec.inChannels),
        output(size_t(spec.outSide)*spec.outSide*3), scale_(int(spec.outSide/spec.side)), spec_(spec) {}
    int scale_;
    NetSpec spec_;
    void init(const std::string& directory) {
        model=read(directory+spec_.file);
        if(model.size()!=spec_.bytes) throw std::runtime_error("Unexpected embedded model length");
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
        if(std::strcmp(g->name,spec_.graph))throw std::runtime_error(std::string("Wrong graph name ")+g->name);
        log("GRAPH: "+std::string(g->name));
        in=tensor(g->input,spec_.side,spec_.inChannels,0);out=tensor(g->output,spec_.outSide,3,1);
        quantizer(in,inQ,input.size(),"input");quantizer(out,outQ,output.size(),"output");
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
        qnn_perf::voteHtpPerformance(api,[](const std::string& line){log(line);});
        created=nullptr;check(fn<Error(*)(Handle,Handle,const void**,const void*,uint64_t,Handle*,Handle)>(13)(backend,device,nullptr,model.data(),model.size(),&created,nullptr),"Context create");context=created;
        check(fn<Error(*)(Handle,const char*,Handle*)>(20)(context,g->name,&graph),"Graph retrieve");
        if(!backend||!device||!context||!graph)throw std::runtime_error("QNN returned an empty handle");
        in.v1.memType=0;out.v1.memType=0;bind();
    }
    void execute() {
        // captureHex swaps only COMPLETED input storage, between synchronous
        // calls. Refresh public client descriptors before each graphExecute.
        if(input.size()!=size_t(spec_.side)*spec_.side*spec_.inChannels||output.size()!=size_t(spec_.outSide)*spec_.outSide*3)
            throw std::runtime_error("HexQuad client buffer shape changed");
        bind();
        if(inQ.on){
            const float inv=1.f/inQ.scale;
            for(size_t i=0;i<input.size();++i){
                const float q=std::nearbyint(input[i]*inv)-float(inQ.offset);
                inQ.data[i]=uint16_t(std::max(0.f,std::min(65535.f,q)));
            }
        }
        if(outQ.on)std::fill(outQ.data.begin(),outQ.data.end(),uint16_t(0xffff));
        else poisonOutput(output);
        ++executions;
        if(executions<=8)log("GRAPH EXECUTE #"+std::to_string(executions)+": begin");
        check(fn<Error(*)(Handle,const Tensor*,uint32_t,Tensor*,uint32_t,Handle,Handle)>(21)(graph,&in,1,&out,1,nullptr,nullptr),"Graph execute");
        if(executions<=8)log("GRAPH EXECUTE #"+std::to_string(executions)+": status=0");
        if(outQ.on)for(size_t i=0;i<output.size();++i)output[i]=(float(outQ.data[i])+float(outQ.offset))*outQ.scale;
        for(float value:output)if(!std::isfinite(value))throw OutputError("HexQuad output nonfinite or unwritten");
    }
    ~HexSession() {
        // The process exits after one job; do not dlclose live vendor runtime code.
        if(api){if(context && api->slots[14])reinterpret_cast<Error(*)(Handle,Handle)>(api->slots[14])(context,nullptr);if(device && api->slots[43])reinterpret_cast<Error(*)(Handle)>(api->slots[43])(device);if(backend && api->slots[8])reinterpret_cast<Error(*)(Handle)>(api->slots[8])(backend);}
        if(sys&&metadata)sys->free(metadata);
    }
};

}
