// Host ABI/lifetime test with an explicitly fake QNN backend. NOT inference.
#define NICE_HOST_TEST 1
#define dlopen nice_test_dlopen
#define dlsym nice_test_dlsym
#define dlerror nice_test_dlerror
#include "../app/src/main/cpp/vivo-nice-probe.cpp"
#include "../app/src/main/cpp/vivo-nice-tone-probe.h"
#undef dlopen
#undef dlsym
#undef dlerror
#include <cassert>
#include <filesystem>

using namespace vivo_nice;
namespace {
Provider provider{};SystemProvider systemProvider{};
uint32_t inputShape[]={1,544,544,22},outputShape[]={1,544,544,3};
Tensor inputTensor{},outputTensor{};GraphPrefix graphInfo{};
struct {uint32_t version=3; BinaryV3Prefix binary{};} binaryInfo;
int executions=0,contextsFreed=0,devicesFreed=0,backendsFreed=0,metadataFreed=0;
int failAt=0;const uint8_t* liveModel=nullptr;
bool toneFixture=false;size_t expectedModelBytes=5840224;
Error systemCreate(Handle* h){*h=(void*)1;return 0;}
Error systemInfo(Handle,void* data,uint64_t length,const void** info,uint64_t* size){
    assert(length==expectedModelBytes);liveModel=static_cast<uint8_t*>(data);*info=&binaryInfo;*size=sizeof(binaryInfo);return 0;
}
Error systemFree(Handle){metadataFreed++;return 0;}
Error getSystem(const SystemProvider*** p,uint32_t* n){static const SystemProvider* a[]={&systemProvider};*p=a;*n=1;return 0;}
Error getProviders(const Provider*** p,uint32_t* n){static const Provider* a[]={&provider};*p=a;*n=1;return 0;}
Error build(const char** p){*p="v2.29.8.250123143957_105779";return 0;}
Error backendCreate(Handle,const void**,Handle* h){*h=(void*)2;return 0;}
Error deviceCreate(Handle,const void**,Handle* h){if(failAt==1)return 91;*h=(void*)3;return 0;}
Error contextCreate(Handle,Handle,const void**,const void* model,uint64_t,Handle* h,Handle){
    assert(model==liveModel);if(failAt==2)return 92;*h=(void*)4;return 0;
}
Error graphRetrieve(Handle,const char*,Handle* h){*h=(void*)5;return 0;}
Error execute(Handle,const Tensor* in,uint32_t ni,Tensor* out,uint32_t no,Handle,Handle){
    if(toneFixture){
        assert(ni==graphInfo.inputs && no==1);
        for(uint32_t i=0;i<ni;++i){assert(in[i].v1.client.size==ToneGraph::bytes(in[i]));assert(in[i].v1.client.data);}
        assert(out->v1.client.size==ToneGraph::bytes(*out));executions++;
        if(failAt==3)return 93;
        size_t n=ToneGraph::elements(*out)-(failAt==4?1:0);
        if(out->v1.dataType==0x232){
            float v;std::memcpy(&v,in[0].v1.client.data,4);
            for(size_t i=0;i<n;++i)std::memcpy(static_cast<uint8_t*>(out->v1.client.data)+4*i,&v,4);
        }else{
            assert(ni==2);uint16_t v,m;std::memcpy(&v,in[0].v1.client.data,2);std::memcpy(&m,in[1].v1.client.data,2);
            v=uint16_t(v+m/2);
            for(size_t i=0;i<n;++i)std::memcpy(static_cast<uint8_t*>(out->v1.client.data)+2*i,&v,2);
        }
        return 0;
    }
    assert(ni==1 && no==1 && in->v1.client.size==544*544*22*4 && out->v1.client.size==544*544*3*4);
    auto src=static_cast<float*>(in->v1.client.data),dst=static_cast<float*>(out->v1.client.data);
    assert(src[0]==(executions?0.125f:0.0f));
    assert(std::isnan(dst[0]));executions++;
    if(failAt==3)return 93;
    if(failAt!=4)std::fill(dst,dst+544*544*3,src[0]);return 0;
}
Error contextFree(Handle,Handle){assert(liveModel[0]==0x5a);contextsFreed++;return 0;}
Error deviceFree(Handle){devicesFreed++;return 0;}
Error backendFree(Handle){backendsFreed++;return 0;}
}
extern "C" void* nice_test_dlopen(const char*,int) noexcept{return (void*)9;}
extern "C" char* nice_test_dlerror() noexcept{return nullptr;}
extern "C" void* nice_test_dlsym(void*,const char* n) noexcept {
    return std::strcmp(n,"QnnSystemInterface_getProviders")==0?(void*)getSystem:(void*)getProviders;
}
int main(int argc,char** argv){
    assert(argc==2);std::string dir=argv[1];std::filesystem::create_directories(dir);
    {std::vector<uint8_t> data(5840224,0x5a);std::ofstream f(dir+"/nice-main-forward-v79.bin",std::ios::binary);f.write((char*)data.data(),data.size());}
    systemProvider.version={1,2,0};systemProvider.create=systemCreate;systemProvider.info=systemInfo;systemProvider.free=systemFree;
    provider.id=6;provider.core={2,22,0};
    provider.slots[1]=(Fn)backendCreate;provider.slots[4]=(Fn)build;provider.slots[8]=(Fn)backendFree;
    provider.slots[13]=(Fn)contextCreate;provider.slots[14]=(Fn)contextFree;provider.slots[20]=(Fn)graphRetrieve;
    provider.slots[21]=(Fn)execute;provider.slots[40]=(Fn)deviceCreate;provider.slots[43]=(Fn)deviceFree;
    inputTensor.version=outputTensor.version=1;
    inputTensor.v1.name="inputs_0";inputTensor.v1.rank=4;inputTensor.v1.dimensions=inputShape;inputTensor.v1.dataType=0x232;
    outputTensor.v1.name="tail_conv_1_0";outputTensor.v1.rank=4;outputTensor.v1.dimensions=outputShape;outputTensor.v1.dataType=0x232;outputTensor.v1.type=1;
    graphInfo={3,"nice_hdr_imx06c_general_forward_bayer_x1_quant_8w16a32b",1,&inputTensor,1,&outputTensor};
    binaryInfo.binary.graphs=1;binaryInfo.binary.graph=&graphInfo;
    for(failAt=0;failAt<=5;failAt++){
        executions=contextsFreed=devicesFreed=backendsFreed=metadataFreed=0;
        inputShape[3]=failAt==5?21:22;bool stopped=false;
        try{probe(dir,[](const std::string&){});}catch(const std::runtime_error&){stopped=true;}
        assert(stopped==(failAt!=0));assert(metadataFreed==1);
        assert(backendsFreed==(failAt==5?0:1));
        assert(devicesFreed==((failAt==1||failAt==5)?0:1));
        assert(contextsFreed==((failAt==1||failAt==2||failAt==5)?0:1));
        if(!failAt)assert(executions==2);
    }
    toneFixture=true;
    auto specs=toneModels();
    for(const auto& spec:specs){
        expectedModelBytes=spec.bytes;
        {std::vector<uint8_t> data(spec.bytes,0x5a);std::ofstream f(dir+"/"+spec.file,std::ios::binary);f.write((char*)data.data(),data.size());}
        std::vector<Tensor> ti(spec.inputs.size());std::vector<std::array<uint32_t,4>> shapes;
        for(const auto& t:spec.inputs)shapes.push_back(t.shape);
        auto outputShape=spec.output.shape;
        auto init=[](Tensor& t,const ToneTensorSpec& spec,uint32_t kind,uint32_t* dims){
            t={};t.version=1;t.v1.name=spec.name;t.v1.type=kind;t.v1.rank=4;t.v1.dimensions=dims;t.v1.dataType=spec.dtype;
        };
        for(size_t i=0;i<ti.size();++i)init(ti[i],spec.inputs[i],0,shapes[i].data());
        Tensor to{};init(to,spec.output,1,outputShape.data());
        graphInfo={3,spec.graph,uint32_t(ti.size()),ti.data(),1,&to};
        for(failAt=0;failAt<=5;++failAt){
            executions=contextsFreed=devicesFreed=backendsFreed=metadataFreed=0;
            shapes.back()[3]=spec.inputs.back().shape[3]+(failAt==5?1:0);
            bool stopped=false;try{probeToneModel(dir,spec,[](const std::string&){});}catch(const std::runtime_error&){stopped=true;}
            assert(stopped==(failAt!=0));assert(metadataFreed==1);
            assert(backendsFreed==(failAt==5?0:1));assert(devicesFreed==((failAt==1||failAt==5)?0:1));
            assert(contextsFreed==((failAt==1||failAt==2||failAt==5)?0:1));
            if(!failAt)assert(executions==(spec.output.dtype==0x416?4:2));
        }
        std::filesystem::remove(dir+"/"+spec.file);
    }
    std::cout<<"Tone probe host PASS: five graph contracts, two-input UFIXED16, float poisoning, quantized last-element poison detection, cleanup (mock QNN)\n";
    std::filesystem::remove(dir+"/nice-main-forward-v79.bin");
    std::cout<<"NICE probe host PASS: descriptor validation, two dispatches, failure cleanup, output sentinel and binary lifetime (mock QNN, no device inference)\n";
}
