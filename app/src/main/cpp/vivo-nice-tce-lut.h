#pragma once
#include "vivo-nice-tce-process.h"
#include <vector>
#include <limits>
#include <utility>

namespace vivo_nice::tce_contract {
// Process+370: cube edge; +374: element count; +378: borrowed uint16 pointer.
// TCE 3e685c..3e691c reads edge^3 RGB triples with ldrh and two-byte spacing.
// Own the samples through Process and session destruction. No stock address
// or per-shot LUT from a different capture can serve as calibration defaults.
class ToneColorLut {
    uint32_t edge_;
    std::vector<uint16_t> samples_;
    static uint32_t checkedCount(uint32_t edge) {
        if (edge == 0) throw std::invalid_argument("Empty TCE color LUT");
        uint64_t count = 3;
        for (int i=0;i<3;++i) {
            if (count > uint64_t(std::numeric_limits<int32_t>::max()) / edge)
                throw std::invalid_argument("TCE color LUT extent overflow");
            count *= edge;
        }
        return uint32_t(count);
    }
public:
    ToneColorLut(uint32_t edge, std::vector<uint16_t> samples)
        : edge_(edge), samples_(std::move(samples)) {
        if (samples_.size() != checkedCount(edge_))
            throw std::invalid_argument("TCE color LUT payload length mismatch");
    }
    ToneColorLut(const ToneColorLut&)=delete;
    ToneColorLut& operator=(const ToneColorLut&)=delete;
    ToneColorLut(ToneColorLut&&)=delete;
    ToneColorLut& operator=(ToneColorLut&&)=delete;
    void bind(ProcessArgument& argument) const {
        processPut(argument,0x370,edge_);
        processPut(argument,0x374,uint32_t(samples_.size()));
        processPut(argument,0x378,reinterpret_cast<uint64_t>(samples_.data()));
    }
};
} // namespace vivo_nice::tce_contract
