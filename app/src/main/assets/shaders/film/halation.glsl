precision highp float;
precision highp sampler2D;
// P281: the film simulation's halation (FilmHalation) - the halo sum (the image's pyramid around the film's halation size,
// lensfilter/upsum) brought up to the image with a cubic B-spline and added per channel, energy kept: (E + a H) / (1 + a).
uniform sampler2D InputBuffer;
uniform sampler2D Halo;
uniform vec3 amount;
out vec4 Output;

vec3 bspline(sampler2D t, vec2 uv) {
    ivec2 size = textureSize(t, 0);
    vec2 q = uv * vec2(size) - 0.5;
    vec2 i = floor(q);
    vec2 f = q - i;
    vec2 f2 = f * f, f3 = f2 * f;
    vec2 w0 = (1.0 - 3.0 * f + 3.0 * f2 - f3) / 6.0;
    vec2 w1 = (4.0 - 6.0 * f2 + 3.0 * f3) / 6.0;
    vec2 w2 = (1.0 + 3.0 * f + 3.0 * f2 - 3.0 * f3) / 6.0;
    vec2 w3 = f3 / 6.0;
    float wx[4] = float[4](w0.x, w1.x, w2.x, w3.x);
    float wy[4] = float[4](w0.y, w1.y, w2.y, w3.y);
    ivec2 last = size - ivec2(1);
    vec3 s = vec3(0.0);
    for (int y = 0; y < 4; y++) {
        vec3 row = vec3(0.0);
        for (int x = 0; x < 4; x++)
            row += wx[x] * texelFetch(t, clamp(ivec2(i) + ivec2(x - 1, y - 1), ivec2(0), last), 0).rgb;
        s += wy[y] * row;
    }
    return s;
}

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    ivec2 size = textureSize(InputBuffer, 0);
    vec4 e = texelFetch(InputBuffer, xy, 0);
    vec3 h = max(bspline(Halo, (vec2(xy) + 0.5) / vec2(size)), vec3(0.0));
    Output = vec4((e.rgb + amount * h) / (1.0 + amount), e.a);
}
