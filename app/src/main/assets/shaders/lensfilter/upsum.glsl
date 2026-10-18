precision highp float;
precision highp sampler2D;
// P228 / owner, 2026-10-18 (Pro-Mist's glow had square highlights): one step of the glow / veil sum from the coarse levels up -
// this level times its weight plus the coarser sum (half this size) upsampled with a cubic B-spline. A bilinear stretch of the
// 1/32 and 1/64 levels drew a point source's glow as a diamond with creases; the B-spline is smooth (C2) and round.
uniform sampler2D Level;    // this pyramid level (the output's size)
uniform sampler2D Small;    // the sum of the coarser levels (half this size)
uniform vec3 weight;        // per channel (the diffusion's warm / cool halo)
uniform int hasSmall;
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
    ivec2 size = textureSize(Level, 0);
    vec3 c = weight * texelFetch(Level, xy, 0).rgb;
    if (hasSmall == 1) c += bspline(Small, (vec2(xy) + 0.5) / vec2(size));
    Output = vec4(c, 1.0);
}
