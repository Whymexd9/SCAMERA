// Richardson-Lucy deconvolution, second half of one iteration (RT's GAUSS_MULT
// step in ImProcFunctions::deconvsharpening, rtengine/ipsharpen.cc).
//
// RawTherapee is Copyright (c) 2004-2010 Gabor Horvath and the RawTherapee
// development team, licensed under the GNU General Public License v3 or later.
// This file is a GLSL reimplementation and carries the same license.
// https://github.com/RawTherapee/RawTherapee
precision highp float;
precision mediump sampler2D;
/** Output of rtdeconv1 for this iteration (tmp in RT). */
uniform sampler2D RatioBuffer;
/** Current estimate, multiplied by the blurred ratio (tmpI in RT). */
uniform sampler2D EstimateBuffer;
uniform float radius;
out vec3 Output;
#define INSIZE 1,1
#import coords

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    float sigma = max(radius, 0.05);
    float sum = 0.0;
    float wsum = 0.0;
    for (int i = -3; i <= 3; i++) {
        for (int j = -3; j <= 3; j++) {
            float w = exp(-float(i * i + j * j) / (2.0 * sigma * sigma));
            sum += texelFetch(RatioBuffer, mirrorCoords2(xy + ivec2(i, j), ivec2(INSIZE)), 0).r * w;
            wsum += w;
        }
    }
    float blurredRatio = sum / max(wsum, 1e-5);
    float estimate = texelFetch(EstimateBuffer, xy, 0).r;
    Output = vec3(max(estimate * blurredRatio, 0.0), 0.0, 0.0);
}
