#pragma once
// Vivo VSR still super-resolution (/vendor/camera3rd/nti/VSR, vsr_config_ve.json):
// sr1x_m4 (enhance), sr2x_m24, sr4x_m24 QNN contexts, 8-bit quantized I/O.
// Contract recovered from the OpenCL kernels embedded in libvivo_vsr.so:
//  pre:  8-bit RGB of the source; 2x packs each 2x2 cell as R4 G4 B4 (row-major
//        inside the cell), 4x feeds RGB per pixel, 1x packs 4x4 cells (R16 G16 B16);
//  post: 48 channels per model pixel = R16 G16 B16, each a 4x4 output block
//        (row-major); value = (q - zeroPoint) * scale + nearest source pixel.
// Tiles overlap by a margin that is cropped, so every output pixel comes from
// the interior of one tile.
#include "vivo-hexquad-runtime.h"
#include <cstdio>

namespace vivo_vsr {
using namespace vivo_nn;

struct Spec { const char* file; size_t bytes; const char* graph; int gridW, gridH, inC, cell, scale; };
// cell: source pixels per model pixel side; scale: output/source ratio.
inline Spec spec(int scale) {
    if(scale==4)return {"/vsr4x-v79.bin",756696u,"sr4x_m24_0220_v0p26_quant_8w8a32b",640,360,3,1,4};
    if(scale==2)return {"/vsr2x-v79.bin",752600u,"sr2x_m24_0220_v0p26_quant_8w8a32b",640,360,12,2,2};
    if(scale==1)return {"/vsr1x-v79.bin",755888u,"sr1x_m4_quant_8w8a32b",480,270,48,4,1};
    throw std::runtime_error("Unsupported VSR scale");
}

class VsrSession {
    std::vector<void*> libraries;
    const Provider* api=nullptr; const SystemProvider* sys=nullptr;
    Handle backend=nullptr,device=nullptr,context=nullptr,metadata=nullptr,graph=nullptr;
    std::vector<uint8_t> model;
    Tensor in{},out{};
    template<class T> T fn(int i){if(!api->slots[i])throw std::runtime_error("Missing QNN function");return reinterpret_cast<T>(api->slots[i]);}
    void* load(const char* p){log(std::string("LOAD: ")+p);void* h=dlopen(p,RTLD_NOW|RTLD_LOCAL);
        if(!h){const char* e=dlerror();throw std::runtime_error(e?e:"dlopen failed");}libraries.push_back(h);return h;}
    static Tensor tensor(Tensor* t,uint32_t h,uint32_t w,uint32_t c,uint32_t type,float& scale,int32_t& offset,int& bits){
        if(!t||t->version!=1)throw std::runtime_error("Expected tensor version 1");
        const auto& v=t->v1;
        // 0x408 = QNN_DATATYPE_UFIXED_POINT_8 with one scale/offset.
        // 0x408 UFIXED_POINT_8 / 0x416 UFIXED_POINT_16, one scale/offset each.
        if(v.type!=type||v.format!=0||(v.dataType!=0x408&&v.dataType!=0x416)||v.rank!=4||!v.dimensions||v.quant.encoding!=0)
            throw std::runtime_error("Unsupported VSR tensor dataType=0x"+[&]{char b[16];std::snprintf(b,sizeof b,"%x",unsigned(v.dataType));return std::string(b);}());
        if(v.dimensions[0]!=1||v.dimensions[1]!=h||v.dimensions[2]!=w||v.dimensions[3]!=c)throw std::runtime_error("Unexpected VSR dimensions");
        std::memcpy(&scale,v.quant.payload,4);std::memcpy(&offset,v.quant.payload+4,4);bits=v.dataType==0x416?16:8;
        log("VSR TENSOR: "+std::string(v.name?v.name:"?")+" UFIXED"+std::to_string(bits)+" 1x"+std::to_string(h)+"x"+std::to_string(w)+"x"+std::to_string(c)
            +" scale="+std::to_string(scale)+" offset="+std::to_string(offset));
        return Tensor{1,v};
    }
public:
    Spec s;
    // Real values: input in 8-bit RGB units (0..255), output residual in the same units.
    std::vector<float> input,output;
    float inScale=1,outScale=1;int32_t inOffset=0,outOffset=0;int inBits=8,outBits=8;
    float unit=1;   // network value per 8-bit RGB unit (1 or 1/255, from the input range)
    std::vector<uint8_t> inRaw,outRaw;
    explicit VsrSession(const Spec& spec):s(spec),input(size_t(spec.gridW)*spec.gridH*spec.inC),output(size_t(spec.gridW)*spec.gridH*48){}
    void init(const std::string& dir){
        model=read(dir+s.file);
        if(model.size()!=s.bytes)throw std::runtime_error("Unexpected VSR model length");
        auto system=load((dir+"/libQnnSystem.so").c_str());
        auto getSystem=reinterpret_cast<Error(*)(const SystemProvider***,uint32_t*)>(dlsym(system,"QnnSystemInterface_getProviders"));
        if(!getSystem)throw std::runtime_error("No System provider entry");
        const SystemProvider** systems=nullptr;uint32_t n=0;check(getSystem(&systems,&n),"System providers");
        for(uint32_t i=0;i<n&&i<16;i++)if(systems[i]&&systems[i]->version.major==1&&systems[i]->version.minor==2)sys=systems[i];
        if(!sys)throw std::runtime_error("System 1.2 required");
        Handle created=nullptr;check(sys->create(&created),"System create");metadata=created;
        const void* info=nullptr;uint64_t bytes=0;check(sys->info(metadata,model.data(),model.size(),&info,&bytes),"System metadata");
        if(!info)throw std::runtime_error("Empty binary metadata");
        uint32_t version;std::memcpy(&version,info,4);
        GraphPrefix* g=nullptr;uint32_t graphs=0;auto body=static_cast<const uint8_t*>(info)+8;
        if(version==1||version==2){auto b=reinterpret_cast<const BinaryV1Prefix*>(body);g=b->graph;graphs=b->graphs;}
        else if(version==3){auto b=reinterpret_cast<const BinaryV3Prefix*>(body);g=b->graph;graphs=b->graphs;}
        else throw std::runtime_error("Unsupported binary metadata version");
        if(graphs!=1||!g||g->inputs!=1||g->outputs!=1||!g->name||std::strcmp(g->name,s.graph))throw std::runtime_error("Unexpected VSR graph");
        log("VSR GRAPH: "+std::string(g->name));
        in=tensor(g->input,s.gridH,s.gridW,s.inC,0,inScale,inOffset,inBits);
        out=tensor(g->output,s.gridH,s.gridW,48,1,outScale,outOffset,outBits);
        const float inRange=(float((1u<<inBits)-1)+float(inOffset))*inScale;
        unit=inRange>32.f?1.f:1.f/255.f;
        log("VSR DOMAIN: input range "+std::to_string(float(inOffset)*inScale)+".."+std::to_string(inRange)+" -> "+(unit==1.f?"0..255":"0..1")+" RGB");
        inRaw.resize(input.size()*(inBits/8));outRaw.resize(output.size()*(outBits/8));
        load("libcdsprpc.so");
        load((dir+"/libQnnHtpV79Stub.so").c_str());
        auto htp=load((dir+"/libQnnHtp.so").c_str());
        auto get=reinterpret_cast<Error(*)(const Provider***,uint32_t*)>(dlsym(htp,"QnnInterface_getProviders"));
        if(!get)throw std::runtime_error("No HTP providers");
        const Provider** providers=nullptr;n=0;check(get(&providers,&n),"HTP providers");
        for(uint32_t i=0;i<n&&i<16;i++)if(providers[i]&&providers[i]->core.major==2&&providers[i]->core.minor==22)api=providers[i];
        if(!api||api->id!=6)throw std::runtime_error("Expected HTP Core API 2.22");
        for(int slot:{1,8,13,14,20,21,40,43})if(!api->slots[slot])throw std::runtime_error("Missing required QNN function");
        created=nullptr;check(fn<Error(*)(Handle,const void**,Handle*)>(1)(nullptr,nullptr,&created),"Backend create");backend=created;
        created=nullptr;check(fn<Error(*)(Handle,const void**,Handle*)>(40)(nullptr,nullptr,&created),"Device create");device=created;
        qnn_perf::voteHtpPerformance(api,[](const std::string& line){log(line);});
        created=nullptr;check(fn<Error(*)(Handle,Handle,const void**,const void*,uint64_t,Handle*,Handle)>(13)(backend,device,nullptr,model.data(),model.size(),&created,nullptr),"Context create");context=created;
        check(fn<Error(*)(Handle,const char*,Handle*)>(20)(context,g->name,&graph),"Graph retrieve");
        in.v1.memType=0;out.v1.memType=0;
    }
    void execute(){
        const float qmax=float((1u<<inBits)-1);
        for(size_t i=0;i<input.size();++i){
            const float q=std::max(0.f,std::min(qmax,std::nearbyint(input[i]*unit/inScale)-float(inOffset)));
            if(inBits==16){const uint16_t v=uint16_t(q);std::memcpy(&inRaw[i*2],&v,2);}else inRaw[i]=uint8_t(q);
        }
        in.v1.client={inRaw.data(),uint32_t(inRaw.size())};
        out.v1.client={outRaw.data(),uint32_t(outRaw.size())};
        check(fn<Error(*)(Handle,const Tensor*,uint32_t,Tensor*,uint32_t,Handle,Handle)>(21)(graph,&in,1,&out,1,nullptr,nullptr),"Graph execute");
        for(size_t i=0;i<output.size();++i){
            float q;if(outBits==16){uint16_t v;std::memcpy(&v,&outRaw[i*2],2);q=v;}else q=outRaw[i];
            output[i]=(q+float(outOffset))*outScale/unit;
        }
    }
    ~VsrSession(){
        if(api){if(context&&api->slots[14])reinterpret_cast<Error(*)(Handle,Handle)>(api->slots[14])(context,nullptr);
            if(device&&api->slots[43])reinterpret_cast<Error(*)(Handle)>(api->slots[43])(device);
            if(backend&&api->slots[8])reinterpret_cast<Error(*)(Handle)>(api->slots[8])(backend);}
        if(sys&&metadata)sys->free(metadata);
    }
};

// rgb: w*h*3 bytes; returns (w*scale)*(h*scale)*3 bytes. blend in [0,1] mixes the
// network residual (1 = vendor output, 0 = nearest/unchanged source).
inline std::vector<uint8_t> upscale(VsrSession& net,const std::vector<uint8_t>& rgb,int w,int h,float blend) {
    const Spec& s=net.s;
    const int tileW=s.gridW*s.cell,tileH=s.gridH*s.cell;     // source pixels per tile
    const int margin=s.scale==4?16:32;                        // source pixels cropped per side
    const int coreW=tileW-2*margin,coreH=tileH-2*margin;
    const int ow=w*s.scale,oh=h*s.scale;
    std::vector<uint8_t> result(size_t(ow)*oh*3);
    auto src=[&](int x,int y,int c)->uint8_t{
        x=std::max(0,std::min(w-1,x));y=std::max(0,std::min(h-1,y));
        return rgb[(size_t(y)*w+x)*3+c];
    };
    std::vector<int> xs,ys;
    for(int x=0;;x+=coreW){xs.push_back(std::min(x,std::max(0,w-coreW)));if(x+coreW>=w)break;}
    for(int y=0;;y+=coreH){ys.push_back(std::min(y,std::max(0,h-coreH)));if(y+coreH>=h)break;}
    double npuMs=0;int tiles=0;
    const auto started=std::chrono::steady_clock::now();
    for(int cy:ys)for(int cx:xs){
        const int ox=cx-margin,oy=cy-margin;   // tile origin in source pixels
        // Pack: model pixel (gx,gy) covers a cell x cell source block.
        for(int gy=0;gy<s.gridH;++gy)for(int gx=0;gx<s.gridW;++gx){
            float* p=&net.input[(size_t(gy)*s.gridW+gx)*s.inC];
            if(s.cell==1){for(int c=0;c<3;++c)p[c]=src(ox+gx,oy+gy,c);}
            else for(int c=0;c<3;++c)for(int k=0;k<s.cell*s.cell;++k)
                p[c*s.cell*s.cell+k]=src(ox+gx*s.cell+k%s.cell,oy+gy*s.cell+k/s.cell,c);
        }
        const auto t=std::chrono::steady_clock::now();net.execute();
        npuMs+=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-t).count();++tiles;
        // Unpack: model pixel -> 4x4 output block; output px per source px = scale.
        for(int gy=0;gy<s.gridH;++gy)for(int gx=0;gx<s.gridW;++gx){
            const float* q=&net.output[(size_t(gy)*s.gridW+gx)*48];
            for(int r=0;r<4;++r)for(int col=0;col<4;++col){
                // Output pixel inside the tile (output resolution).
                const int tx=gx*4+col,ty=gy*4+r;
                const int X=ox*s.scale+tx,Y=oy*s.scale+ty;
                if(X<cx*s.scale||Y<cy*s.scale||X>=(cx+coreW)*s.scale||Y>=(cy+coreH)*s.scale||X>=ow||Y>=oh)continue;
                // Nearest source pixel of this output pixel.
                const int sx=ox+tx/s.scale,sy=oy+ty/s.scale;
                for(int c=0;c<3;++c){
                    const float base=src(sx,sy,c);
                    const float residual=q[c*16+r*4+col];
                    const float v=base+blend*residual;
                    result[(size_t(Y)*ow+X)*3+c]=uint8_t(std::max(0.f,std::min(255.f,v+.5f)));
                }
            }
        }
    }
    log("VSR TIMING ms: tiles="+std::to_string(tiles)+" npu="+std::to_string(npuMs)+" total="
        +std::to_string(std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-started).count())
        +" "+std::to_string(w)+"x"+std::to_string(h)+" -> "+std::to_string(ow)+"x"+std::to_string(oh));
    return result;
}
}
