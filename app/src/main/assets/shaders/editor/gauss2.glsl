// P351: one direction of two Gaussians of log2 Y at once (the sharpening sigma into r, the texture band's into g); uSelA / uSelB
// pick the input channel (develop's alpha in the first pass, r / g in the second).
uniform sampler2D uIn;
uniform vec2 uTexel;
uniform vec2 uDir;
uniform vec4 uSelA, uSelB;
uniform float uSigA, uSigB;
uniform int uTaps;
uniform float uStride;
out vec4 o;

void main() {
    vec2 uv = gl_FragCoord.xy * uTexel;
    vec4 t0 = texture(uIn, uv);
    float a = dot(t0, uSelA), b = dot(t0, uSelB), wa = 1.0, wb = 1.0;
    float ia = 1.0 / (2.0 * uSigA * uSigA), ib = 1.0 / (2.0 * uSigB * uSigB);
    for (int k = 1; k <= uTaps; ++k) {
        float d = float(k) * uStride;
        float ga = exp(-d * d * ia), gb = exp(-d * d * ib);
        vec4 p = texture(uIn, uv + uDir * d * uTexel), n = texture(uIn, uv - uDir * d * uTexel);
        a += ga * (dot(p, uSelA) + dot(n, uSelA));
        b += gb * (dot(p, uSelB) + dot(n, uSelB));
        wa += 2.0 * ga;
        wb += 2.0 * gb;
    }
    o = vec4(a / wa, b / wb, 0.0, 1.0);
}
