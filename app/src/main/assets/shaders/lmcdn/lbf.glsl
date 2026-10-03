precision highp float;
precision highp int;
precision highp sampler2D;
// LMC luma noise reduction, one pass of one pyramid level (GCam 9.6 LumaDenoise F16 / v11 luma_denoise.cl).
// BilateralFilter3x3 at stride s: every neighbour of the 3x3 (at s) is compared with the centre through the
// weighted mean absolute difference of their 3x3 patches; it is taken with its 1-2-1 weight when that distance is
// below sigma (strict) or below 2 sigma (soft); the strict mean is used when the strict weights (centre 4 included)
// exceed the outlier threshold (outlier * 16), else the soft mean.
// sigma = sqrt(f * (noise.x * max(Y, 0) + noise.y)), noise = k * G * (rho_s s_Y, rho_r r_Y), k = strength^2 / s,
// G = the measured difference variance of this level and stride over the single-frame model; Y = the input
// luminance on level 0, else the filtered image's own centre; f = strength-map multiplier (effective frames).
// Stage 1 (the stride-1 pass) also applies the revert: out = d1 + rf' (recon - d1), rf' = rf scaled by GCam's
// kVarMultLUT {8: 1/sqrt8, 4: 1/2, 2: 1/sqrt2} of f; F16 GCam does not clamp rf (> 1 adds back the removed part
// amplified), the host clamps it to dn_revert_max.
uniform sampler2D InputBuffer;  // image to filter (.r): recon (stride-2 pass) or d0 (stride-1 pass)
uniform sampler2D Recon;        // recon of this level (.r): revert reference (stage 1)
uniform sampler2D Yin;          // level-0 input luminance (.r) for the noise level (useYinU = 1)
uniform sampler2D Base;         // Y of this level (.r): modeU 1 outputs den - Base
uniform sampler2D LowFreq;      // level-1 low pass in .g (modeU 2 adds its Up2)
uniform sampler2D Chroma;       // level-0 YUV: U, V passed through (modeU 2)
uniform sampler2D StrMap;       // strength-map variance multiplier f (useMapU = 1)
uniform int strideU;            // 2 or 1 (unset: 1)
uniform int filterU;            // 1: filter; 0: d = input (strength 0)
uniform vec2 noiseU;
uniform float thrU;             // outlier threshold * 16
uniform int stageU;             // 0: filter only; 1: filter + revert
uniform float rfU;              // revert factor of this level
uniform int modeU;              // 0: den; 1: den - Base; 2: level-0 output (den + Up2(LowFreq.g), U, V)
uniform int useYinU;
uniform int useMapU;
uniform vec2 mapInvU;           // 1 / size of this level (strength-map coordinates)
out vec4 Output;

float up2(sampler2D t, ivec2 p) {
    ivec2 b = p >> 1, o = ((p & 1) << 1) - 1;
    ivec2 n = clamp(b + o, ivec2(0), textureSize(t, 0) - 1);
    b = min(b, textureSize(t, 0) - 1);
    vec4 a = vec4(texelFetch(t, b, 0).g, texelFetch(t, ivec2(n.x, b.y), 0).g,
                  texelFetch(t, ivec2(b.x, n.y), 0).g, texelFetch(t, n, 0).g);
    return dot(a, vec4(0.5625, 0.1875, 0.1875, 0.0625));
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    int s = strideU > 0 ? strideU : 1;
    ivec2 last = textureSize(InputBuffer, 0) - 1;
    float f = useMapU != 0 ? max(texture(StrMap, (vec2(p) + 0.5) * mapInvU).r, 0.0) : 1.0;
    float c = texelFetch(InputBuffer, p, 0).r;
    float d = c;
    if (filterU != 0) {
        float P[25];
        for (int j = 0; j < 5; j++)
            for (int i = 0; i < 5; i++)
                P[j * 5 + i] = texelFetch(InputBuffer, clamp(p + ivec2(i - 2, j - 2) * s, ivec2(0), last), 0).r;
        float y = useYinU != 0 ? texelFetch(Yin, p, 0).r : c;
        float sigma = sqrt(max(f * (noiseU.x * max(y, 0.0) + noiseU.y), 0.0));
        vec2 sw = vec2(4.0), sum = vec2(4.0 * c);
        for (int ry = -1; ry <= 1; ry++) {
            for (int rx = -1; rx <= 1; rx++) {
                if (rx == 0 && ry == 0) continue;
                float D = 0.0;
                for (int py = -1; py <= 1; py++) {
                    for (int px = -1; px <= 1; px++) {
                        float w = (py == 0 ? 0.5 : 0.25) * (px == 0 ? 0.5 : 0.25);
                        D += w * abs(P[(py + ry + 2) * 5 + px + rx + 2] - P[(py + 2) * 5 + px + 2]);
                    }
                }
                float ew = (rx == 0 || ry == 0) ? 2.0 : 1.0;
                vec2 wgt = vec2(D <= sigma ? ew : 0.0, 0.5 * D <= sigma ? ew : 0.0);
                sw += wgt;
                sum += wgt * P[(ry + 2) * 5 + rx + 2];
            }
        }
        d = sw.x > thrU ? sum.x / sw.x : sum.y / sw.y;
    }
    float den = d;
    if (stageU == 1) {
        float rf = rfU * (f > 8.0 ? 0.35355339 : f > 4.0 ? 0.5 : f > 2.0 ? 0.70710678 : 1.0);
        den = d + rf * (texelFetch(Recon, p, 0).r - d);
    }
    if (modeU == 1) den -= texelFetch(Base, p, 0).r;
    if (modeU == 2) {
        vec3 yuv = texelFetch(Chroma, p, 0).rgb;
        Output = vec4(den + up2(LowFreq, p), yuv.g, yuv.b, 1.0);
        return;
    }
    Output = vec4(den, 0.0, 0.0, 1.0);
}
