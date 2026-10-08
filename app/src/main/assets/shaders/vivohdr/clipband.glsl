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
// Fringes (vivo X200 Ultra lamp 2026-10-08): a blue-violet line along the dark end cap and a blue / cyan edge of the
// diffuser, 2-5 px wide, beyond the flagged band. Axial chromatic aberration spreads B (and R) wider than G around a
// light 100-200x brighter than its surroundings; the RAW CA pass re-centres R and B but cannot undo the blur, so the
// merge keeps a B halo (B leads G by ~0.35 px at the lamp, R by 0.1 px). Within the window radius of a flagged pixel, a
// pixel whose colour leaves the edge model - its dark side (by the brightest channel) for the dark share of its
// luminance, the light (the brightest pixels within twice the radius) for the rest - towards blue, violet or cyan (B in
// excess beyond any R excess, or R in deficit) takes the model's colour in proportion to that excess, at its own
// luminance. Red, orange and yellow deviations stay (a real coloured detail next to a light), and so does a pixel close
// to the model (a cool lamp edge; blue sky next to a blown cloud or a blue wall next to a lamp is its own dark side).
// A coloured light (the light's saturation 0.2..0.4) keeps its colour detail. tools/check_highlight_neutral.py (scene B).
// The input is signed on hybrid shots (nicergb signedU): the window statistics clamp each pixel for their own maths, the
// pixel itself keeps its signed value for the share it is not recoloured (w < 1) - for non-negative input as before.
uniform sampler2D InputBuffer;      // white-balanced linear RGB after nicergb
uniform sampler2D ClipFlags;        // worker flags, normalised R8 (code = r * 255)
uniform int radiusU;                // window radius in output pixels (3 at 1x, 6 on the Sabre 2x grid); <= 0 -> 3
uniform int zoneU;                  // how far the band reaches past the flagged pixels, output pixels; <= 0 -> 2
uniform float strengthU;            // 0..1; <= 0 -> pass-through
uniform int clipHiUnflaggedU;       // P58, as in vivohdr/nicergb: inside the Bento mask bit 3 is no clip edge (0 = unset)
out vec4 Output;

uint flagAt(ivec2 p) {
    uint f = uint(texelFetch(ClipFlags, p, 0).r * 255.0 + 0.5);
    // P58: with the ultrashort clipped below the worker's flag threshold, the base frames' clipped samples inside the Bento
    // mask (bit 3 without a clipped mean) are no edge: the band would darken those cells (5 % blotches in a blown sky)
    if (clipHiUnflaggedU != 0 && (f & 16u) != 0u && (f & 7u) == 0u) f &= ~8u;
    return f;
}

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
    // the fringe reach: within the window radius of a flagged pixel (taps every rs px: a flagged area is wider than that)
    bool reach = zone;
    int rs = max(1, (r + 2) / 3);
    for (int dy = -r; dy <= r && !reach; dy += rs)
        for (int dx = -r; dx <= r && !reach; dx += rs)
            reach = (flagAt(clamp(xy + ivec2(dx, dy), ivec2(0), last)) & 15u) != 0u;
    if (!reach) { Output = c; return; }
    vec3 sum = vec3(0.0);
    float n = 0.0, top = 0.0, low = 1.0e30, lowM = 1.0e30;
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
            lowM = min(lowM, max(max(v.r, v.g), v.b));
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
    float w = zone ? clamp(strengthU, 0.0, 1.0) * side * (1.0 - smoothstep(0.2, 0.4, meanSat)) : 0.0;
    // The light of the fringe model: the colour of the brightest pixels within twice the radius (taps every r / 2 px,
    // weighted by luminance^4), i.e. the recovered core. The window mean is no reference there: on a steep edge the
    // fringe itself colours it (B 1.6 x G over the 7 x 7 window next to the lamp) and the band above leaves it alone.
    int r2 = 2 * r, s2 = max(2, r / 2);
    vec3 light = vec3(0.0);
    float top2 = top;
    for (int dy = -r2; dy <= r2; dy += s2) {
        for (int dx = -r2; dx <= r2; dx += s2) {
            vec3 v = max(texelFetch(InputBuffer, clamp(xy + ivec2(dx, dy), ivec2(0), last), 0).rgb, vec3(0.0));
            float lv = dot(v, W);
            top2 = max(top2, lv);
            float lv2 = lv * lv;
            light += (lv2 * lv2) * v;
        }
    }
    float ll = dot(light, W);
    float lightSat = (max(max(light.r, light.g), light.b) - min(min(light.r, light.g), light.b)) / max(max(max(light.r, light.g), light.b), 1.0e-30);
    float wf0 = ll > 0.0 && l > 1.0e-6 ? clamp(strengthU, 0.0, 1.0) * (1.0 - smoothstep(0.2, 0.4, lightSat)) : 0.0;
    if (w <= 0.0 && wf0 <= 0.0) { Output = c; return; }
    // The dark side's colour: the mean of the window pixels within 2x of its darkest luminance. For the fringe model by
    // the brightest channel (a fringe is dark in luminance, B weighs 0.07, but not in B), plus 1 % of the base white so
    // that a dark noisy side averages more than its single darkest pixel.
    vec3 dk = vec3(0.0), dkF = vec3(0.0);
    float nd = 0.0, ndF = 0.0;
    for (int dy = -r; dy <= r; dy++) {
        for (int dx = -r; dx <= r; dx++) {
            vec3 v = max(texelFetch(InputBuffer, clamp(xy + ivec2(dx, dy), ivec2(0), last), 0).rgb, vec3(0.0));
            if (dot(v, W) <= 2.0 * low + 1.0e-6) { dk += v; nd += 1.0; }
            if (max(max(v.r, v.g), v.b) <= 2.0 * lowM + 0.01) { dkF += v; ndF += 1.0; }
        }
    }
    dk /= max(nd, 1.0);
    dkF /= max(ndF, 1.0);
    float ld = min(dot(dk, W), l);
    vec3 mixed = dk * (ld / max(dot(dk, W), 1.0e-6)) + mean * ((l - ld) / lm);
    vec3 target = mix(mixed, mean * (l / lm), bright);     // the bright side: the window's mean colour, as before
    vec3 col = mix(c.rgb, target, w);
    if (wf0 > 0.0) {
        // fringe: the same edge model with the light's colour; the log chromaticity of the pixel against it
        float ldF = min(dot(dkF, W), l);
        float brightL = smoothstep(0.15, 0.4, l / max(top2, 1.0e-6));
        vec3 fmix = dkF * (ldF / max(dot(dkF, W), 1.0e-6)) + light * ((l - ldF) / ll);
        vec3 ft = mix(fmix, light * (l / ll), brightL);
        vec3 lp = log(max(col, vec3(1.0e-6)));
        vec3 lt = log(max(ft, vec3(1.0e-6)));
        vec3 d = (lp - dot(lp, vec3(1.0 / 3.0))) - (lt - dot(lt, vec3(1.0 / 3.0)));
        // B in excess beyond any R excess (blue, violet, purple - not a red detail the halo reaches), or R in deficit
        col = mix(col, ft, wf0 * smoothstep(0.04, 0.15, max(d.b - max(d.r, 0.0), -d.r)));
    }
    Output = vec4(col, c.a);
}
