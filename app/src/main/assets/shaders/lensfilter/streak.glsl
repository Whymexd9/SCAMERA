precision highp float;
precision highp sampler2D;
// P228: streaks from the highlight plane (half size): taps along each direction with an exponential fall-off -
// Streak one horizontal line (length from its parameter), Glimmerglass a short four-point star.
uniform sampler2D Highlights;
uniform vec2 dirA;
uniform vec2 dirB;      // (0, 0): one direction
uniform float lengthPx; // in highlight-plane pixels
uniform float gain;
out vec4 Output;
vec3 at(vec2 q, ivec2 size) {
    q = clamp(q, vec2(0.0), vec2(size - ivec2(1)));
    ivec2 i0 = ivec2(floor(q));
    vec2 f = q - vec2(i0);
    ivec2 last = size - ivec2(1);
    vec3 a = texelFetch(Highlights, i0, 0).rgb;
    vec3 b = texelFetch(Highlights, min(i0 + ivec2(1, 0), last), 0).rgb;
    vec3 c = texelFetch(Highlights, min(i0 + ivec2(0, 1), last), 0).rgb;
    vec3 d = texelFetch(Highlights, min(i0 + ivec2(1, 1), last), 0).rgb;
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}
void main() {
    ivec2 size = textureSize(Highlights, 0);
    vec2 p = gl_FragCoord.xy - 0.5;
    const int TAPS = 24;
    float norm = 0.0;
    for (int t = 1; t <= TAPS; t++) norm += 2.0 * exp(-3.0 * float(t) / float(TAPS));
    bool two = dot(dirB, dirB) > 0.0;
    if (two) norm *= 2.0;
    vec3 sum = vec3(0.0);
    for (int t = 1; t <= TAPS; t++) {
        float w = exp(-3.0 * float(t) / float(TAPS)) / norm;
        float off = lengthPx * float(t) / float(TAPS);
        sum += w * (at(p + off * dirA, size) + at(p - off * dirA, size));
        if (two) sum += w * (at(p + off * dirB, size) + at(p - off * dirB, size));
    }
    Output = vec4(sum * gain, 1.0);
}
