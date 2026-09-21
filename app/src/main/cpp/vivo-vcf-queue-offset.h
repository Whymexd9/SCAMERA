#pragma once
#include <cstdint>
#include <climits>
#include <stdexcept>
#include <vector>

namespace vivo_vcf {
// getOffset 142dc0 and modifiedOffseByReq 140078. The motion callback must
// implement the full native policy, not just its ready-frame arithmetic.
template<class MotionPolicy>
inline int32_t queueOffset(const std::vector<uint32_t>& queueRequestIds,
        uint32_t requested, int32_t catchMode, uint8_t sync0, uint8_t sync1,
        const std::vector<uint32_t>& requestedIds, int32_t& onlyNeedNext,
        MotionPolicy&& motion) {
    if(queueRequestIds.size()>INT_MAX || requested>INT_MAX)
        throw std::invalid_argument("VCF queue count outside signed offset range");
    const int32_t initial=int32_t(queueRequestIds.size())-int32_t(requested);
    if(catchMode<1 || catchMode>6)return initial;
    if(sync0 && !sync1 && !requestedIds.empty()) {
        for(size_t i=0;i<queueRequestIds.size();++i)
            if(queueRequestIds[i]==requestedIds.front())return int32_t(i);
        return 0;
    }
    if(sync0 && sync1)onlyNeedNext=1;
    return motion(initial,onlyNeedNext);
}

// preparePastBuffersLocked 127618..127748 resets invalid offsets to zero.
// This is distinct from the lower-only clamp inside selectReadyFrames.
inline size_t pastQueueStart(int32_t offset,size_t queueCount) {
    return offset<0 || size_t(offset)>=queueCount ? 0 : size_t(offset);
}
}
