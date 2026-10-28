precision highp float;
precision highp int;
precision highp sampler2D;
// P378 (WhySky): the sky cells of whyLow for WhySkyMask.decide - one output pixel per cell of cellU x cellU whyLow px, sampled every
// stepU px (at most 32 x 32 samples a cell; WhySkyMask.Cells.of is the same on the CPU). Y = the Rec.709 luminance, at least 1e-6;
// a negative or non-finite channel counts as 0.
//   MODE 0: the mean R, G, B and the mean log2 Y
//   MODE 1: the mean (log2 Y)^2, the texture (|log2 Y - log2 Y of the sample stepU px to the right| and of the one below,
//           averaged; the neighbour clamped into the image), the largest channel, the share of samples whose largest channel
//           reaches clipU
#define MODE 0
uniform sampler2D InputBuffer;      // whyLow after bracket_dn (linear Rec.709)
uniform int cellU;                  // the cell's side, whyLow px
uniform int stepU;                  // the sample stride
uniform float clipU;                // MODE 1: the clipped level of the largest channel (0.9 x the data ceiling)
out vec4 Output;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

vec3 tap(ivec2 p) {
    vec3 c = texelFetch(InputBuffer, p, 0).rgb;
    return vec3(c.r > 0.0 && c.r < 1.0e30 ? c.r : 0.0, c.g > 0.0 && c.g < 1.0e30 ? c.g : 0.0, c.b > 0.0 && c.b < 1.0e30 ? c.b : 0.0);
}

float lum(vec3 c) { return log2(max(dot(c, LUMA), 0.000001)); }

void main() {
    ivec2 size = textureSize(InputBuffer, 0);
    ivec2 o = ivec2(gl_FragCoord.xy) * cellU;
    ivec2 e = min(o + ivec2(cellU), size);
    int stride = max(stepU, 1);
    vec4 acc = vec4(0.0);
    float top = 0.0;
    float n = 0.0;
    for (int y = o.y; y < e.y; y += stride) {
        for (int x = o.x; x < e.x; x += stride) {
            vec3 v = tap(ivec2(x, y));
            float l = lum(v);
#if MODE == 0
            acc += vec4(v, l);
#else
            float right = lum(tap(ivec2(min(x + stride, size.x - 1), y)));
            float below = lum(tap(ivec2(x, min(y + stride, size.y - 1))));
            float m = max(v.r, max(v.g, v.b));
            top = max(top, m);
            acc += vec4(l * l, 0.5 * (abs(l - right) + abs(l - below)), 0.0, m >= clipU ? 1.0 : 0.0);
#endif
            n += 1.0;
        }
    }
    acc /= max(n, 1.0);
#if MODE == 1
    acc.z = top;
#endif
    Output = acc;
}
