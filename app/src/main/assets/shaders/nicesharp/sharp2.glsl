precision highp float;
precision highp sampler2D;
// SCAM HDR finishing, pass 2: edge-directed anti-aliasing of the sharpened image. On coherent edges the colour is
// averaged along the edge only, so a stair-stepped or zipper-patterned edge becomes the smooth line it was while the
// profile across it keeps its steepness; then a triangular dither of the 8-bit output.
uniform sampler2D InputBuffer;
uniform float aaStrength;   // 0..1 share of the along-edge average on strong coherent edges
uniform vec4 aaGate;        // coherence from/to, gradient from/to (display units per pixel) between which the average fades in
uniform float alongSigma;   // along-edge Gaussian sigma in pixels
uniform float ditherAmp;    // dither amplitude in display units
out vec4 Output;
const vec3 LW = vec3(0.2126, 0.7152, 0.0722);
float lum(ivec2 p, ivec2 hi) { return dot(texelFetch(InputBuffer, clamp(p, ivec2(0), hi), 0).rgb, LW); }
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    ivec2 sz = textureSize(InputBuffer, 0), hi = sz - ivec2(1);
    vec3 c = texelFetch(InputBuffer, p, 0).rgb;
    float Y[25];
    for (int j = -2; j <= 2; j++) for (int i = -2; i <= 2; i++) Y[(j + 2) * 5 + i + 2] = lum(p + ivec2(i, j), hi);
    float jxx = 0.0, jyy = 0.0, jxy = 0.0;
    for (int j = -1; j <= 1; j++) for (int i = -1; i <= 1; i++) {
        int k = (j + 2) * 5 + i + 2;
        float gx = (Y[k - 5 + 1] + 2.0 * Y[k + 1] + Y[k + 5 + 1]) - (Y[k - 5 - 1] + 2.0 * Y[k - 1] + Y[k + 5 - 1]);
        float gy = (Y[k + 5 - 1] + 2.0 * Y[k + 5] + Y[k + 5 + 1]) - (Y[k - 5 - 1] + 2.0 * Y[k - 5] + Y[k - 5 + 1]);
        float w = (i == 0 ? 2.0 : 1.0) * (j == 0 ? 2.0 : 1.0);
        jxx += w * gx * gx; jyy += w * gy * gy; jxy += w * gx * gy;
    }
    float tr = jxx + jyy;
    float mag = sqrt(tr / 16.0) / 4.0;
    float coh = tr > 1.0e-9 ? sqrt((jxx - jyy) * (jxx - jyy) + 4.0 * jxy * jxy) / tr : 0.0;
    vec3 outc = c;
    float aa = aaStrength * smoothstep(aaGate.x, aaGate.y, coh) * smoothstep(aaGate.z, aaGate.w, mag);
    if (aa > 0.0) {
        float t = 0.5 * atan(2.0 * jxy, jxx - jyy);
        vec2 tang = vec2(-sin(t), cos(t));
        vec3 acc = c; float ws = 1.0;
        for (int k = 1; k <= 3; k++) {
            float w = exp(-0.5 * float(k * k) * 0.7225 / (alongSigma * alongSigma));
            vec2 d = tang * float(k) * 0.85;
            acc += w * (texture(InputBuffer, (gl_FragCoord.xy + d) / vec2(sz)).rgb + texture(InputBuffer, (gl_FragCoord.xy - d) / vec2(sz)).rgb);
            ws += 2.0 * w;
        }
        outc = mix(c, acc / ws, aa);
    }
    vec2 fc = gl_FragCoord.xy;
    float n1 = fract(sin(dot(fc, vec2(12.9898, 78.233))) * 43758.5453);
    float n2 = fract(sin(dot(fc, vec2(39.3468, 11.1353))) * 24634.6345);
    outc += vec3((n1 + n2 - 1.0) * ditherAmp);
    Output = vec4(clamp(outc, 0.0, 1.0), 1.0);
}
