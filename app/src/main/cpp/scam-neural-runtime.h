#pragma once
#include "qnn-htp-perf.h"
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
#include <cstdio>
#include <stdio_ext.h> // __fpending (bionic API 23+)

// Minimal public QNN ABI prefixes, restricted to the hash-checked PD2454
// libraries and Core 2.18.0 / System 1.1.0. Runtime files come from APK assets.
// See docs/scam-neural-capture.md for source declarations and validation limits.
namespace scam_nn {
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
// A vendor library may print to stdout without a trailing newline (the CRE's first detect, on the warm-up thread: "Failed to
// load symbol  ntiLegacyMemMgrCreate"), and the next line of the worker then ran into it. Each own line is written and flushed
// whole while holding stdout's lock (std::cout goes through stdio's stdout and endl flushes it), so bytes still pending in
// stdout's buffer when a line starts are someone else's: they are ended with a newline first. The vendor text stays as printed.
inline void log(const std::string& s) {
    flockfile(stdout);
    if(__fpending(stdout))std::putc('\n',stdout);
    std::cout<<s<<std::endl;
    funlockfile(stdout);
}
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
}

