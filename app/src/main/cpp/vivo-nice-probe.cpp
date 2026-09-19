#include "vivo-neural-runtime.h"
#ifndef NICE_HOST_TEST
#include <jni.h>
#endif
#include <cstdlib>

// Execution prerequisite only. The 22 input channels are NOT interpreted as
// camera pixels until the original NICE preprocessing contract is recovered.
namespace vivo_nice {
using namespace vivo_nn;
using Reporter=std::function<void(const std::string&)>;
struct Session {
    const Provider* api=nullptr;
    const SystemProvider* sys=nullptr;
    Handle metadata=nullptr,backend=nullptr,device=nullptr,context=nullptr,graph=nullptr;
    std::vector<void*> libraries;
    Reporter report;
    explicit Session(Reporter r):report(std::move(r)) {}
    template<class T>T fn(int slot) {
        if(!api || !api->slots[slot])throw std::runtime_error("Missing QNN API slot");
        return reinterpret_cast<T>(api->slots[slot]);
    }
    void* load(const std::string& path) {
        report("LOAD: "+path);
        void* h=dlopen(path.c_str(),RTLD_NOW|RTLD_LOCAL);
        if(!h)throw std::runtime_error(dlerror());
        libraries.push_back(h);return h;
    }
    ~Session() {
        // Keep loaded code resident until this isolated process exits.
        if(api) {
            if(context)fn<Error(*)(Handle,Handle)>(14)(context,nullptr);
            if(device)fn<Error(*)(Handle)>(43)(device);
            if(backend)fn<Error(*)(Handle)>(8)(backend);
        }
        if(sys && metadata)sys->free(metadata);
    }
};

Tensor requireTensor(const Tensor* t,uint32_t channels,uint32_t kind,const char* name) {
    if(!t || t->version!=1)throw std::runtime_error("NICE tensor version mismatch");
    const auto& v=t->v1;
    if(v.type!=kind || v.format!=0 || v.dataType!=0x232 || v.rank!=4
            || !v.dimensions || !v.name || std::strcmp(v.name,name)
            || v.dimensions[0]!=1 || v.dimensions[1]!=544
            || v.dimensions[2]!=544 || v.dimensions[3]!=channels)
        throw std::runtime_error("NICE tensor contract mismatch");
    return Tensor{1,v};
}

void probe(const std::string& directory,const Reporter& report) {
    report("NICE RUNTIME CHECK: IMX06C forward HDR; synthetic tensor inputs only");
    auto model=read(directory+"/nice-main-forward-v79.bin");
    if(model.size()!=5840224)throw std::runtime_error("NICE model size mismatch");
    // Context may refer to its binary and client storage until destruction.
    std::vector<float> input(size_t(544)*544*22),output(size_t(544)*544*3);
    Session s(report);
    auto system=s.load(directory+"/libQnnSystem.so");
    auto getSystem=reinterpret_cast<Error(*)(const SystemProvider***,uint32_t*)>(dlsym(system,"QnnSystemInterface_getProviders"));
    if(!getSystem)throw std::runtime_error("Missing QNN System provider");
    const SystemProvider** systems=nullptr;uint32_t count=0;
    check(getSystem(&systems,&count),"System providers");
    if(!systems || !count || count>16)throw std::runtime_error("System provider count");
    for(uint32_t i=0;i<count;i++)if(systems[i] && systems[i]->version.major==1 && systems[i]->version.minor==2)s.sys=systems[i];
    if(!s.sys || !s.sys->create || !s.sys->info || !s.sys->free)throw std::runtime_error("System 1.2 required");
    check(s.sys->create(&s.metadata),"System create");
    const void* info=nullptr;uint64_t bytes=0;
    check(s.sys->info(s.metadata,model.data(),model.size(),&info,&bytes),"NICE metadata");
    if(!info || (bytes && bytes<8+sizeof(BinaryV3Prefix)))throw std::runtime_error("Incomplete NICE metadata");
    uint32_t version;std::memcpy(&version,info,4);
    if(version!=3)throw std::runtime_error("Expected NICE binary metadata V3");
    auto b=reinterpret_cast<const BinaryV3Prefix*>(static_cast<const uint8_t*>(info)+8);
    auto g=b->graph;
    if(b->graphs!=1 || !g || g->version<1 || g->version>3 || g->inputs!=1 || g->outputs!=1
            || !g->name || std::strcmp(g->name,"nice_hdr_imx06c_general_forward_bayer_x1_quant_8w16a32b"))
        throw std::runtime_error("Unexpected NICE graph");
    Tensor in=requireTensor(g->input,22,0,"inputs_0");
    Tensor out=requireTensor(g->output,3,1,"tail_conv_1_0");
    report("METADATA PASS: FLOAT32 input 1x544x544x22; output 1x544x544x3");
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
    in.v1.memType=0;in.v1.client={input.data(),static_cast<uint32_t>(input.size()*4)};
    out.v1.memType=0;out.v1.client={output.data(),static_cast<uint32_t>(output.size()*4)};
    for(int fixture=0;fixture<2;fixture++) {
        std::fill(input.begin(),input.end(),fixture==0?0.0f:0.125f);
        poisonOutput(output);
        report("GRAPH EXECUTE "+std::to_string(fixture+1)+": synthetic uniform tensor");
        check(s.fn<Error(*)(Handle,const Tensor*,uint32_t,Tensor*,uint32_t,Handle,Handle)>(21)
                (s.graph,&in,1,&out,1,nullptr,nullptr),"NICE execute");
        size_t invalid=0;double sum=0;float lo=INFINITY,hi=-INFINITY;
        for(float v:output) {
            if(!std::isfinite(v)){invalid++;continue;}
            sum+=v;lo=std::min(lo,v);hi=std::max(hi,v);
        }
        report("OUTPUT: samples="+std::to_string(output.size())+" nonfinite_or_unwritten="+std::to_string(invalid)
                +" min="+std::to_string(lo)+" max="+std::to_string(hi)+" mean="+std::to_string(sum/output.size()));
        if(invalid)throw std::runtime_error("NICE output invalid or unwritten");
    }
    report("RUNTIME PASS: original NICE graph executed twice. Camera preprocessing, VST/IVST, frame routing and photo quality remain UNVERIFIED.");
}
}

#ifndef NICE_HOST_TEST
extern "C" JNIEXPORT void JNICALL
Java_com_particlesdevs_photoncamera_ui_settings_VivoNiceActivity_nativeProbe(JNIEnv* env,jobject activity,jstring path) {
    jclass type=env->GetObjectClass(activity);
    jmethodID progress=env->GetMethodID(type,"onNativeProgress","(Ljava/lang/String;)V");
    if(!progress)return;
    auto report=[&](const std::string& line) {
        jstring text=env->NewStringUTF(line.c_str());
        if(!text)throw std::runtime_error("JNI allocation failed");
        env->CallVoidMethod(activity,progress,text);env->DeleteLocalRef(text);
        if(env->ExceptionCheck())throw std::runtime_error("Report callback failed");
    };
    const char* chars=env->GetStringUTFChars(path,nullptr);if(!chars)return;
    std::string directory(chars);env->ReleaseStringUTFChars(path,chars);
    try{vivo_nice::probe(directory,report);}
    catch(const std::exception& e){if(!env->ExceptionCheck())report(std::string("STOP: ")+e.what());}
}
#endif
