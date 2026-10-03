precision highp float;
precision highp sampler2D;
// SCAM HDR finishing, pass 1 on the display-encoded RGB: chroma anti-aliasing (colour averaged over the neighbours of
// similar luminance) and a luminance unsharp mask whose result is held inside the local range of the pixel's
// neighbourhood (no halo or ring, a thin real line keeps its contrast). Pass 2 (sharp2.glsl) smooths the edges along
// their direction.
uniform sampler2D InputBuffer;
uniform float amount;       // unsharp mask gain on the luminance
uniform float radius;       // unsharp mask Gaussian sigma in pixels
uniform float overshoot;    // allowed excursion beyond the local min/max (display units)
uniform vec2 chromaAA;      // share of the chroma anti-aliasing, luminance tolerance
uniform vec2 coring;        // detail amplitude (display units) below which the mask does not amplify / above which it is full
uniform int pxStepU;           // outputScale: every window tap is 'pxStep' output pixels = one sensor pixel apart (1 at 1x)
out vec4 Output;
const vec3 LW = vec3(0.2126, 0.7152, 0.0722);
float lum(ivec2 p, ivec2 hi) { return dot(texelFetch(InputBuffer, clamp(p, ivec2(0), hi), 0).rgb, LW); }
void main() {
    int pxStep = max(pxStepU, 1); // unset uniform (0) = 1x behaviour
    ivec2 p = ivec2(gl_FragCoord.xy);
    ivec2 sz = textureSize(InputBuffer, 0), hi = sz - ivec2(1);
    vec3 c = texelFetch(InputBuffer, p, 0).rgb;
    if (chromaAA.x > 0.0) {
        // Colour that changes where the luminance does not (the Bayer sampling pattern of fine text, fabric, mesh) is
        // flattened; a coloured edge that is also a luminance edge keeps its place.
        float yc = dot(c, LW);
        vec3 cc = c - vec3(yc);
        vec3 acc = vec3(0.0); float ws = 0.0;
        for (int j = -2; j <= 2; j++) for (int i = -2; i <= 2; i++) {
            vec3 n = texelFetch(InputBuffer, clamp(p + ivec2(i, j) * pxStep, ivec2(0), hi), 0).rgb;
            float yn = dot(n, LW);
            vec3 cn = n - vec3(yn);
            float dy = (yn - yc) / chromaAA.y;
            vec3 dd = (cn - cc) / 0.08;
            float w = exp(-0.5 * float(i * i + j * j) / (1.3 * 1.3)) * exp(-dy * dy - dot(dd, dd) * 0.5);
            acc += w * cn; ws += w;
        }
        c = vec3(yc) + mix(cc, acc / max(ws, 1.0e-6), chromaAA.x);
    }
    float Y[25];
    for (int j = -2; j <= 2; j++) for (int i = -2; i <= 2; i++) Y[(j + 2) * 5 + i + 2] = lum(p + ivec2(i, j) * pxStep, hi);
    float y0 = dot(c, LW);
    float blur = 0.0, bw = 0.0;
    for (int j = -2; j <= 2; j++) for (int i = -2; i <= 2; i++) {
        // radius is in output pixels and the taps are pxStep pixels apart, so the squared distance carries pxStep^2.
        float w = exp(-0.5 * float((i * i + j * j) * pxStep * pxStep) / (radius * radius));
        blur += w * Y[(j + 2) * 5 + i + 2]; bw += w;
    }
    blur /= bw;
    float lo = 1.0, hh = 0.0;
    for (int j = -1; j <= 1; j++) for (int i = -1; i <= 1; i++) { float v = Y[(j + 2) * 5 + i + 2]; lo = min(lo, v); hh = max(hh, v); }
    // Weak fine texture (paint weave, fabric, a noise remainder) would only turn into a regular lattice, so the gain
    // fades in with the detail amplitude (measured on the 3x3 neighbourhood to keep real edges at full gain).
    float dm = 0.0;
    for (int j = -1; j <= 1; j++) for (int i = -1; i <= 1; i++) dm = max(dm, abs(Y[(j + 2) * 5 + i + 2] - blur));
    float g = amount * smoothstep(coring.x, coring.y, dm);
    float y1 = clamp(y0 + g * (y0 - blur), lo - overshoot, hh + overshoot);
    c *= (y0 > 1.0e-4 ? max(y1, 0.0) / y0 : 1.0);
    Output = vec4(clamp(c, 0.0, 1.0), 1.0);
}
