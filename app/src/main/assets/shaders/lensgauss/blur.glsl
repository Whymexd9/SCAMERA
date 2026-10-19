precision highp float;
precision highp sampler2D;
// P289 (LensFilter, DiffusionGauss.blur): one axis of a Gaussian of sigma pixels (radius 3 sigma, at most 24; the edge
// mirrored as the reference pads its image, numpy 'reflect'). The y pass
// (accU 1) adds weight x the blur to the level's running sum (Acc; hasAccU 0: the first Gaussian of the level).
uniform sampler2D InputBuffer;
uniform sampler2D Acc;
uniform vec2 dir;
uniform float sigma;
uniform vec3 weight;
uniform int accU;
uniform int hasAccU;
out vec4 Output;

int mirror(int i, int n) {
    if (n == 1) return 0;
    int period = 2 * (n - 1);
    i = i % period;
    if (i < 0) i += period;
    return i < n ? i : period - i;
}

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    ivec2 last = textureSize(InputBuffer, 0) - ivec2(1);
    int r = int(ceil(3.0 * sigma));
    vec3 s = vec3(0.0);
    float ws = 0.0;
    for (int i = -24; i <= 24; i++) {
        if (i < -r || i > r) continue;
        float w = sigma < 1e-3 ? (i == 0 ? 1.0 : 0.0) : exp(-0.5 * float(i * i) / (sigma * sigma));
        ivec2 q = xy + ivec2(dir) * i;
        s += w * texelFetch(InputBuffer, ivec2(mirror(q.x, last.x + 1), mirror(q.y, last.y + 1)), 0).rgb;
        ws += w;
    }
    s /= ws;
    if (accU == 1) {
        vec3 a = hasAccU == 1 ? texelFetch(Acc, xy, 0).rgb : vec3(0.0);
        Output = vec4(a + weight * s, 1.0);
    } else {
        Output = vec4(s, 1.0);
    }
}
