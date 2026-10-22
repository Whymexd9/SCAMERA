precision highp float;
precision highp sampler2D;
// P228 lens filters on the linear scene-referred RGB before the WHY tone (the CPU model: processing/color/LensFilters):
// FILTER 1 Black Pro-Mist, 2 CineBloom, 3 Glimmerglass, 4 Pearlescent, 5 Fog, 6 Double Fog, 7 GND, 8 Light Pollution,
// 9 Streak, 10 Prism, 11 Kaleidoscope, 12 Infrared.
#ifndef FILTER
#define FILTER 1
#endif
uniform sampler2D InputBuffer;
// owner, 2026-10-18: the diffusion filters (1..6) as a physical model (DiffusionModel) - out = (1 - p) E + p (K * E), all the
// light, energy kept: the image itself times w0 plus its pyramid's levels 1.. weighted per channel, summed from the coarse end with
// B-spline steps (lensfilter/upsum.glsl) and B-spline upsampled here (no square highlights)
uniform sampler2D DiffAcc;  // Σ_k W_k L_k at level 1's size
uniform vec3 w0;            // the image's own weight (1 - p + the PSF's sharp part)
uniform sampler2D VeilAcc;  // 0.2 V4 + 0.4 V5 + 0.4 V6 of the image's own pyramid, at V4's size (the prism's ghost)
uniform sampler2D Streaks;
uniform vec3 tint;
uniform float strength;
uniform float param;
uniform float segments;
// the photo's turn as RotateWatermark makes it (0: none, 1: 270, 2: 180, 3: 90 degrees): GND, Prism and the Streak's direction
// work in the photo's own frame (its top is the sky), not the sensor's
uniform int rot;
out vec4 Output;
// the photo's (u, v), v 0 at its top, of a sensor uv; and back
vec2 photoOf(vec2 uv) {
    if (rot == 1) return vec2(uv.y, 1.0 - uv.x);
    if (rot == 2) return vec2(1.0 - uv.x, 1.0 - uv.y);
    if (rot == 3) return vec2(1.0 - uv.y, uv.x);
    return uv;
}
vec2 sensorOf(vec2 p) {
    if (rot == 1) return vec2(1.0 - p.y, p.x);
    if (rot == 2) return vec2(1.0 - p.x, 1.0 - p.y);
    if (rot == 3) return vec2(p.y, 1.0 - p.x);
    return p;
}
vec3 bl(sampler2D t, vec2 uv) {
    ivec2 size = textureSize(t, 0);
    vec2 q = uv * vec2(size) - 0.5;
    ivec2 i0 = ivec2(floor(q));
    vec2 f = q - vec2(i0);
    ivec2 last = size - ivec2(1);
    vec3 a = texelFetch(t, clamp(i0, ivec2(0), last), 0).rgb;
    vec3 b = texelFetch(t, clamp(i0 + ivec2(1, 0), ivec2(0), last), 0).rgb;
    vec3 c = texelFetch(t, clamp(i0 + ivec2(0, 1), ivec2(0), last), 0).rgb;
    vec3 d = texelFetch(t, clamp(i0 + ivec2(1, 1), ivec2(0), last), 0).rgb;
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}
vec3 bspline(sampler2D t, vec2 uv) {
    ivec2 size = textureSize(t, 0);
    vec2 q = uv * vec2(size) - 0.5;
    vec2 i = floor(q);
    vec2 f = q - i;
    vec2 f2 = f * f, f3 = f2 * f;
    vec2 w0 = (1.0 - 3.0 * f + 3.0 * f2 - f3) / 6.0;
    vec2 w1 = (4.0 - 6.0 * f2 + 3.0 * f3) / 6.0;
    vec2 w2 = (1.0 + 3.0 * f + 3.0 * f2 - 3.0 * f3) / 6.0;
    vec2 w3 = f3 / 6.0;
    float wx[4] = float[4](w0.x, w1.x, w2.x, w3.x);
    float wy[4] = float[4](w0.y, w1.y, w2.y, w3.y);
    ivec2 last = size - ivec2(1);
    vec3 s = vec3(0.0);
    for (int y = 0; y < 4; y++) {
        vec3 row = vec3(0.0);
        for (int x = 0; x < 4; x++)
            row += wx[x] * texelFetch(t, clamp(ivec2(i) + ivec2(x - 1, y - 1), ivec2(0), last), 0).rgb;
        s += wy[y] * row;
    }
    return s;
}
float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
vec3 veil(vec2 uv) { return bspline(VeilAcc, uv); }
void main() {
    ivec2 size = textureSize(InputBuffer, 0);
    vec2 uv = gl_FragCoord.xy / vec2(size);
    vec3 c = texelFetch(InputBuffer, ivec2(gl_FragCoord.xy), 0).rgb;
    float s = strength;
#if FILTER >= 1 && FILTER <= 6
    c = w0 * c + bspline(DiffAcc, uv);
#elif FILTER == 7
    // GND: the photo's top darker by up to 3 stops, the transition at param (0 top .. 1 bottom)
    float t = photoOf(uv).y;
    float d = 1.0 - smoothstep(param - 0.15, param + 0.15, t);
    c *= exp2(-3.0 * s * d);
#elif FILTER == 8
    float y = luma(c);
    float mx = max(c.r, max(c.g, c.b));
    float yellow = max(0.0, (c.r + c.g) * 0.5 - c.b) / max(1e-4, mx);
    float band = smoothstep(0.15, 0.55, yellow) * (c.r >= c.g ? 1.0 : smoothstep(0.6, 1.0, c.r / max(1e-4, c.g)));
    float k = 0.8 * s * band;
    c = c + k * (vec3(y) - c);
    c *= vec3(1.0 - 0.06 * s, 1.0, 1.0 + 0.08 * s);
#elif FILTER == 12
    float ir = max(0.0, 0.25 * c.r + 1.2 * c.g - 0.45 * c.b);
    vec3 o;
    if (param < 1.0 / 3.0) o = vec3(ir);
    else if (param < 2.0 / 3.0) o = vec3(0.6 * ir + 0.6 * c.r, 0.75 * ir + 0.1 * c.r, 0.55 * c.b + 0.25 * ir);
    else o = c.bgr;
    c = c + s * (o - c);
#elif FILTER == 11
    vec2 px = gl_FragCoord.xy;
    vec2 centre = vec2(size) * 0.5;
    vec2 dv = px - centre;
    float r = length(dv);
    float a = atan(dv.y, dv.x);
    float seg = 6.28318530718 / segments;
    float m = mod(a, seg);
    float folded = abs(m - seg * 0.5);
    vec2 q = (centre + r * vec2(cos(folded), sin(folded))) / vec2(size);
    c = c + s * (bl(InputBuffer, q) - c);
#else
#if FILTER == 9
    c += s * bl(Streaks, uv) * tint;
#endif
#if FILTER == 10
    {
        // in the photo's frame: the ghost shifted right and up, on the left part, its colours split sideways
        vec2 ph = photoOf(uv);
        vec2 photoSize = (rot == 1 || rot == 3) ? vec2(size.yx) : vec2(size);
        float off = 0.05 + 0.2 * param;
        float mask = 1.0 - smoothstep(0.35, 0.75, ph.x);
        float aa = 0.35 * s * mask;
        vec3 g;
        vec2 base = ph + vec2(off, -off * 0.35 * photoSize.x / photoSize.y);
        g.r = veil(sensorOf(base + vec2(-0.004, 0.0))).r;
        g.g = veil(sensorOf(base)).g;
        g.b = veil(sensorOf(base + vec2(0.004, 0.0))).b;
        c += aa * vec3(1.15, 0.9, 1.2) * g;
    }
#endif
#endif
    Output = vec4(c, 1.0);
}
