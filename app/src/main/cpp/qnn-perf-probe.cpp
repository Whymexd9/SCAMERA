// Diagnostic only (not shipped): measures SCAM graph execution time and dumps the
// HTP device infrastructure returned by QnnDevice_getInfrastructure (interface
// slot 39), so the performance-vote ABI is read from the device, not guessed.
#define SCAM_HOST_TEST 1
#include "scam-probe.cpp"
#include <chrono>
#include <cinttypes>
#include <cstdio>
#include <dlfcn.h>

static double timeExecutions(scam::Graph& g,int n) {
    const auto t0=std::chrono::steady_clock::now();
    for(int i=0;i<n;++i)g.execute();
    return std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-t0).count()/n;
}

int main(int argc,char** argv) {
    if(argc<2){std::fprintf(stderr,"usage: %s <asset dir>\n",argv[0]);return 2;}
    try {
        scam::Graph g(argv[1],[](const std::string& s){std::printf("%s\n",s.c_str());});
        std::fill(g.input.begin(),g.input.end(),0.1f);
        g.execute();g.execute();
        std::printf("BASELINE ms/exec: %.2f\n",timeExecutions(g,20));
        auto getInfra=g.s.fn<scam_nn::Error(*)(void**)>(39);
        void* infra=nullptr;
        scam_nn::Error e=getInfra(&infra);
        std::printf("getInfrastructure error=%" PRIu64 " ptr=%p\n",e,infra);
        if(!e&&infra){
            auto u32=static_cast<const uint32_t*>(infra);
            for(int i=0;i<16;++i)std::printf("u32[%d]=0x%08x\n",i,u32[i]);
            auto u64=static_cast<const uint64_t*>(infra);
            for(int i=0;i<8;++i){
                Dl_info info{};
                const bool code=dladdr(reinterpret_cast<void*>(u64[i]),&info)!=0;
                std::printf("u64[%d]=0x%016" PRIx64 " %s %s offset=0x%" PRIx64 "\n",i,u64[i],
                    code&&info.dli_fname?info.dli_fname:"-",code&&info.dli_sname?info.dli_sname:"-",
                    code?uint64_t(u64[i]-reinterpret_cast<uintptr_t>(info.dli_fbase)):uint64_t(0));
            }
            // Layout read from libQnnHtp.so v2.29.8: +8 createPowerConfigId(deviceId,coreId,uint32_t*),
            // +16 destroy(id), +24 setPowerConfig(id,const cfg**); cfg: option(1=DCVS_V3) then
            // contextId,setDcvsEnable,dcvsEnable,powerMode,setSleepLatency,sleepLatency,setSleepDisable,
            // sleepDisable,setBusParams,busMin,busTarget,busMax,setCoreParams,coreMin,coreTarget,coreMax.
            using Create=scam_nn::Error(*)(uint32_t,uint32_t,uint32_t*);
            using Destroy=scam_nn::Error(*)(uint32_t);
            using Set=scam_nn::Error(*)(uint32_t,const void**);
            auto create=reinterpret_cast<Create>(u64[1]);auto destroy=reinterpret_cast<Destroy>(u64[2]);
            auto set=reinterpret_cast<Set>(u64[3]);
            uint32_t id=0;e=create(0,0,&id);
            std::printf("createPowerConfigId error=%" PRIu64 " id=%u\n",e,id);
            const uint32_t corner=argc>2?uint32_t(std::strtoul(argv[2],nullptr,0)):0xA0;
            alignas(8) uint32_t cfg[32]{};
            cfg[0]=1;cfg[1]=id;cfg[2]=1;cfg[3]=0;cfg[4]=0x10;cfg[5]=1;cfg[6]=40;cfg[7]=0;cfg[8]=0;
            cfg[9]=1;cfg[10]=corner;cfg[11]=corner;cfg[12]=corner;cfg[13]=1;cfg[14]=corner;cfg[15]=corner;cfg[16]=corner;
            const void* list[2]={cfg,nullptr};
            e=set(id,list);
            std::printf("setPowerConfig(DCVS_V3 performance, corner=0x%x) error=%" PRIu64 "\n",corner,e);
            g.execute();g.execute();
            std::printf("PERFORMANCE ms/exec: %.2f\n",timeExecutions(g,20));
            destroy(id);
        }
    } catch(const std::exception& ex){std::printf("ERROR: %s\n",ex.what());return 1;}
    return 0;
}
