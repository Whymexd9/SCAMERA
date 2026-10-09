precision highp float;
precision highp int;
precision highp sampler2D;
// SCAM chroma noise reduction, one pass of one pyramid level (GCam ChromaDenoise / v11 chroma_denoise.cl,
// BilateralFilterChroma3x3): the 3x3 neighbours at stride s, with 1-2-1 x 1-2-1 spatial weights (4 at the centre),
// are compared with the centre through d^2 = dY^2 + (uvs.x dU)^2 + (uvs.y dV)^2 (U, V brought to the noise scale of
// Y); strict: d^2 <= sigma^2, soft: d^2 / 4 <= sigma^2; U, V = strict mean when the strict weights exceed the
// integer outlier threshold, else the soft mean. Y is never changed.
// sigma^2 = f * (noise.x * max(Y, 0) + noise.y): the noise of Y on this level and stride (measured), times k =
// strength^2 / s, times the strength-map multiplier f.
// With useDeltaU the level is reconstructed first: X + (0, Up2(DeltaUV)), DeltaUV = the denoised minus the noisy next
// coarser level; Up2 = bilinear x2 at the coarse position p/2 - 0.25 (GCam Reconstruct) from four integer texel fetches
// (a float coordinate (p + 0.5) / 2 is an fp16 value on Adreno above 2048 px). ScamDenoise runs the reconstruction as a
// pass of its own (filterU 0) and filters its result, so every pixel is reconstructed once.
uniform sampler2D InputBuffer;  // YUV of this level: X (stage 0) or the stride-2 result (stage 1)
uniform sampler2D DeltaUV;      // RG: DenC - X of the next coarser level (useDeltaU = 1)
uniform sampler2D Orig;         // modeU 1: X of this level; modeU 3: the sensor-scale RGB before any filtering
uniform sampler2D StrMap;       // strength-map variance multiplier f (useMapU = 1)
uniform int strideU;            // 2 or 1 (unset: 1)
uniform int filterU;            // 1: filter; 0: pass through (strength 0)
uniform int useDeltaU;
uniform int useMapU;
uniform vec2 noiseU;
uniform vec2 uvsU;              // sqrt(G_Y / G_U), sqrt(G_Y / G_V)
uniform float thrU;             // integer outlier threshold (sum of spatial weights, max 16)
uniform vec2 mapInvU;           // 1 / size of this level
uniform int modeU;              // 0: YUV; 1: (U,V) - Orig.(U,V); 2: RGB (final, sensor grid);
                                // 3 (2x grid): (Y - Y(Orig), UV - keepU * UV(Orig)) for scamdn/final2x
uniform float keepU;            // modeU 3: share of the 2x-only colour kept by final2x (unset 0 = colour from this scale)
uniform int fadeU;              // modeU 2: colour fades to neutral in the darkest pixels (black-level error)
uniform vec2 darkFadeU;         // mean RGB where the colour starts to fade / is fully kept
uniform vec2 darkChromaU;       // fadeU: colour deviation |RGB - mean| from which a dark colour starts to stay / stays
                                // fully (signed hybrid input; 0 = off, the fade of the luminance alone)
uniform vec2 darkNoiseU;        // darkChromaU on: the keep floor follows the colour noise left after the denoise,
                                // floor^2 = x * mean + y (ScamDenoise.darkNoise; 0 = the fixed floor alone)
out vec4 Output;

