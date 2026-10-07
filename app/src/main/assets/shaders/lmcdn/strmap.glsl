precision highp float;
precision highp int;
precision highp sampler2D;
precision highp usampler2D;
// LMC noise reduction, strength map "frames" (the counterpart of GCam's q8 denoise strength map): the noise
// variance multiplier f = clamp(sqrt(E_ref / E(p)), 1, effMax)^2 from the worker's effective-frame map (code 0 =
// unknown or clip-flagged: f = 1), averaged over factor x factor output pixels. One-sided (W3.5): only pixels merged
// from fewer than the median frames get a stronger filter; at or above the median f = 1 exactly (the strength of the
// uniform map). Same clamp as the NLM route (bento_denoise_max limits the noise boost of Bento regions merged from one
// gained frame).
uniform usampler2D EffMap;      // effective-frame codes on the output grid
uniform int factorU;            // output pixels per map texel (unset: 2)
uniform float effRefU;          // median code
uniform float effMaxU;          // sigma clamp (unset: 3)
out float Output;
void main() {
    int F = factorU > 0 ? factorU : 2;
    float ref = effRefU > 0.0 ? effRefU : 64.0;
    float hi = effMaxU > 0.0 ? effMaxU : 3.0;
    ivec2 o = ivec2(gl_FragCoord.xy) * F;
    ivec2 last = textureSize(EffMap, 0) - 1;
    float s = 0.0;
    for (int j = 0; j < F; j++) {
        for (int i = 0; i < F; i++) {
            uint v = texelFetch(EffMap, min(o + ivec2(i, j), last), 0).r;
            // codes at or above the median skip the division: 1.0 exactly also where a GPU divides through an approximate
            // reciprocal (ref / ref slightly above 1 would strengthen the median pixels)
            float r = (v > 0u && float(v) < ref) ? clamp(sqrt(ref / float(v)), 1.0, hi) : 1.0;
            s += r * r;
        }
    }
    Output = s / float(F * F);
}
