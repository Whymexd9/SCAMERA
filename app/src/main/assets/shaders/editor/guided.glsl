// P351: guided filter coefficients from the blurred moments: a = var / (var + eps), b = (1 - a) mean; the dark channel passes.
// The output q = mean(a) x + mean(b) is edge-aware: strong edges keep their step (no halos), flat areas get the mean.
uniform sampler2D uIn;
uniform float uEps;
out vec4 o;

void main() {
    vec4 m = texelFetch(uIn, ivec2(gl_FragCoord.xy), 0);
    float var = max(m.g - m.r * m.r, 0.0);
    float a = var / (var + uEps);
    o = vec4(a, m.r - a * m.r, m.b, 1.0);
}