const vec3 kY = vec3(0.2126, 0.7152, 0.0721996);
const vec3 kU = vec3(-0.162450244, -0.546494309, 0.708944715);
const vec3 kV = vec3(0.999996748, -0.908302439, -0.091693333);
vec3 toRgb(vec3 c) {
    return vec3(dot(c, vec3(0.999999632, -0.000000001, 0.787402639)),
                dot(c, vec3(1.00000065, -0.132114184, -0.234062881)),
                dot(c, vec3(1.000000187, 1.308706208, -0.000000397)));
}
vec2 up2uv(ivec2 p) {           // bilinear x2 at p/2 - 0.25: weights 9/16, 3/16, 3/16, 1/16 (even p: -1 side, odd: +1)
    ivec2 lim = textureSize(DeltaUV, 0) - 1;
    ivec2 b = p >> 1, o = ((p & 1) << 1) - 1;
    ivec2 n = clamp(b + o, ivec2(0), lim);
    b = min(b, lim);
    return 0.5625 * texelFetch(DeltaUV, b, 0).rg + 0.1875 * texelFetch(DeltaUV, ivec2(n.x, b.y), 0).rg
         + 0.1875 * texelFetch(DeltaUV, ivec2(b.x, n.y), 0).rg + 0.0625 * texelFetch(DeltaUV, n, 0).rg;
}
vec3 fetchLevel(ivec2 t) {
    vec3 x = texelFetch(InputBuffer, t, 0).rgb;
    if (useDeltaU != 0) x.yz += up2uv(t);
    return x;
}
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    int s = strideU > 0 ? strideU : 1;
    ivec2 last = textureSize(InputBuffer, 0) - 1;
    vec3 c = fetchLevel(p);
    vec3 r = c;
    if (filterU != 0) {
        float f = useMapU != 0 ? max(texture(StrMap, (vec2(p) + 0.5) * mapInvU).r, 0.0) : 1.0;
        float sigma2 = f * (noiseU.x * max(c.x, 0.0) + noiseU.y);
        vec2 a0 = vec2(0.0), a1 = vec2(0.0);
        float s0 = 0.0, s1 = 0.0;
        for (int j = -1; j <= 1; j++) {
            for (int i = -1; i <= 1; i++) {
                vec3 n = (i == 0 && j == 0) ? c : fetchLevel(clamp(p + ivec2(i, j) * s, ivec2(0), last));
                vec3 dd = n - c;
                vec2 duv = uvsU * dd.yz;
                float d2 = dd.x * dd.x + dot(duv, duv);
                float w = float((2 - abs(i)) * (2 - abs(j)));
                if (d2 <= sigma2) { a0 += w * n.yz; s0 += w; }
                if (0.25 * d2 <= sigma2) { a1 += w * n.yz; s1 += w; }
            }
        }
        r = vec3(c.x, s0 > thrU ? a0 / s0 : a1 / max(s1, 1.0e-6));
    }
    if (modeU == 1) {
        Output = vec4(r.yz - texelFetch(Orig, p, 0).gb, 0.0, 1.0);
    } else if (modeU == 2) {
        vec3 rgb = toRgb(r);
        if (fadeU != 0) {
            // Dark fade: the colour of the darkest pixels fades to neutral (a black-level error shows as a tint there),
            // except a colour clearly stronger than such a tint (a dark teal curtain), which is kept.
            float m = dot(rgb, vec3(1.0 / 3.0));
            float t = smoothstep(darkFadeU.x, darkFadeU.y, m);
            if (darkChromaU.y > 0.0) {
                // the floor rises above the fixed one where the colour noise does (high ISO, few frames): noise-level
                // colour still fades, a colour clearly above it stays
                float lo = max(darkChromaU.x, sqrt(max(darkNoiseU.x * max(m, 0.0) + darkNoiseU.y, 0.0)));
                t = max(t, smoothstep(lo, max(darkChromaU.y, 2.0 * lo), length(rgb - vec3(m))));
            }
            rgb = toRgb(vec3(r.x, r.yz * t));
        }
        Output = vec4(max(rgb, vec3(0.0)), 1.0);
    } else if (modeU == 3) {
        vec3 o = texelFetch(Orig, p, 0).rgb;
        Output = vec4(r.x - dot(o, kY), r.yz - clamp(keepU, 0.0, 1.0) * vec2(dot(o, kU), dot(o, kV)), 1.0);
    } else {
        Output = vec4(r, 1.0);
    }
}
