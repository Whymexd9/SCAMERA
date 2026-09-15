// Richardson-Lucy deconvolution, first half of one iteration, ported from
// RawTherapee's ImProcFunctions::deconvsharpening and dcdamping
// (rtengine/ipsharpen.cc).
//
// RawTherapee is Copyright (c) 2004-2010 Gabor Horvath and the RawTherapee
// development team, licensed under the GNU General Public License v3 or later.
// This file is a GLSL reimplementation and carries the same license.
// https://github.com/RawTherapee/RawTherapee
//
// RT runs, per iteration: blur(estimate) -> divide the original by it (GAUSS_DIV)
// or apply damping, then blur again and multiply the estimate (GAUSS_MULT). The
// second blur needs a neighbourhood of this pass's output, so the iteration is
// split across two shaders; this is the first.
precision highp float;
precision mediump sampler2D;
/** Current estimate (tmpI in RT). */
uniform sampler2D EstimateBuffer;
/** The untouched luminance the deconvolution is solving against. */
uniform sampler2D OriginalBuffer;
/** RT deconvradius, in pixels (RT default 0.75). */
uniform float radius;
/** RT deconvdamping/5, zero disables damping. */
uniform float damping;
/** PSF shape: 0 gaussian, 1 pillbox (defocus disc), 2 Airy (diffraction). */
uniform int kernelType;
out vec3 Output;
#define INSIZE 1,1
#import coords
#import psf

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    float r = max(radius, 0.05);
    float sum = 0.0;
    float wsum = 0.0;
    for (int i = -3; i <= 3; i++) {
        for (int j = -3; j <= 3; j++) {
            float w = psfWeight(kernelType, float(i), float(j), r);
            sum += texelFetch(EstimateBuffer, mirrorCoords2(xy + ivec2(i, j), ivec2(INSIZE)), 0).r * w;
            wsum += w;
        }
    }
    float blurred = sum / max(wsum, 1e-5);
    float original = dot(texelFetch(OriginalBuffer, xy, 0).rgb, LUMA);

    float result;
    if (damping <= 0.0) {
        // GAUSS_DIV
        result = original / max(blurred, 1e-5);
    } else {
        // RT dcdamping, with I = blurred, O = original. The quartic ramp leaves
        // strong detail untouched and fades the correction to nothing where the
        // discrepancy is within the damping level, so noise is not amplified
        // iteration after iteration.
        float I = blurred;
        float O = original;
        if (O <= 0.0 || I <= 0.0) {
            result = 0.0;
        } else {
            float dampingFac = -2.0 / (damping * damping);
            float U = (O * log(I / O) - I + O) * dampingFac;
            U = min(U, 1.0);
            U = U * U * U * U * (5.0 - U * 4.0);
            result = (O - I) / I * U + 1.0;
        }
    }
    Output = vec3(result, 0.0, 0.0);
}
