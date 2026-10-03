precision highp float;
precision highp sampler2D;
// SCAM HDR hybrid: final resize of the display-encoded image (after sharpening) on the GPU, so the 50 MP Sabre 2x
// grid never reaches the readback / JPEG stage. Filtering runs in linear light (sRGB decode/encode) like the CPU
// VivoPostDownscale. kernel: 0 Lanczos-3, 1 bicubic (Lanczos-2), 2 area (box of the scale ratio, partial coverage),
// 3 bilinear. The support scales with the ratio (scale-aware), ratios up to 4:1.
uniform sampler2D InputBuffer;
uniform int yOffset;
uniform int kernel;
uniform vec2 scale;      // input pixels per output pixel, >= 1
out vec4 Output;
const float PI = 3.14159265358979;
float sincf(float x) { float p = PI * x; return abs(x) < 1.0e-4 ? 1.0 : sin(p) / p; }
float lanczos(float x, float lobes) { return abs(x) < lobes ? sincf(x) * sincf(x / lobes) : 0.0; }
vec3 toLinear(vec3 c) { return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(0.04045, c)); }
vec3 toDisplay(vec3 c) { return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, c)); }
vec3 fetchLin(ivec2 p, ivec2 hi) { return toLinear(clamp(texelFetch(InputBuffer, clamp(p, ivec2(0), hi), 0).rgb, 0.0, 1.0)); }
float boxW(float i, float c, float r) { return clamp(min(i + 0.5, c + r) - max(i - 0.5, c - r), 0.0, 1.0); }
void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy) + ivec2(0, yOffset);
    ivec2 inSize = textureSize(InputBuffer, 0), hi = inSize - ivec2(1);
    vec2 center = (vec2(xy) + 0.5) * scale - 0.5;   // input-pixel coordinates of the output pixel centre
    vec3 sum = vec3(0.0);
    float wsum = 0.0;
    if (kernel == 3) {
        vec2 f = floor(center), t = center - f;
        ivec2 b = ivec2(f);
        vec3 c00 = fetchLin(b, hi), c10 = fetchLin(b + ivec2(1, 0), hi);
        vec3 c01 = fetchLin(b + ivec2(0, 1), hi), c11 = fetchLin(b + ivec2(1, 1), hi);
        sum = mix(mix(c00, c10, t.x), mix(c01, c11, t.x), t.y);
        wsum = 1.0;
    } else {
        float lobes = kernel == 1 ? 2.0 : 3.0;
        vec2 radius = kernel == 2 ? 0.5 * scale : lobes * scale;
        ivec2 lo = max(ivec2(ceil(center - radius)), ivec2(0)), up = min(ivec2(floor(center + radius)), hi);
        for (int j = lo.y; j <= up.y; j++) {
            float wy = kernel == 2 ? boxW(float(j), center.y, radius.y) : lanczos((float(j) - center.y) / scale.y, lobes);
            if (wy == 0.0) continue;
            for (int i = lo.x; i <= up.x; i++) {
                float wx = kernel == 2 ? boxW(float(i), center.x, radius.x) : lanczos((float(i) - center.x) / scale.x, lobes);
                float w = wx * wy;
                if (w == 0.0) continue;
                sum += fetchLin(ivec2(i, j), hi) * w;
                wsum += w;
            }
        }
    }
    Output = vec4(toDisplay(clamp(sum / max(wsum, 1.0e-6), 0.0, 1.0)), 1.0);
}
