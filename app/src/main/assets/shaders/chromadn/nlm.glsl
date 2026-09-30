precision highp float;
precision highp sampler2D;
precision highp usampler2D;
// Post-network luma noise, step 2: non-local means on u (3x3 patches, 5x5 candidates spaced
// two pixels apart). u = sqrt(Y + c) is roughly perceptual, so one absolute strength h gives
// the same visible result for every noise level (the target is the grain level of a
// GCam/stock render, not the removal of all noise).
uniform sampler2D InputBuffer;
uniform float h;        // filtering strength in u units (about 0.002)
uniform usampler2D EffMap;  // effective merged frames per pixel (1/8 frame steps, 0 = unknown)
uniform float effRef;       // median of the map
uniform int useEff;
out float Output;
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    ivec2 last = textureSize(InputBuffer, 0) - ivec2(1);
    float centre[9];
    for (int k = 0; k < 9; k++)
        centre[k] = texelFetch(InputBuffer, clamp(p + ivec2(k % 3 - 1, k / 3 - 1), ivec2(0), last), 0).r;
    float sum = 0.0, mass = 0.0, best = 0.0;
    // The noise left by the merge follows the number of frames merged at each pixel (rejected areas
    // are noisier, well merged ones cleaner): the filtering strength follows it.
    float hp = h;
    if (useEff != 0) {
        uint v = texelFetch(EffMap, p, 0).r;
        if (v > 0u) hp = h * clamp(sqrt(effRef / (float(v) * 0.125)), 0.6, 2.0);
    }
    float norm = 1.0 / (hp * hp);
    for (int j = -2; j <= 2; j++) {
        for (int i = -2; i <= 2; i++) {
            if (i == 0 && j == 0) continue;
            ivec2 q = p + ivec2(i, j) * 2;
            float d = 0.0;
            for (int k = 0; k < 9; k++) {
                float v = texelFetch(InputBuffer, clamp(q + ivec2(k % 3 - 1, k / 3 - 1), ivec2(0), last), 0).r;
                float e = v - centre[k];
                d += e * e;
            }
            d *= (1.0 / 9.0);
            float w = exp(-d * norm);
            sum += w * texelFetch(InputBuffer, clamp(q, ivec2(0), last), 0).r;
            mass += w;
            best = max(best, w);
        }
    }
    float own = centre[4];
    sum += best * own;
    mass += best;
    Output = mass > 0.0 ? sum / mass : own;
}
