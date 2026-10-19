precision highp float;
precision highp sampler2D;
// P286: the reference model's in-emulsion scatter (FilmHalation, linear light, before the halation), one axis per pass:
// per channel (1 - w) G(core) + w Exp(tail), the exponential as the reference's three-Gaussian fit (amplitudes 0.1633,
// 0.6496, 0.1870 at 0.536, 1.5236, 2.7684 x the decay), applied as a separable kernel (its 1D profile on each axis).
uniform sampler2D InputBuffer;
uniform vec2 dir;          // (1, 0) or (0, 1)
uniform vec3 core;         // Gaussian sigma per channel, pixels
uniform vec3 tail;         // exponential decay per channel, pixels
uniform vec3 tailWeight;   // w per channel
out vec4 Output;

float g(float x, float s) {
    s = max(s, 1e-3);
    return exp(-0.5 * x * x / (s * s)) / s;
}

vec3 kernel(float x) {
    vec3 k;
    for (int c = 0; c < 3; c++) {
        float t = 0.1633 * g(x, 0.536 * tail[c]) + 0.6496 * g(x, 1.5236 * tail[c]) + 0.1870 * g(x, 2.7684 * tail[c]);
        k[c] = (1.0 - tailWeight[c]) * g(x, core[c]) + tailWeight[c] * t;
    }
    return k;
}

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    ivec2 last = textureSize(InputBuffer, 0) - ivec2(1);
    float reach = 3.0 * max(max(max(core.r, core.g), core.b), 2.7684 * max(max(tail.r, tail.g), tail.b));
    int r = int(min(ceil(reach), 24.0));
    vec3 sum = vec3(0.0), wsum = vec3(0.0);
    for (int i = -24; i <= 24; i++) {
        if (i < -r || i > r) continue;
        vec3 w = kernel(float(i));
        ivec2 p = clamp(xy + ivec2(dir * float(i)), ivec2(0), last);
        sum += w * texelFetch(InputBuffer, p, 0).rgb;
        wsum += w;
    }
    vec4 e = texelFetch(InputBuffer, xy, 0);
    Output = vec4(sum / max(wsum, vec3(1e-6)), e.a);
}
