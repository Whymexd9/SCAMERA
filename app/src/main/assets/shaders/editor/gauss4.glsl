// P351: one direction of a Gaussian over all four channels (the proxy's guided filter means), texel centres.
uniform sampler2D uIn;
uniform vec2 uTexel;
uniform vec2 uDir;
uniform float uSigma;
uniform int uTaps;
uniform float uStride;
out vec4 o;

void main() {
    vec2 uv = gl_FragCoord.xy * uTexel;
    vec4 s = texture(uIn, uv);
    float w = 1.0, inv = 1.0 / (2.0 * uSigma * uSigma);
    for (int k = 1; k <= uTaps; ++k) {
        float d = float(k) * uStride;
        float g = exp(-d * d * inv);
        s += g * (texture(uIn, uv + uDir * d * uTexel) + texture(uIn, uv - uDir * d * uTexel));
        w += 2.0 * g;
    }
    o = s / w;
}
