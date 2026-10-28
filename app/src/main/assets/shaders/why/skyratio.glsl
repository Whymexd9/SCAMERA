precision highp float;
precision highp int;
precision highp sampler2D;
// P378 review (WhyCombine, Ultra HDR on with a sky lowered by the sky term: the gain map's boost is the scene plane over the SDR, so
// an HDR display showed the lowered sky at its old light): the SDR's own ratio of the sky term - why/combine at whyLow (the kernel's
// same-size bypass fU 1, no detail, no dither) with it (WithSky) over the same without it (WithoutSky: every sky branch skipped),
// in display-linear luminance with the gain map's offset (ultrahdr/gainmap.glsl: sRGB decode, the base's luminance weights, epsU),
// boxU x boxU whyLow px a pixel. 1 exactly where the sky term did nothing (the ground, a backlit sky's level). ultrahdr/sceneluma
// scales the scene plane by it. Output: r the ratio.
uniform sampler2D WithSky;          // RGBA8, whyLow size: the display-encoded output with the sky term
uniform sampler2D WithoutSky;       // the same without it
uniform int boxU;                   // whyLow px along a side of a ratio pixel; <= 0 -> 1
uniform int p3U;                    // 1: the output is Display P3 (its luminance weights, as gainmap.glsl uP3); 0: Rec.709
uniform float epsU;                 // the gain map's offset (GainMapComputer.DECODE_OFFSET)
out vec4 Output;

float srgbToLinear(float c) {
    c = clamp(c, 0.0, 1.0);
    if (c <= 0.04045) return c / 12.92;
    return pow((c + 0.055) / 1.055, 2.4);
}

float lumOf(vec3 c) {
    vec3 l = vec3(srgbToLinear(c.r), srgbToLinear(c.g), srgbToLinear(c.b));
    return p3U == 1 ? dot(l, vec3(0.2290, 0.6917, 0.0793)) : dot(l, vec3(0.2126, 0.7152, 0.0722));
}

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    int k = max(boxU, 1);
    ivec2 last = textureSize(WithSky, 0) - ivec2(1);
    float a = 0.0, b = 0.0;
    for (int j = 0; j < k; j++)
        for (int i = 0; i < k; i++) {
            ivec2 p = min(xy * k + ivec2(i, j), last);
            a += lumOf(texelFetch(WithSky, p, 0).rgb);
            b += lumOf(texelFetch(WithoutSky, p, 0).rgb);
        }
    float n = float(k * k);
    float r = (a / n + epsU) / (b / n + epsU);
    Output = vec4(a == b ? 1.0 : r, 0.0, 0.0, 1.0);
}
