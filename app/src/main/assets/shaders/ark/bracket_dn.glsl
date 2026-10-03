precision highp float;
precision highp sampler2D;
// ARK tone (ArkFusion): bracket_denoise_filter of libfc_suppressor.so [fc_cl_source.cl:1135-1224] on arkLow. Only
// pixels with Y > 0.95 (the Bento short-frame range) take a 5x5 cross-bilateral mean (sigma_s ~1.58, range sigma
// max(0.18 Y, 0.2), plus a colour weight), blended in by smoothstep(0.95, 1.15, Y) * strength. Runs only when the
// data ceiling clip > 1.05; the result feeds the fusion and the colour of ArkCombine.
uniform sampler2D InputBuffer;  // arkLow, linear Rec.709 (G_CLEAN scale)
uniform float strengthU;        // pref bracket_denoise (ArkCam 1.0); unset -> 0 = pass through
out vec4 Output;

float luma709(vec3 c) { return 0.2126 * c.r + 0.7152 * c.g + 0.0722 * c.b; }

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    ivec2 last = textureSize(InputBuffer, 0) - ivec2(1);
    vec3 c0 = texelFetch(InputBuffer, xy, 0).rgb;
    float l0 = luma709(c0);
    if (l0 <= 0.95 || strengthU <= 0.0) {
        Output = vec4(c0, 1.0);
        return;
    }
    float mask = smoothstep(0.95, 1.15, l0);
    float sigmaR = max(0.18 * l0, 0.20);
    float inv2s2 = 1.0 / (2.0 * sigmaR * sigmaR);
    vec3 sum = vec3(0.0);
    float sumW = 0.0;
    for (int dy = -2; dy <= 2; dy++) {
        for (int dx = -2; dx <= 2; dx++) {
            vec3 cn = texelFetch(InputBuffer, clamp(xy + ivec2(dx, dy), ivec2(0), last), 0).rgb;
            float dl = luma709(cn) - l0;
            vec3 dc = cn - c0;
            float w = exp(-float(dx * dx + dy * dy) * 0.20) * exp(-dl * dl * inv2s2) * exp(-dot(dc, dc) * inv2s2 * 0.5);
            sumW += w;
            sum += cn * w;
        }
    }
    vec3 filt = sum / max(sumW, 0.0001);
    Output = vec4(mix(c0, filt, mask * clamp(strengthU, 0.0, 1.0)), 1.0);
}
