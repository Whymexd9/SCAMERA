precision highp float;
precision highp int;
precision highp sampler2D;
precision highp usampler2D;
// SCAM noise reduction, strength map "frames" (the counterpart of the reference q8 denoise strength map): the noise
// variance multiplier f = clamp(sqrt(E_ref / E(p)), 1, effMax)^2 from the worker's effective-frame map (code 0 =
// unknown or clip-flagged: f = 1), averaged over factor x factor output pixels. One-sided (W3.5): only pixels merged
// from fewer than the median frames get a stronger filter; at or above the median f = 1 exactly (the strength of the
// uniform map). Same clamp as the NLM route (bento_denoise_max limits the noise boost of Bento regions merged from one
// gained frame).
// P214d: times the lens-shading term min(g^2, cap), g = the luminance gain of the shading map at this texel (the mean
// of r, (g_even + g_odd) / 2, b: the factor why/low, why/combine and why/sharp_in multiply the image by, AFTER this
// denoise; scamrgb applies the colour part only, normalised to mean 1) over the map's smallest gain (ScamDenoise.lscTerm,
// a 1/512 margin above it for the fp16 storage and the filtering): the noise the WHY tone lifts in the corners (corner
// gains 2.0-3.8 in the phone logs) is filtered harder, after GCam's (1, g, g^2) strength map. One-sided as well: where
// g <= 1 (the optical centre) the factor is 1 exactly. lscU.x = 0 (unset, scam_dev "denoise_lsc 0", no real map,
// the old run of post_ab): no term, the map of before. noEffU = 1: no effective-frame map (EffMap is a 1x1
// placeholder, bound so that no sampler of another type shares its unit): the frame factor is 1.
uniform usampler2D EffMap;      // effective-frame codes on the output grid
uniform int factorU;            // output pixels per map texel (unset: 2)
uniform float effRefU;          // median code
uniform float sharpRefU;        // coverage reference for motion detail; 0 uses the median
uniform float effMaxU;          // sigma clamp (unset: 3)
uniform int noEffU;             // 1: no effective-frame map (unset 0: the map, as before)
uniform float flatU;            // P306: f without a map (the 200 MP shot, ScamDenoise dn_huge_map; unset 0: 1)
uniform sampler2D GainMap;      // lens-shading gains (r, g_even, g_odd, b) as ScamRgb uploaded them (lscU.x > 0)
uniform vec2 lscU;              // x: 1 / reference gain (0: no lens-shading term), y: cap of g^2
uniform vec2 fullInvU;          // 1 / size of the output grid (GainMap coordinates, as scamrgb samples it)
out vec2 Output;
void main() {
    int F = factorU > 0 ? factorU : 2;
    float ref = effRefU > 0.0 ? effRefU : 64.0;
    float sharpRef = sharpRefU > 0.0 ? sharpRefU : ref;
    float hi = effMaxU > 0.0 ? effMaxU : 3.0;
    ivec2 o = ivec2(gl_FragCoord.xy) * F;
    float f = flatU > 0.0 ? flatU : 1.0;
    float rejected = 0.0;
    if (noEffU == 0) {
        ivec2 last = textureSize(EffMap, 0) - 1;
        float s = 0.0;
        for (int j = 0; j < F; j++) {
            for (int i = 0; i < F; i++) {
                uint v = texelFetch(EffMap, min(o + ivec2(i, j), last), 0).r;
                // codes at or above the median skip the division: 1.0 exactly also where a GPU divides through an
                // approximate reciprocal (ref / ref slightly above 1 would strengthen the median pixels)
                float r = (v > 0u && float(v) < ref) ? clamp(sqrt(ref / float(v)), 1.0, hi) : 1.0;
                s += r * r;
                if (v > 0u) rejected += clamp(1.0 - float(v) / sharpRef, 0.0, 1.0);
            }
        }
        f = s / float(F * F);
        rejected /= float(F * F);
    }
    if (lscU.x > 0.0) {
        // the mean pixel position of this texel's block, in scamrgb's coordinates (pixel * inverseSize)
        vec4 sites = texture(GainMap, (vec2(o) + 0.5 * float(F - 1)) * fullInvU);
        float g = dot(vec3(sites.r, 0.5 * (sites.g + sites.b), sites.a), vec3(1.0 / 3.0)) * lscU.x;
        // NaN or g <= 1: 1 (a broken site never weakens or blows up the filter)
        f *= g > 1.0 ? min(g * g, max(lscU.y, 1.0)) : 1.0;
    }
    Output = vec2(f, rejected);
}
