#pragma once
#include "vivo-raw-handoff.h"
#include "vivo-nice-capture.h"

namespace vivo_raw {
// Owns the RAW storage backing the existing NICE graph input. This adapter does
// not acquire HAL buffers or establish capture identity; the producer must do so.
class NiceInput {
    std::vector<Frame> frames_;
    vivo_nice::Burst input_;
public:
    explicit NiceInput(Burst& transaction):frames_(transaction.finish()) {
        const Frame& reference=frames_[0];
        input_.w=int(reference.width);input_.h=int(reference.height);
        input_.cfa=int(reference.cfa);input_.white=reference.white;
        input_.black=reference.black;
        // Do not infer TCE ADRC from a CRE DRC field. Scene values are absent
        // unless their identity and domain are supplied by a future producer.
        input_.scene.timestamp=reference.timestamp;
        input_.cameraNoise=true;input_.noiseReferenceSlot=4;
        input_.noise={frames_[4].noiseSlope,frames_[4].noiseOffset};
        input_.normalNoise={reference.noiseSlope,reference.noiseOffset};
        input_.hasNormalNoise=true;
        for(size_t i=0;i<frames_.size();++i) {
            const Frame& f=frames_[i];
            const double ratio=f.exposureProduct()/reference.exposureProduct();
            if(!std::isfinite(ratio) || ratio<1.0/256 || ratio>256)
                throw std::invalid_argument("RAW exposure outside NICE calibrated range");
            input_.exposure[i]=float(ratio);input_.iso[i]=f.iso;
            input_.ae[i]=f.ae;input_.raw[i]=f.pixels.data();
        }
    }
    NiceInput(const NiceInput&)=delete;
    NiceInput& operator=(const NiceInput&)=delete;
    NiceInput(NiceInput&&)=delete;
    NiceInput& operator=(NiceInput&&)=delete;
    const vivo_nice::Burst& view() const {return input_;}
};
} // namespace vivo_raw
