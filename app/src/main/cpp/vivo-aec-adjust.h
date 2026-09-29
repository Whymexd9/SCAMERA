#pragma once
#include "vivo-aec-exposure.h"
#include <vector>

namespace vivo_aec {
struct NormalAdjustment {
    float minGain, maxGain, minShutter, maxShutter;
    float period, bandingTolerance, sensorMinGain;
    bool bandingEnabled, blurActive;
    int mode;
};
struct AdjustedExposure { Exposure value; float correction; };
struct BlurRow { float product, pixels, maxGain; };

// VivoNormalEVExpAdjust (0x17a5fc, b2e3e124...). Motion is the native
// measurement at *(calculator+0x5f8)+4, not an app gyro norm.
inline AdjustedExposure normalExposureAdjustment(Exposure value, float correction,
        const NormalAdjustment& c, const std::vector<BlurRow>& blur={}, float motion=0.f) {
    const float required[]={value.shutter,value.gain,correction,c.minGain,c.maxGain,
                            c.minShutter,c.maxShutter};
    for (float x:required) if (!std::isfinite(x) || x<=0.f)
        throw std::invalid_argument("Invalid stock normal-exposure input");
    if (!std::isfinite(value.ev) || !std::isfinite(c.period) || c.period<0.f ||
        !std::isfinite(c.bandingTolerance) || c.bandingTolerance<0.f || c.bandingTolerance>1.f ||
        c.minGain>c.maxGain || c.minShutter>c.maxShutter || c.mode<0 || c.mode>4)
        throw std::invalid_argument("Invalid stock adjustment limits");
    const float before=value.gain*value.shutter;
    if (!std::isfinite(before)) throw std::invalid_argument("Stock exposure product overflow");
    value.gain=value.gain*correction;
    if (value.gain<c.minGain) {
        value.gain=c.minGain;
        value.shutter=(before*correction)/c.minGain;
        if (value.shutter<c.minShutter)value.shutter=c.minShutter;
    }
    const float product=value.gain*value.shutter;
    if (!std::isfinite(product)) throw std::invalid_argument("Stock corrected product overflow");
    if (c.blurActive) {
        if (blur.empty() || blur.size()>1024 || !std::isfinite(motion) || motion<0.f)
            throw std::invalid_argument("Missing stock motion/table input");
        float previous=0.f;
        for (const auto& row:blur) {
            if (!std::isfinite(row.product) || row.product<=previous ||
                !std::isfinite(row.pixels) || row.pixels<=0.f ||
                !std::isfinite(row.maxGain) || row.maxGain<=0.f)
                throw std::invalid_argument("Invalid stock blur table");
            previous=row.product;
        }
        float pixels=blur.front().pixels, cap=blur.front().maxGain;
        if (product>=blur.back().product) {
            pixels=blur.back().pixels;cap=blur.back().maxGain;
        } else if (product>blur.front().product) {
            for (size_t i=1;i<blur.size();++i) {
                const auto& lo=blur[i-1];const auto& hi=blur[i];
                if (product>hi.product)continue;
                if (product==hi.product) {pixels=hi.pixels;cap=hi.maxGain;}
                else {
                    const float delta=product-lo.product, span=hi.product-lo.product;
                    pixels=lo.pixels+(delta*(hi.pixels-lo.pixels))/span;
                    cap=lo.maxGain+(delta*(hi.maxGain-lo.maxGain))/span;
                }
                break;
            }
        }
        if (std::abs(motion)>=1.e-6f) {
            const float shutter=(pixels/motion)*1000000.f;
            if (!std::isfinite(shutter) || shutter<=0.f)
                throw std::invalid_argument("Invalid stock motion shutter");
            if (value.shutter>shutter) {value.shutter=shutter;value.gain=product/shutter;}
        }
        if (value.gain>cap) {value.shutter=product/cap;value.gain=cap;}
    }
    if (c.bandingEnabled && c.period>0.f) {
        const float quotient=value.shutter/c.period;
        if (!std::isfinite(quotient) || quotient>1000000.f)
            throw std::invalid_argument("Unbounded stock banding input");
        const int n=static_cast<int>(quotient);
        // ARM64 FMSUB keeps one rounding; separate multiplication differs.
        const float lowerDistance=std::abs(std::fma(-float(n),c.period,value.shutter));
        const float upperDistance=std::abs(std::fma(-float(n+1),c.period,value.shutter));
        const float threshold=c.period*(1.f-c.bandingTolerance);
        float snapped=0.f;
        if (lowerDistance>upperDistance) {
            if (upperDistance>threshold)snapped=c.period*float(n+1);
        } else if (lowerDistance>threshold)snapped=c.period*float(n);
        if (std::abs(snapped)>=1.e-6f) {
            float gain;unsigned iterations=0;
            while (snapped<c.minShutter || (gain=product/snapped)>c.maxGain) {
                const float next=snapped+c.period;
                if (++iterations>1000000 || next==snapped || !std::isfinite(next))
                    throw std::invalid_argument("Unbounded stock banding adjustment");
                snapped=next;
            }
            while (snapped>c.maxShutter || gain<c.minGain) {
                const float next=snapped-c.period;
                if (++iterations>1000000 || next<=0.f || next==snapped)
                    throw std::invalid_argument("No bounded stock banding solution");
                snapped=next;gain=product/snapped;
            }
            if (snapped>=c.minShutter && snapped<=c.maxShutter &&
                gain>=c.minGain && gain<=c.maxGain) {
                value.shutter=snapped;value.gain=gain;
            }
        }
    }
    float minimum=c.minGain;
    if (std::abs(minimum)<1.e-6f) {
        minimum=c.sensorMinGain;
        if (!std::isfinite(minimum) || minimum<=0.f)
            throw std::invalid_argument("Missing native sensor minimum gain");
    }
    if (c.mode==1 && value.shutter<c.period) {
        value.gain=std::max(minimum,(value.shutter*value.gain)/c.period);
        value.shutter=c.period;
    }
    correction=std::min((value.gain*value.shutter)/before,1.f);
    if (!std::isfinite(value.shutter) || !std::isfinite(value.gain) ||
        !std::isfinite(correction) || value.shutter<=0.f || value.gain<=0.f || correction<=0.f)
        throw std::invalid_argument("Invalid stock adjusted exposure");
    return {value,correction};
}

// Compatibility entry point for previously recorded inactive-branch checks.
inline AdjustedExposure normalExposureWithoutBlur(Exposure value, float correction,
                                                  const NormalAdjustment& c) {
    if (c.blurActive) throw std::invalid_argument("Active blur requires native motion and table");
    return normalExposureAdjustment(value,correction,c);
}
} // namespace vivo_aec
