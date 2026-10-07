precision highp float;
precision highp sampler2D;
// Clip-band chroma (VivoNiceRgb, hybrid shots with the worker's clip flags): along the border of a clipped area the
// merge gives a colour either from the clipped mean (flags bits 0-2: every nearby sample of that colour clipped) or from
// the few unclipped samples left (bit 3: low, they sit on the darker side). Where a slanted edge crosses the Bayer rows
// the two alternate every few pixels: an orange/blue dashed line along bright window frames and sills, already in a
// single RAW frame. ArkCam has none (the Bento frame covers the band). On a pixel whose colour came out of the clip
// handling (own flag bits 0-3, a colour that is unreliable anyway) or next to one, the colour becomes that of a mix of
// the window's dark side (its darkest pixels, at their level) and the window's mean colour (the light) for the rest of
// the pixel's own luminance: on the bright side that is the window's mean colour (the alternation averages out), on
// the dark side of a sharp clip edge the dark surface's colour - only where that mean is near-neutral (a white
// highlight: a coloured light keeps its colour detail).
// The dark side: pixels next to the clip edge whose own colour is censored (bit 3) keep a red / blue dot pattern (each
// colour from a different side of the edge, the worker's clip-border pass cannot rebuild every one): the dotted
// dark-red line between a lamp's dark end cap and its clipped diffuser (vivo X200 Ultra 2026-10-07,
// tools/check_highlight_recovery.py).
// Those take the mixed colour at any luminance; other band pixels only on the bright side (luminance above ~a quarter
// of the window's brightest), and every pixel away from the flagged band stays as it is.
uniform sampler2D InputBuffer;      // white-balanced linear RGB after nicergb
uniform sampler2D ClipFlags;        // worker flags, normalised R8 (code = r * 255)
uniform int radiusU;                // window radius in output pixels (3 at 1x, 6 on the Sabre 2x grid); <= 0 -> 3
uniform int zoneU;                  // how far the band reaches past the flagged pixels, output pixels; <= 0 -> 2
uniform float strengthU;            // 0..1; <= 0 -> pass-through
out vec4 Output;

uint flagAt(ivec2 p) { return uint(texelFetch(ClipFlags, p, 0).r * 255.0 + 0.5); }

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    vec4 c = texelFetch(InputBuffer, xy, 0);
    if (strengthU <= 0.0) { Output = c; return; }
    ivec2 last = textureSize(InputBuffer, 0) - ivec2(1);
    int r = radiusU > 0 ? radiusU : 3;
    uint own = flagAt(xy);
    // the band: a flagged pixel or one within 2 px (output grid: zoneU) of a flagged one
    int z = zoneU > 0 ? zoneU : 2;
    bool zone = (own & 15u) != 0u;
    for (int dy = -z; dy <= z && !zone; dy++)
        for (int dx = -z; dx <= z && !zone; dx++)
            zone = (flagAt(clamp(xy + ivec2(dx, dy), ivec2(0), last)) & 15u) != 0u;
    if (!zone) { Output = c; return; }
    vec3 sum = vec3(0.0);
    float n = 0.0, top = 0.0, low = 1.0e30;
    const vec3 W = vec3(0.2126, 0.7152, 0.0722);
    for (int dy = -r; dy <= r; dy++) {
        for (int dx = -r; dx <= r; dx++) {
            ivec2 q = clamp(xy + ivec2(dx, dy), ivec2(0), last);
            vec3 v = max(texelFetch(InputBuffer, q, 0).rgb, vec3(0.0));
            float lv = dot(v, W);
            sum += v;
            n += 1.0;
            top = max(top, lv);
            low = min(low, lv);
        }
    }
    vec3 mean = sum / n;
    float lm = dot(mean, W);
    vec3 rgb = max(c.rgb, vec3(0.0));
    float l = dot(rgb, W);
    if (lm <= 1.0e-6 || top <= 1.0e-6) { Output = c; return; }
    // Only near-neutral bands: the dashes alternate around a white highlight, while a coloured light (a blue screen with
    // red lines, a neon) has a coloured mean and keeps its own colour detail.
    float meanSat = (max(max(mean.r, mean.g), mean.b) - min(min(mean.r, mean.g), mean.b)) / max(max(max(mean.r, mean.g), mean.b), 1.0e-6);
    float bright = smoothstep(0.15, 0.4, l / top);
    float side = (own & 8u) != 0u ? 1.0 : bright;
    float w = clamp(strengthU, 0.0, 1.0) * side * (1.0 - smoothstep(0.2, 0.4, meanSat));
    if (w <= 0.0) { Output = c; return; }
    // The dark side's colour: the mean of the window pixels within 2x of its darkest luminance.
    vec3 dk = vec3(0.0);
    float nd = 0.0;
    for (int dy = -r; dy <= r; dy++) {
        for (int dx = -r; dx <= r; dx++) {
            vec3 v = max(texelFetch(InputBuffer, clamp(xy + ivec2(dx, dy), ivec2(0), last), 0).rgb, vec3(0.0));
            if (dot(v, W) <= 2.0 * low + 1.0e-6) { dk += v; nd += 1.0; }
        }
    }
    dk /= max(nd, 1.0);
    float ld = min(dot(dk, W), l);
    vec3 mixed = dk * (ld / max(dot(dk, W), 1.0e-6)) + mean * ((l - ld) / lm);
    vec3 target = mix(mixed, mean * (l / lm), bright);     // the bright side: the window's mean colour, as before
    Output = vec4(mix(rgb, target, w), c.a);
}
