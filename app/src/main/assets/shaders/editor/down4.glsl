// P351: 4x4 box mean (the large-scale level of the proxy: means of x, x^2 and dark stay means).
uniform sampler2D uIn;
uniform ivec2 uInSize;
out vec4 o;

void main() {
    ivec2 b = ivec2(gl_FragCoord.xy) * 4;
    vec4 s = vec4(0.0);
    for (int j = 0; j < 4; ++j)
        for (int i = 0; i < 4; ++i) s += texelFetch(uIn, min(b + ivec2(i, j), uInSize - 1), 0);
    o = s / 16.0;
}
