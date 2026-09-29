#pragma once
#include "vivo-aec-solver.h"
namespace vivo_aec {
struct SnapshotReader {
    const uint8_t* cursor;size_t remaining;
    void copy(void* out,size_t count) {
        if(count>remaining)throw std::invalid_argument("Truncated AE snapshot");
        std::memcpy(out,cursor,count);cursor+=count;remaining-=count;
    }
    template<class T>T read(){T value;copy(&value,sizeof(value));return value;}
    TuningBank bank() {
        TuningBank out;const auto count=read<uint32_t>();
        if(count<1 || count>16)throw std::invalid_argument("AE bank extent");
        for(unsigned i=0;i<count;++i) {
            TuningTable table;table.divisor=read<float>();table.bandingTolerance=read<float>();
            const auto rows=read<uint32_t>();if(rows<2 || rows>1024)throw std::invalid_argument("AE table extent");
            for(unsigned j=0;j<rows;++j) {
                float gain=read<float>();uint64_t shutter=read<uint64_t>();uint32_t first=read<uint32_t>();
                table.rows.push_back({gain,shutter,first!=0});
            }
            out.tables.push_back(std::move(table));
        }
        const auto countBlur=read<uint32_t>();if(countBlur>1024)throw std::invalid_argument("AE blur extent");
        for(unsigned i=0;i<countBlur;++i)out.blur.push_back(read<BlurRow>());
        return out;
    }
};
inline SolverSnapshot decodeSolverSnapshot(const uint8_t* payload,size_t size) {
    if(!payload || size>1024*1024)throw std::invalid_argument("AE snapshot size");
    SnapshotReader r{payload,size};SolverSnapshot s;
    r.copy(s.params.bytes.data(),0xe8);r.copy(s.common.bytes.data(),0xb8);r.copy(s.calculator.bytes.data(),0x74);
    r.copy(s.state.bytes.data(),0x930);r.copy(s.tuning.bytes.data(),0x440);r.copy(s.sensor.bytes.data(),12);
    r.copy(s.camera.bytes.data(),8);r.copy(s.debugFlags.bytes.data(),0xb0);r.copy(s.debugEv.bytes.data(),0x44);
    r.copy(s.motion.bytes.data(),0x65c);s.sensorType=r.read<int32_t>();s.primary=r.bank();s.alternate=r.bank();
    if(r.remaining)throw std::invalid_argument("Unexpected AE snapshot tail");
    return s;
}
inline std::array<uint8_t,100> encodeSolverPlan(const SolverPlan& p) {
    if(!p.shortEnabled)throw std::invalid_argument("Stock scene disables short RAWs");
    std::array<uint8_t,100> out{};
    std::memcpy(out.data(),&p.normal,16);std::memcpy(out.data()+16,&p.shortFrame,16);
    std::memcpy(out.data()+32,&p.extraShortFrame,16);std::memcpy(out.data()+48,&p.longFrame,16);
    auto put=[&](size_t offset,auto value){std::memcpy(out.data()+offset,&value,4);};
    put(64,p.ratioMilli);put(68,p.evMilli);put(72,p.hdrFlags);put(76,p.tableType);put(80,p.tableId);
    put(84,p.targetRatio);put(88,p.correctionMilli);put(92,p.deltaEv);put(96,p.deltaMultiplier);
    return out;
}
} // namespace vivo_aec
