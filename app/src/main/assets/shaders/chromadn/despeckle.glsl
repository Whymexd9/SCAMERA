precision highp float;
precision highp sampler2D;
// Isolated dark or bright dots (defective or half-dead quad groups that stay put through the
// whole burst, so the merge keeps them): a pixel whose luminance is far from the ring of
// pixels four away, while that ring is itself homogeneous, is scaled back to the ring level.
// Edges, text and texture fail the homogeneity test and are left alone.
uniform sampler2D InputBuffer;
out vec4 Output;
float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    ivec2 last = textureSize(InputBuffer, 0) - ivec2(1);
    vec3 c = max(texelFetch(InputBuffer, p, 0).rgb, vec3(0.0));
    float y = luma(c);
    // Ring at distance four: a dot up to three pixels wide (a dead quad group) leaves it untouched.
    const ivec2 ring[16] = ivec2[16](
        ivec2(4, 0), ivec2(-4, 0), ivec2(0, 4), ivec2(0, -4),
        ivec2(4, 4), ivec2(-4, 4), ivec2(4, -4), ivec2(-4, -4),
        ivec2(4, 2), ivec2(4, -2), ivec2(-4, 2), ivec2(-4, -2),
        ivec2(2, 4), ivec2(-2, 4), ivec2(2, -4), ivec2(-2, -4));
    float sum = 0.0, lo = 1.0e9, hi = 0.0;
    for (int k = 0; k < 16; k++) {
        float v = luma(max(texelFetch(InputBuffer, clamp(p + ring[k], ivec2(0), last), 0).rgb, vec3(0.0)));
        sum += v;
        lo = min(lo, v);
        hi = max(hi, v);
    }
    float mean = sum * (1.0 / 16.0);
    bool homog = mean > 1.0e-4 && hi < 1.6 * mean && lo > 0.55 * mean;
    bool speck = y < 0.78 * mean || y > 1.35 * mean;
    if (homog && speck) c *= clamp(mean / max(y, 1.0e-6), 0.3, 3.0);
    Output = vec4(c, 1.0);
}
