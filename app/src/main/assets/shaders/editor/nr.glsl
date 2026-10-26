// P351 noise reduction: 5x5 bilateral of luma (taps uStepL px apart) and a luma-guided bilateral of chroma (uStepC px apart) in
// an encoded YCbCr (a linear source is encoded with gamma 2.2 for the weights and decoded after). Runs before develop.
uniform sampler2D uSrc;
uniform vec2 uTexel;
uniform float uStepL, uStepC, uSigL, uSigC;
uniform float uLumaOn, uChromaOn;
out vec4 o;
const vec3 YW = vec3(0.299, 0.587, 0.114);

vec3 enc(vec3 c) { return uLinearIn == 1 ? pow(max(c, 0.0), vec3(1.0 / 2.2)) : c; }

void main() {
    vec2 uv = gl_FragCoord.xy * uTexel;
    vec3 e0 = enc(texture(uSrc, uv).rgb);
    float y0 = dot(e0, YW);
    vec2 c0 = vec2(e0.b - y0, e0.r - y0);
    float ys = 0.0, yw = 0.0, cw = 0.0;
    vec2 cs = vec2(0.0);
    float il = 1.0 / (2.0 * uSigL * uSigL), ic = 1.0 / (2.0 * uSigC * uSigC);
    for (int j = -2; j <= 2; ++j) {
        for (int i = -2; i <= 2; ++i) {
            vec2 d = vec2(float(i), float(j));
            float sp = exp(-dot(d, d) / 4.5);
            vec3 eL = enc(texture(uSrc, uv + d * uStepL * uTexel).rgb);
            float yL = dot(eL, YW);
            float wL = sp * exp(-(yL - y0) * (yL - y0) * il);
            ys += wL * yL;
            yw += wL;
            vec3 eC = enc(texture(uSrc, uv + d * uStepC * uTexel).rgb);
            float yC = dot(eC, YW);
            vec2 cC = vec2(eC.b - yC, eC.r - yC);
            vec2 dc = cC - c0;
            float wC = sp * exp(-(yC - y0) * (yC - y0) * 50.0 - dot(dc, dc) * ic);
            cs += wC * cC;
            cw += wC;
        }
    }
    float y = mix(y0, ys / yw, uLumaOn);
    vec2 cc = mix(c0, cs / cw, uChromaOn);
    float r = cc.y + y, b = cc.x + y;
    float g = (y - 0.299 * r - 0.114 * b) / 0.587;
    vec3 e = vec3(r, g, b);
    o = vec4(uLinearIn == 1 ? pow(max(e, 0.0), vec3(2.2)) : e, 1.0);
}
