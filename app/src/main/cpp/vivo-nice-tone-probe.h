#pragma once
// Include after vivo-nice-probe.cpp: shares the pinned Session ABI only.
// Synthetic compatibility checks; never used as photographic tone processing.
namespace vivo_nice {
struct ToneTensorSpec { const char* name; std::array<uint32_t,4> shape; uint32_t dtype; };
struct ToneModelSpec {
    const char* file; size_t bytes; const char* graph;
    std::vector<ToneTensorSpec> inputs; ToneTensorSpec output;
};
inline std::vector<ToneModelSpec> toneModels() { return {
    {"nice-tone-fasttm-v79.bin",991248,"fasttmv7_arch295k_520input_DX4_1_fp16",{{"_1_0",{1,520,520,3},0x232}},{"_376_0",{1,520,520,3},0x232}},
    {"nice-tone-adams-v79.bin",10158400,"nicetone_adams_x200u_quant_8w16a32b",{{"x_0",{1,1024,1024,3},0x416},{"mask_0",{1,512,512,1},0x416}},{"d2s_0",{1,1024,1024,3},0x416}},
    {"nice-tone-adams-landscape-v79.bin",10162552,"nicetce_noportrait_v11_quant_8w16a32b",{{"x_0",{1,1024,1024,3},0x416},{"mask_0",{1,512,512,1},0x416}},{"d2s_vnnop_0",{1,1024,1024,3},0x416}},
    {"nice-tone-hdrnet-coeff-v79.bin",9521192,"coeff_1_fp16",{{"_1_0",{1,512,512,8},0x232}},{"_116_0",{1,32,32,384},0x232}},
    {"nice-tone-hdrnet-weight-v79.bin",2627792,"weight_1_fp16",{{"_1_0",{1,512,512,8},0x232}},{"_134_0",{1,512,512,2},0x232}},
}; }
struct ToneGraph {
    std::vector<uint8_t> model;
    // 32-bit words provide alignment for both FLOAT32 and UFIXED16 clients.
    std::vector<std::vector<uint32_t>> inputStorage;
    std::vector<uint32_t> outputStorage;
    Session s;
    std::vector<Tensor> inputs; Tensor output{};
    static Tensor validate(const Tensor* t,const ToneTensorSpec& expected,uint32_t kind) {
        if(!t || t->version!=1 || !t->v1.name || std::strcmp(t->v1.name,expected.name)
           || t->v1.type!=kind || t->v1.format!=0 || t->v1.dataType!=expected.dtype
           || t->v1.rank!=4 || !t->v1.dimensions)throw std::runtime_error("Tone tensor contract mismatch");
        for(int i=0;i<4;++i)if(t->v1.dimensions[i]!=expected.shape[i])throw std::runtime_error("Tone tensor shape mismatch");
        return *t;
    }
    static size_t elements(const Tensor& t) {
        size_t n=1;for(int i=0;i<4;++i)n*=t.v1.dimensions[i];return n;
    }
    static size_t bytes(const Tensor& t) {return elements(t)*(t.v1.dataType==0x232?4:2);}
    ToneGraph(const std::string& directory,const ToneModelSpec& spec,const Reporter& report)
        :model(read(directory+"/"+spec.file)),s(report) {
        if(model.size()!=spec.bytes)throw std::runtime_error("Tone model size mismatch");
    auto system=s.load(directory+"/libQnnSystem.so");
    auto getSystem=reinterpret_cast<Error(*)(const SystemProvider***,uint32_t*)>(dlsym(system,"QnnSystemInterface_getProviders"));
    if(!getSystem)throw std::runtime_error("Missing QNN System provider");
    const SystemProvider** systems=nullptr;uint32_t count=0;
    check(getSystem(&systems,&count),"System providers");
    if(!systems || !count || count>16)throw std::runtime_error("System provider count");
    for(uint32_t i=0;i<count;i++)if(systems[i] && systems[i]->version.major==1 && systems[i]->version.minor==2)s.sys=systems[i];
    if(!s.sys || !s.sys->create || !s.sys->info || !s.sys->free)throw std::runtime_error("System 1.2 required");
    check(s.sys->create(&s.metadata),"System create");
    const void* info=nullptr;uint64_t metadataBytes=0;
    check(s.sys->info(s.metadata,model.data(),model.size(),&info,&metadataBytes),"NICE metadata");
    if(!info || (metadataBytes && metadataBytes<8+sizeof(BinaryV3Prefix)))throw std::runtime_error("Incomplete NICE metadata");
    uint32_t version;std::memcpy(&version,info,4);
    if(version!=3)throw std::runtime_error("Expected NICE binary metadata V3");
    auto b=reinterpret_cast<const BinaryV3Prefix*>(static_cast<const uint8_t*>(info)+8);
    auto g=b->graph;
    if(b->graphs!=1 || !g || g->version<1 || g->version>3 || !g->name
       || std::strcmp(g->name,spec.graph) || g->inputs!=spec.inputs.size() || g->outputs!=1
       || !g->input || !g->output)throw std::runtime_error("Unexpected tone graph");
    for(size_t i=0;i<spec.inputs.size();++i)inputs.push_back(validate(g->input+i,spec.inputs[i],0));
    output=validate(g->output,spec.output,1);
    report(std::string("TONE METADATA PASS: ")+spec.graph);
    report("DRIVER: public platform FastRPC; algorithms/model/runtime are bundled");
    s.load("libcdsprpc.so");
    // Only this app-private process is affected; never modify system properties
    // or linker/SELinux policies to make a failed load succeed.
    if(setenv("ADSP_LIBRARY_PATH",directory.c_str(),1))throw std::runtime_error("DSP search path failed");
    s.load(directory+"/libQnnHtpV79Stub.so");
    auto htp=s.load(directory+"/libQnnHtp.so");
    auto get=reinterpret_cast<Error(*)(const Provider***,uint32_t*)>(dlsym(htp,"QnnInterface_getProviders"));
    if(!get)throw std::runtime_error("Missing HTP provider");
    const Provider** providers=nullptr;count=0;
    check(get(&providers,&count),"HTP providers");
    if(!providers || !count || count>16)throw std::runtime_error("HTP provider count");
    for(uint32_t i=0;i<count;i++)if(providers[i] && providers[i]->id==6
            && providers[i]->core.major==2 && providers[i]->core.minor==22
            && providers[i]->core.patch==0)s.api=providers[i];
    if(!s.api)throw std::runtime_error("HTP Core 2.22.0 required");
    for(int slot:{1,4,8,13,14,20,21,40,43})if(!s.api->slots[slot])throw std::runtime_error("Missing HTP operation");
    const char* build=nullptr;check(s.fn<Error(*)(const char**)>(4)(&build),"Build ID");
    report(std::string("SDK: ")+(build?build:"unknown"));
    if(!build || std::strcmp(build,"v2.29.8.250123143957_105779"))throw std::runtime_error("Unverified runtime build");
    report("BACKEND CREATE");
    check(s.fn<Error(*)(Handle,const void**,Handle*)>(1)(nullptr,nullptr,&s.backend),"Backend create");
    if(!s.backend)throw std::runtime_error("Empty backend");
    report("DEVICE CREATE");
    check(s.fn<Error(*)(Handle,const void**,Handle*)>(40)(nullptr,nullptr,&s.device),"Device create");
    if(!s.device)throw std::runtime_error("Empty device");
    report("CONTEXT CREATE: original NICE weights");
    check(s.fn<Error(*)(Handle,Handle,const void**,const void*,uint64_t,Handle*,Handle)>(13)
            (s.backend,s.device,nullptr,model.data(),model.size(),&s.context,nullptr),"Context create");
    if(!s.context)throw std::runtime_error("Empty context");
    check(s.fn<Error(*)(Handle,const char*,Handle*)>(20)(s.context,g->name,&s.graph),"Graph retrieve");
    if(!s.graph)throw std::runtime_error("Empty graph");

        inputStorage.resize(inputs.size());
        for(size_t i=0;i<inputs.size();++i){
            inputStorage[i].resize((bytes(inputs[i])+3)/4);
            inputs[i].v1.memType=0;inputs[i].v1.client={inputStorage[i].data(),uint32_t(bytes(inputs[i]))};
        }
        outputStorage.resize((bytes(output)+3)/4);
        output.v1.memType=0;output.v1.client={outputStorage.data(),uint32_t(bytes(output))};
        auto describe=[&](const Tensor& t){
            std::ostringstream v;v<<"TONE TENSOR "<<t.v1.name<<" dtype=0x"<<std::hex<<t.v1.dataType
                <<" quant="<<t.v1.quant.definition<<":"<<t.v1.quant.encoding<<" payload=";
            for(uint8_t b:t.v1.quant.payload)v<<std::setw(2)<<std::setfill('0')<<unsigned(b);
            report(v.str());
        };
        for(const auto& t:inputs)describe(t);
        describe(output);
    }
    void fillInputs(bool second) {
        for(size_t k=0;k<inputs.size();++k){
            const size_t n=elements(inputs[k]);
            // Raw quantized codes, not asserted to represent physical scene values.
            if(inputs[k].v1.dataType==0x232){
                float v=second?.125f:0.f;uint32_t bits;std::memcpy(&bits,&v,4);
                std::fill(inputStorage[k].begin(),inputStorage[k].end(),bits);
            }else{
                uint16_t v=second?(k?32768:8192):0;
                for(size_t i=0;i<n;++i)std::memcpy(reinterpret_cast<uint8_t*>(inputStorage[k].data())+i*2,&v,2);
            }
        }
    }
    void execute(uint8_t sentinel) {
        std::memset(outputStorage.data(),sentinel,bytes(output));
        if(output.v1.dataType==0x232){
            const uint32_t nan=0x7fc0a55a;
            std::fill(outputStorage.begin(),outputStorage.end(),nan);
        }
        check(s.fn<Error(*)(Handle,const Tensor*,uint32_t,Tensor*,uint32_t,Handle,Handle)>(21)
              (s.graph,inputs.data(),uint32_t(inputs.size()),&output,1,nullptr,nullptr),"Tone execute");
        if(output.v1.dataType==0x232)for(uint32_t bits:outputStorage){
            float f;std::memcpy(&f,&bits,4);if(!std::isfinite(f))throw std::runtime_error("Tone output nonfinite or unwritten");
        }
    }
    void summary(const Reporter& report) {
        double sum=0;float lo=INFINITY,hi=-INFINITY;size_t n=elements(output);
        for(size_t i=0;i<n;++i){float v;
            if(output.v1.dataType==0x232)std::memcpy(&v,outputStorage.data()+i,4);
            else {uint16_t code;std::memcpy(&code,reinterpret_cast<const uint8_t*>(outputStorage.data())+i*2,2);v=code;}
            lo=std::min(lo,v);hi=std::max(hi,v);sum+=v;
        }
        report("TONE OUTPUT count="+std::to_string(n)+" min="+std::to_string(lo)+" max="+std::to_string(hi)+" mean="+std::to_string(sum/n));
    }
};
inline void probeToneModel(const std::string& directory,const ToneModelSpec& spec,const Reporter& report) {
    report(std::string("TONE MODEL START: ")+spec.file);
    ToneGraph g(directory,spec,report);
    for(int fixture=0;fixture<2;++fixture){
        g.fillInputs(fixture!=0);g.execute(0xa5);g.summary(report);
        if(g.output.v1.dataType==0x416){
            auto previous=g.outputStorage;g.execute(0x5a);
            // Same input must overwrite both distinct poisons identically. A single
            // sentinel cannot distinguish a valid uint16 code from an unwritten slot.
            if(previous!=g.outputStorage)throw std::runtime_error("Tone quantized output is unwritten or nondeterministic");
        }
    }
    report(std::string("TONE MODEL PASS: ")+spec.file);
}
inline void probeTone(const std::string& directory,const Reporter& report) {
    int failed=0;
    for(const auto& spec:toneModels())try{probeToneModel(directory,spec,report);}
        catch(const std::exception& e){++failed;report(std::string("TONE MODEL FAIL: ")+spec.file+" "+e.what());}
    if(failed)throw std::runtime_error("Tone runtime failures: "+std::to_string(failed));
    report("NICE TONE RUNTIME CHECK COMPLETE: synthetic inputs only; photo preprocessing, masks and quality remain unverified");
}
}
