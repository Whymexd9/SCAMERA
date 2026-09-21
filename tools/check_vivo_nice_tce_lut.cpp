#include "vivo-nice-tce-lut.h"
#include <cassert>
#include <numeric>
#include <iostream>
using namespace vivo_nice::tce_contract;
int main() {
    // Recorded stock extent. Binding must replace the stale address while
    // preserving every unrelated byte of the per-shot Process argument.
    std::vector<uint16_t> payload(33*33*33*3);
    std::iota(payload.begin(),payload.end(),uint16_t(0));
    const auto expected=payload;
    ToneColorLut lut(33,std::move(payload));
    ProcessArgument argument;argument.bytes.fill(0xa5);
    lut.bind(argument);
    assert(toneRead<uint32_t>(argument,0x370)==33);
    assert(toneRead<uint32_t>(argument,0x374)==107811);
    const auto* p=reinterpret_cast<const uint16_t*>(toneRead<uint64_t>(argument,0x378));
    assert(std::equal(expected.begin(),expected.end(),p));
    for(size_t i=0;i<argument.bytes.size();++i)
        if(i<0x370 || i>=0x380)assert(argument.bytes[i]==0xa5);
    for(uint32_t edge : {0u,33u,0xffffffffu}) {
        bool rejected=false;
        try {ToneColorLut invalid(edge,{});}catch(const std::invalid_argument&){rejected=true;}
        assert(rejected);
    }
    std::cout<<"PASS: owned RGB16 LUT, stock extent, sample preservation, isolated binding, invalid extent rejection\n";
}
