#pragma once
#include <array>
#include <cmath>
#include <limits>
#include <stdexcept>

namespace scam {
struct DonorPoint { float x,y; };
// Backward mapping, destination -> donor. CRE CL passes eight row-major
// coefficients as two float4s; the bottom-right coefficient is implicitly 1.
// Estimation/acceptance of this matrix is a separate, still incomplete stage.
[[noreturn]] __attribute__((noinline,cold)) inline void homographyFailure(const char* what) {
    throw std::invalid_argument(what);
}
// Validated once where the matrix is produced (inverseToRaw, capture setup);
// the per-pixel calls below only keep cheap guards with an out-of-line throw,
// so they inline into the samplers (validate() per pixel was ~5% of SCAM time).
struct BackwardHomography {
    std::array<float,8> h{1,0,0,0,1,0,0,0};
    float upRatio=1;
    void validate() const {
        for(float value:h)if(!std::isfinite(value))
            throw std::invalid_argument("Nonfinite SCAM homography");
        if(!std::isfinite(upRatio)||upRatio<=0)
            throw std::invalid_argument("Invalid SCAM homography upRatio");
    }
    DonorPoint project(int x,int y) const {
        const float denominator=h[6]*x+h[7]*y+1.f;
        if(__builtin_expect(!std::isfinite(denominator)||denominator==0,0))
            homographyFailure("Singular SCAM homography coordinate");
        const float scale=1.f/denominator;
        return {(h[0]*x+h[1]*y+h[2])*scale,
                (h[3]*x+h[4]*y+h[5])*scale};
    }
    static void checkCoordinate(DonorPoint p) {
        // Avoid undefined float->int conversion in the scalar sampler. This
        // is a memory-safety bound, not the donor's motion-acceptance policy.
        constexpr float bound=float(std::numeric_limits<int>::max()/4);
        if(__builtin_expect(!(std::abs(p.x)<=bound&&std::abs(p.y)<=bound),0))
            homographyFailure("SCAM homography coordinate out of range");
    }
    DonorPoint bayerOrigin(int x,int y) const {
        auto p=project(x&~1,y&~1);
        if(upRatio!=1){p.x/=upRatio;p.y/=upRatio;}
        checkCoordinate(p);return p;
    }
    DonorPoint shortPosition(int x,int y) const {
        auto p=project(x,y);
        // swarp=6 adds half a pixel BEFORE upRatio division. warp=2 instead
        // adds its rounding offset after that division in the sampler.
        p.x+=.5f;p.y+=.5f;
        if(upRatio!=1){p.x/=upRatio;p.y/=upRatio;}
        checkCoordinate(p);return p;
    }
};
} // namespace scam
