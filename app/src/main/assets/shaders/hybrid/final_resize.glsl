precision highp float;
precision highp int;
precision highp sampler2D;
// SCAM HDR hybrid: final resize of the display-encoded image (after sharpening) on the GPU, so the 50 MP Sabre 2x
// grid never reaches the readback / JPEG stage. Filtering runs in linear light (sRGB decode/encode) like the CPU
// ScamPostDownscale. kernel: 0 Lanczos-3, 1 bicubic (Lanczos-2), 2 area (box of the scale ratio, partial coverage),
// 3 bilinear. The support scales with the ratio (scale-aware), ratios up to 4:1; taps outside the image are dropped
// and the weights renormalised (as cpp/lanczos-downscale.h).
//
// Precision: Adreno evaluated the input-space centre (xy + 0.5) * scale - 0.5 in half precision although highp is
// declared, so above 2048 input px it snapped to 2- and 4-pixel steps (period-4 duplicate pixels in every 20 MP
// JPEG). The centre is therefore split exactly with integer arithmetic into an integer base and a fraction in
// [0, 1); every float below stays small (tap offsets, fraction, ratio).
uniform sampler2D InputBuffer;
uniform int yOffset;
uniform int kernel;
uniform ivec2 inSize;    // input size in pixels
uniform ivec2 outSize;   // output size in pixels
out vec4 Output;
const float PI = 3.14159265358979;
float sincf(float x) { float p = PI * x; return abs(x) < 1.0e-4 ? 1.0 : sin(p) / p; }
float lanczos(float x, float lobes) { return abs(x) < lobes ? sincf(x) * sincf(x / lobes) : 0.0; }
vec3 toLinear(vec3 c) { return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(0.04045, c)); }
vec3 toDisplay(vec3 c) { return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, c)); }
vec3 fetchLin(ivec2 p) { return toLinear(clamp(texelFetch(InputBuffer, p, 0).rgb, 0.0, 1.0)); }
// Overlap of the input pixel [d - 0.5, d + 0.5] with the box [f - r, f + r] (all relative to the base pixel).
float boxW(float d, float f, float r) { return clamp(min(d + 0.5, f + r) - max(d - 0.5, f - r), 0.0, 1.0); }
void main() {
    highp ivec2 xy = ivec2(gl_FragCoord.xy) + ivec2(0, yOffset);
    // centre = ((2 xy + 1) in - out) / (2 out): exact integer numerator (< 2^31 for 2x8192 -> any size)
    highp ivec2 den = 2 * outSize;
    highp ivec2 num = (2 * xy + 1) * inSize - outSize;
    highp ivec2 base = num / den;                           // num >= 0 because in >= out
    vec2 frac = vec2(num - base * den) / vec2(den);         // [0, 1)
    vec2 scale = vec2(inSize) / vec2(outSize);              // >= 1, small
    ivec2 hi = inSize - ivec2(1);
    vec3 sum = vec3(0.0);
    float wsum = 0.0;
    if (kernel == 3) {
        ivec2 b1 = min(base + ivec2(1), hi);
        vec3 c00 = fetchLin(base), c10 = fetchLin(ivec2(b1.x, base.y));
        vec3 c01 = fetchLin(ivec2(base.x, b1.y)), c11 = fetchLin(b1);
        sum = mix(mix(c00, c10, frac.x), mix(c01, c11, frac.x), frac.y);
        wsum = 1.0;
    } else {
        float lobes = kernel == 1 ? 2.0 : 3.0;
        vec2 radius = kernel == 2 ? 0.5 * scale : lobes * scale;
        ivec2 reach = ivec2(ceil(radius)) + ivec2(1);
        for (int dj = -reach.y; dj <= reach.y; dj++) {
            int y = base.y + dj;
            if (y < 0 || y > hi.y) continue;
            float wy = kernel == 2 ? boxW(float(dj), frac.y, radius.y) : lanczos((float(dj) - frac.y) / scale.y, lobes);
            if (wy == 0.0) continue;
            for (int di = -reach.x; di <= reach.x; di++) {
                int x = base.x + di;
                if (x < 0 || x > hi.x) continue;
                float wx = kernel == 2 ? boxW(float(di), frac.x, radius.x) : lanczos((float(di) - frac.x) / scale.x, lobes);
                float w = wx * wy;
                if (w == 0.0) continue;
                sum += fetchLin(ivec2(x, y)) * w;
                wsum += w;
            }
        }
    }
    Output = vec4(toDisplay(clamp(sum / max(wsum, 1.0e-6), 0.0, 1.0)), 1.0);
}
