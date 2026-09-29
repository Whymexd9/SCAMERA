#pragma once
// HTP performance vote through QnnDevice_getInfrastructure (QNN interface slot 39).
// Without a vote the HTP runs under default DCVS; measured on SM8750 with the NICE
// graph: 29.8 ms -> 14.5 ms per execution. The ABI below was read from the bundled
// libQnnHtp.so (v2.29.8): the infrastructure holds type (0 = PERF) and, at +8/+16/+24,
// htpPerfInfrastructure{CreatePowerConfigId(deviceId,coreId,uint32_t*),
// DestroyPowerConfigId(id), SetPowerConfig(id,const cfg**)}; option 1 = DCVS_V3 with
// contextId,setDcvsEnable,dcvsEnable,powerMode,setSleepLatency,sleepLatency,
// setSleepDisable,sleepDisable,setBusParams,bus{Min,Target,Max},setCoreParams,
// core{Min,Target,Max} as 32-bit fields after the option.
// Any mismatch only logs and leaves the default mode: it must never fail a capture.
#include <cstdint>
#include <cstring>
#include <dlfcn.h>
#include <functional>
#include <string>

namespace qnn_perf {

template<class Provider>
inline void voteHtpPerformance(const Provider* api,const std::function<void(const std::string&)>& report) {
    try {
        if(!api||!api->slots[39]){report("HTP PERF: infrastructure API unavailable; default DCVS");return;}
        using Error=uint64_t;
        void* infra=nullptr;
        if(reinterpret_cast<Error(*)(void**)>(api->slots[39])(&infra)||!infra){report("HTP PERF: no device infrastructure; default DCVS");return;}
        uint32_t type;std::memcpy(&type,infra,4);
        uint64_t fns[3];std::memcpy(fns,static_cast<const uint8_t*>(infra)+8,sizeof(fns));
        if(type!=0){report("HTP PERF: unexpected infrastructure type "+std::to_string(type)+"; default DCVS");return;}
        for(uint64_t f:fns){
            Dl_info info{};
            if(!f||!dladdr(reinterpret_cast<void*>(f),&info)||!info.dli_fname||!std::strstr(info.dli_fname,"libQnnHtp.so")){
                report("HTP PERF: infrastructure pointers outside libQnnHtp.so; default DCVS");return;
            }
        }
        auto create=reinterpret_cast<Error(*)(uint32_t,uint32_t,uint32_t*)>(fns[0]);
        auto set=reinterpret_cast<Error(*)(uint32_t,const void**)>(fns[2]);
        uint32_t id=0;
        if(Error e=create(0,0,&id)){report("HTP PERF: createPowerConfigId error "+std::to_string(e)+"; default DCVS");return;}
        constexpr uint32_t maxCorner=0xA0,performanceMode=0x10,sleepLatencyUs=40;
        alignas(8) uint32_t cfg[32]{};
        cfg[0]=1;            // DCVS_V3
        cfg[1]=id;           // contextId
        cfg[2]=1;cfg[3]=0;   // set, disable DCVS
        cfg[4]=performanceMode;
        cfg[5]=1;cfg[6]=sleepLatencyUs;
        cfg[9]=1;cfg[10]=maxCorner;cfg[11]=maxCorner;cfg[12]=maxCorner;   // bus
        cfg[13]=1;cfg[14]=maxCorner;cfg[15]=maxCorner;cfg[16]=maxCorner;  // core
        const void* list[2]={cfg,nullptr};
        if(Error e=set(id,list)){report("HTP PERF: setPowerConfig error "+std::to_string(e)+"; default DCVS");return;}
        // The vote lives until this one-job process exits.
        report("HTP PERF: DCVS off, performance mode, max bus/core corners (power config "+std::to_string(id)+")");
    } catch(...) {report("HTP PERF: vote failed; default DCVS");}
}

}
