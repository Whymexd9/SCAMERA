#pragma once
#include "vivo-neural-model.h"
#include <dlfcn.h>
#include <cmath>
#include <array>
#include <algorithm>
#include <fstream>
#include <iostream>
#include <limits>
#include <functional>
#include <sstream>
#include <iomanip>

// Minimal public QNN ABI prefixes, restricted to the hash-checked PD2454
// libraries and Core 2.18.0 / System 1.1.0. Runtime files come from APK assets.
// See docs/vivo-neural-capture.md for source declarations and validation limits.
namespace vivo_nn {
using Error=uint64_t; using Handle=void*; using Fn=void(*)();
struct Version { uint32_t major,minor,patch; };
struct Provider { uint32_t id; const char* name; Version core,backend; Fn slots[44]; };
struct SystemProvider {
    uint32_t id; const char* name; Version version;
    Error(*create)(Handle*); Error(*info)(Handle,void*,uint64_t,const void**,uint64_t*);
    Fn metadata; Error(*free)(Handle);
};
// Largest quantization union in this ABI is BW axis scale/offset (32 bytes).
struct Quant { uint32_t definition,encoding; alignas(8) uint8_t payload[32]; };
struct ClientBuffer { void* data; uint32_t size; };
struct TensorV1 {
    uint32_t id; const char* name; uint32_t type,format,dataType;
    Quant quant; uint32_t rank; uint32_t* dimensions; uint32_t memType;
    ClientBuffer client;
};
// Qnn_Tensor_t in the pinned runtime reserves the larger V2 union even for
// version 1 records. Emulated QnnSystem allocations are 144 bytes per tensor.
struct Tensor { uint32_t version; TensorV1 v1; uint8_t reserved[24]{}; };
struct GraphPrefix { uint32_t version; const char* name; uint32_t inputs; Tensor* input; uint32_t outputs; Tensor* output; };
struct BinaryV1Prefix {
    uint32_t backend; const char* build; Version core,impl; const char* soc;
    Version hw,blob; uint32_t hwSize; void* hwData; uint64_t blobSize;
    uint32_t tensors; void* tensorData; uint32_t graphs; GraphPrefix* graph;
};
struct BinaryV3Prefix {
    uint32_t backend; const char* build; Version core,impl; const char* soc;
    Version blob; uint64_t blobSize; uint32_t tensors; void* tensorData;
    uint32_t graphs; GraphPrefix* graph;
};
static_assert(offsetof(Provider,slots)==40,"QNN provider ABI");
static_assert(offsetof(SystemProvider,create)==32,"QNN system ABI");
static_assert(sizeof(TensorV1)==112 && offsetof(TensorV1,dimensions)==80 && offsetof(Tensor, v1)==8,"QNN tensor ABI");
static_assert(sizeof(Tensor)==144,"QNN tensor union storage");
static_assert(offsetof(BinaryV1Prefix,graph)==120 && offsetof(BinaryV3Prefix,graph)==96,"QNN metadata ABI");
inline void check(Error e,const char* stage) { if(e) throw std::runtime_error(std::string(stage)+" status="+std::to_string(e)); }
inline void log(const std::string& s) { std::cout<<s<<std::endl; }
inline std::vector<uint8_t> read(const std::string& p) {
    std::ifstream f(p,std::ios::binary|std::ios::ate);
    if(!f) throw std::runtime_error("Cannot read "+p);
    auto n=f.tellg(); if(n<=0 || n>128*1024*1024) throw std::runtime_error("File size rejected");
    std::vector<uint8_t> v(static_cast<size_t>(n)); f.seekg(0);
    if(!f.read(reinterpret_cast<char*>(v.data()),n)) throw std::runtime_error("Short input read");
    return v;
}
// A data validation failure may reject a CFA hypothesis. Runtime/driver errors
// must still abort the entire job, never trigger a different layout silently.
struct OutputError : std::runtime_error { using std::runtime_error::runtime_error; };
constexpr uint32_t OUTPUT_SENTINEL = 0x7fc0a55a;
constexpr int INPUT_TILE=576, INPUT_HALO=64, OUTPUT_SCALE=2;
constexpr int OUTPUT_TILE=INPUT_TILE*OUTPUT_SCALE, OUTPUT_GRID=OUTPUT_TILE/8;
constexpr int MAX_OUTPUT_CFA_BLOCK=2, SAMPLE_RADIUS=2*MAX_OUTPUT_CFA_BLOCK;
// Include every neighbour visited by sample(), for both input scale factors.
// The half-pixel reprojection can floor one pixel before the retained core.
constexpr int USED_BEGIN=INPUT_HALO*OUTPUT_SCALE-SAMPLE_RADIUS-1;
constexpr int USED_END=OUTPUT_TILE-INPUT_HALO*OUTPUT_SCALE+SAMPLE_RADIUS;
inline bool usedOutputPixel(int x,int y) {
    return x>=USED_BEGIN&&y>=USED_BEGIN&&x<USED_END&&y<USED_END;
}
inline std::array<int,2> outputPixel(size_t index) {
    size_t cell=index/64; int channel=index%64,dx=0,dy=0;
    for(int bit=0;bit<3;++bit) {
        dx|=((channel>>(2*bit))&1)<<bit;
        dy|=((channel>>(2*bit+1))&1)<<bit;
    }
    return {{int(cell%OUTPUT_GRID)*8+dx,int(cell/OUTPUT_GRID)*8+dy}};
}
inline void poisonOutput(std::vector<float>& output) {
    float sentinel; std::memcpy(&sentinel,&OUTPUT_SENTINEL,sizeof(sentinel));
    std::fill(output.begin(),output.end(),sentinel);
}
inline void validateOutput(const std::vector<float>& output, unsigned execution) {
    size_t nan=0,inf=0,untouched=0,outside=0,outsideUsed=0,finite=0,first=output.size(),firstUsed=output.size();
    const bool tiled=output.size()==size_t(OUTPUT_TILE)*OUTPUT_TILE;
    double sum=0; float lo=std::numeric_limits<float>::infinity(),hi=-lo;
    for(size_t i=0;i<output.size();++i) {
        float v=output[i]; uint32_t bits; std::memcpy(&bits,&v,4);
        if(bits==OUTPUT_SENTINEL)++untouched;
        if(std::isnan(v))++nan;
        else if(!std::isfinite(v))++inf;
        else {
            ++finite;sum+=v;lo=std::min(lo,v);hi=std::max(hi,v);
            if(v<-.25f||v>2.f) {
                ++outside;
                auto pixel=outputPixel(i);
                if(!tiled||usedOutputPixel(pixel[0],pixel[1])) {
                    ++outsideUsed;if(firstUsed==output.size())firstUsed=i;
                }
            }
        }
        if(first==output.size()&&(!std::isfinite(v)||v<-.25f||v>2.f))first=i;
    }
    // Non-finite/unwritten values remain fatal anywhere in the output. Only
    // finite overshoot in the discarded convolution halo is ignored.
    const bool bad=nan||inf||outsideUsed||output.empty();
    if(execution<=8||bad) {
        std::ostringstream line;line<<std::setprecision(9)<<"OUTPUT #"<<execution
            <<": count="<<output.size()<<" finite="<<finite<<" nan="<<nan
            <<" inf="<<inf<<" sentinel="<<untouched<<" outside="<<outside
            <<" outside_used="<<outsideUsed<<" outside_halo="<<outside-outsideUsed;
        if(finite)line<<" min="<<lo<<" max="<<hi<<" mean="<<sum/finite;
        log(line.str());
        if(first<output.size()) {
            uint32_t bits;std::memcpy(&bits,&output[first],4);
            std::ostringstream detail;detail<<"FIRST INVALID: index="<<first<<" value="<<output[first]
                <<" bits=0x"<<std::hex<<bits;
            if(tiled){auto pixel=outputPixel(first);detail<<std::dec<<" xy="<<pixel[0]<<','<<pixel[1]
                <<" region="<<(usedOutputPixel(pixel[0],pixel[1])?"used":"discarded_halo");}
            log(detail.str());
        }
        if(firstUsed<output.size()) {
            auto pixel=outputPixel(firstUsed);
            log("FIRST INVALID USED: index="+std::to_string(firstUsed)+" xy="+
                std::to_string(pixel[0])+","+std::to_string(pixel[1])+" value="+std::to_string(output[firstUsed]));
        }
        if(tiled) {
            std::ostringstream center;center<<std::setprecision(6)<<"CENTER: ";
            for(int c=0;c<16;c++)center<<output[(72*144+72)*64+c]<<' ';
            log(center.str());
        }
    }
    if(bad)throw OutputError("Invalid neural output; frame rejected (see OUTPUT statistics)");
}
class Session {
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
    static Tensor tensor(Tensor* t,uint32_t channels,uint32_t expectedType) {
        if(!t || t->version!=1) throw std::runtime_error("Expected firmware tensor version 1");
        const auto& v=t->v1;
        if(v.type!=expectedType || v.format!=0 || v.dataType!=0x232 || v.rank!=4 || !v.dimensions || !v.name)
            throw std::runtime_error("Unsupported tensor descriptor");
        if(v.dimensions[0]!=1 || v.dimensions[1]!=144 || v.dimensions[2]!=144 || v.dimensions[3]!=channels)
            throw std::runtime_error("Unexpected network dimensions");
        log("TENSOR: "+std::string(v.name)+" id="+std::to_string(v.id)+" FLOAT32 1x144x144x"+std::to_string(channels));
        // Use the public V1 descriptor for this single, dense, static tensor.
        // No array stride from a newer/larger tensor record is assumed.
        return Tensor{1,v};
    }
public:
    std::vector<float> input,output;
    Session() : input(144*144*16),output(144*144*64) {}
    void init(const std::string& directory) {
        model=read(directory+"/tele576-v79.bin");
        if(model.size()!=5720680) throw std::runtime_error("Unexpected embedded model length");
        auto system=load((directory+"/libQnnSystem.so").c_str());
        auto getSystem=reinterpret_cast<Error(*)(const SystemProvider***,uint32_t*)>(dlsym(system,"QnnSystemInterface_getProviders"));
        if(!getSystem) throw std::runtime_error("No System provider entry");
        const SystemProvider** systems=nullptr;uint32_t n=0;check(getSystem(&systems,&n),"System providers");
        if(!systems||!n||n>16) throw std::runtime_error("System provider count");
        for(uint32_t i=0;i<n;i++) if(systems[i]&&systems[i]->version.major==1&&systems[i]->version.minor==1)sys=systems[i];
        if(!sys||!sys->create||!sys->info||!sys->free)throw std::runtime_error("System 1.1 required");
        Handle created=nullptr;check(sys->create(&created),"System create");metadata=created;
        const void* info=nullptr;uint64_t bytes=0;check(sys->info(metadata,model.data(),model.size(),&info,&bytes),"System metadata");
        // The supplied System 1.1 returns a valid owned metadata pointer but
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
        if(std::strcmp(g->name,"T2Q_TELE_3x_v1p9_frozen"))throw std::runtime_error("Wrong graph name");
        in=tensor(g->input,16,0);out=tensor(g->output,64,1);
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
        for(uint32_t i=0;i<n;i++)if(providers[i]&&providers[i]->core.major==2&&providers[i]->core.minor==18&&providers[i]->core.patch==0)api=providers[i];
        if(!api||api->id!=6)throw std::runtime_error("Expected HTP Core API 2.18.0");
        for(int slot:{1,4,8,13,14,20,21,40,43})if(!api->slots[slot])throw std::runtime_error("Missing required QNN function");
        const char* build=nullptr;check(fn<Error(*)(const char**)>(4)(&build),"Build ID");log(std::string("SDK: ")+(build?build:"unknown"));
        created=nullptr;check(fn<Error(*)(Handle,const void**,Handle*)>(1)(nullptr,nullptr,&created),"Backend create");backend=created;
        created=nullptr;check(fn<Error(*)(Handle,const void**,Handle*)>(40)(nullptr,nullptr,&created),"Device create");device=created;
        created=nullptr;check(fn<Error(*)(Handle,Handle,const void**,const void*,uint64_t,Handle*,Handle)>(13)(backend,device,nullptr,model.data(),model.size(),&created,nullptr),"Context create");context=created;
        check(fn<Error(*)(Handle,const char*,Handle*)>(20)(context,g->name,&graph),"Graph retrieve");
        if(!backend||!device||!context||!graph)throw std::runtime_error("QNN returned an empty handle");
        in.v1.memType=0;in.v1.client={input.data(),static_cast<uint32_t>(input.size()*4)};
        out.v1.memType=0;out.v1.client={output.data(),static_cast<uint32_t>(output.size()*4)};
    }
    void execute() {
        poisonOutput(output);
        ++executions;
        if(executions<=8)log("GRAPH EXECUTE #"+std::to_string(executions)+": begin");
        check(fn<Error(*)(Handle,const Tensor*,uint32_t,Tensor*,uint32_t,Handle,Handle)>(21)(graph,&in,1,&out,1,nullptr,nullptr),"Graph execute");
        if(executions<=8)log("GRAPH EXECUTE #"+std::to_string(executions)+": status=0");
        validateOutput(output,executions);
    }
    ~Session() {
        // The process exits after one job; do not dlclose live vendor runtime code.
        if(api){if(context && api->slots[14])reinterpret_cast<Error(*)(Handle,Handle)>(api->slots[14])(context,nullptr);if(device && api->slots[43])reinterpret_cast<Error(*)(Handle)>(api->slots[43])(device);if(backend && api->slots[8])reinterpret_cast<Error(*)(Handle)>(api->slots[8])(backend);}
        if(sys&&metadata)sys->free(metadata);
    }
};

// Exact legacy HC packing and s2dout_8 inverse packing verified by executing
// stock ARM64 Halide kernels on host with Unicorn. This does NOT by itself
// identify the active Vivo sensor mode; calibration below is mandatory.
inline int morton(int x,int y,int bits){int k=0;for(int b=0;b<bits;b++)k|=((x>>b)&1)<<(2*b)|((y>>b)&1)<<(2*b+1);return k;}
inline int color(int x,int y,int block){int q=((y/block)&1)*2+((x/block)&1);return q==0?0:q==3?2:1;}
inline float unpack(const std::vector<float>& out,int x,int y){float v=out[((y/8)*144+x/8)*64+morton(x%8,y%8,3)];return std::max(0.0f,std::min(1.0f,v*v));}
struct Mapping {int inputBlock=0,outputBlock=0;double error=1e9;};
template<class Network> Mapping calibrate(Network& s) {
    Mapping best;
    const float tests[4][3]={{.2f,.2f,.2f},{.55f,.55f,.55f},{.12f,.35f,.65f},{.65f,.28f,.10f}};
    for(int block:{4,2}) {
        double errors[2]={0,0};
        bool valid=true; int chart=0;
        for(const auto& rgb:tests){
            log("CFA INPUT: block="+std::to_string(block)+" chart="+std::to_string(++chart));
            for(int y=0;y<576;y++)for(int x=0;x<576;x++)s.input[((y/4)*144+x/4)*16+morton(x%4,y%4,2)]=std::sqrt(rgb[color(x,y,block)]);
            try { s.execute(); }
            catch(const OutputError& e) {
                log("CFA REJECTED: input="+std::to_string(block)+" "+e.what());
                valid=false;break;
            }
            for(int ob=1;ob<=2;ob++){
                double err=0;int count=0;
                for(int y=256;y<896;y+=1)for(int x=256;x<896;x+=1){double d=unpack(s.output,x,y)-rgb[color(x,y,ob)];err+=d*d;count++;}
                errors[ob-1]=std::max(errors[ob-1],std::sqrt(err/count));
            }
        }
        if(!valid)continue;
        for(int ob=1;ob<=2;ob++){
            log("CFA TEST: input="+std::to_string(block)+" output="+std::to_string(ob)+" worst RMSE="+std::to_string(errors[ob-1]));
            if(errors[ob-1]<best.error)best={block,ob,errors[ob-1]};
        }
    }
    // A synthetic colour gate rejects wrong CFA mappings. It cannot prove
    // real-scene detail quality or that this is Vivo's active 4x model.
    if(best.error>.045)throw std::runtime_error("No reliable Tetra/Bayer mapping: neural capture not enabled");
    log("CFA TEST PASSED: input="+std::to_string(best.inputBlock)+" output="+std::to_string(best.outputBlock)+" RMSE="+std::to_string(best.error));
    return best;
}
inline int phaseClamp(int x,int n){int p=((x%8)+8)%8;return std::max(p,std::min(x,n-8+p));}
// Select the closest output sample of the requested colour. No cross-colour
// averaging and no synthetic sharpening; equal-distance candidates are averaged.
inline float sample(const std::vector<float>& v,float fx,float fy,int c,int block){
    if(block<1||block>MAX_OUTPUT_CFA_BLOCK)throw std::runtime_error("Unsupported output CFA block");
    int cx=static_cast<int>(std::floor(fx)),cy=static_cast<int>(std::floor(fy));float best=1e9f,total=0;int count=0;
    for(int y=cy-2*block;y<=cy+2*block;y++)for(int x=cx-2*block;x<=cx+2*block;x++){
        if(x<0||y<0||x>=OUTPUT_TILE||y>=OUTPUT_TILE||color(x,y,block)!=c)continue;
        if(!usedOutputPixel(x,y))throw std::runtime_error("Sampler reached unvalidated output halo");
        float d=(x-fx)*(x-fx)+(y-fy)*(y-fy);
        if(d<best-1e-4f){best=d;total=unpack(v,x,y);count=1;}
        else if(std::abs(d-best)<1e-4f){total+=unpack(v,x,y);count++;}
    }
    if(!count)throw std::runtime_error("No matching output CFA sample");
    return total/count;
}
template<class Network> std::vector<float> reconstruct(Network& s,const Mapping& mapping,const float* raw,int w,int h,int redQuad){
    if(!raw||w<8||h<8||w%8||h%8||static_cast<int64_t>(w)*h>16000000||redQuad<0||redQuad>3)throw std::runtime_error("Unsupported Tetra frame");
    const int scale=4/mapping.inputBlock,margin=INPUT_HALO,step=INPUT_TILE-2*margin,span=step*scale;
    const bool flipX=redQuad%2,flipY=redQuad/2;
    std::vector<float> result(static_cast<size_t>(w)*h);
    auto at=[&](int x,int y){x=phaseClamp(x,w);y=phaseClamp(y,h);if(flipX)x=w-1-x;if(flipY)y=h-1-y;return std::max(0.f,std::min(1.f,raw[y*w+x]));};
    int tile=0,total=((w+span-1)/span)*((h+span-1)/span);
    for(int oy=0;oy<h;oy+=span)for(int ox=0;ox<w;ox+=span){
        for(int y=0;y<576;y++)for(int x=0;x<576;x++){
            float v=0;for(int dy=0;dy<scale;dy++)for(int dx=0;dx<scale;dx++)v+=at(ox+(x-margin)*scale+dx,oy+(y-margin)*scale+dy);
            s.input[((y/4)*144+x/4)*16+morton(x%4,y%4,2)]=std::sqrt(v/(scale*scale));
        }
        s.execute();
        for(int y=oy;y<std::min(oy+span,h);y++)for(int x=ox;x<std::min(ox+span,w);x++){
            float nx=((x-ox+.5f)/scale+margin)*2-.5f,ny=((y-oy+.5f)/scale+margin)*2-.5f;
            float v=sample(s.output,nx,ny,color(x,y,1),mapping.outputBlock);
            int tx=flipX?w-1-x:x,ty=flipY?h-1-y:y;result[ty*w+tx]=v;
        }
        log("TILE "+std::to_string(++tile)+"/"+std::to_string(total));
    }
    return result;
}
}

