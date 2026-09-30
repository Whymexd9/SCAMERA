precision highp float;
precision highp sampler2D;
// Isolated dark or bright dots (defective or half-dead quad groups that stay put through the
// whole burst, so the merge keeps them): a pixel whose luminance is far from the ring of
// pixels four away, while that ring is itself homogeneous, is scaled back to the ring level.
// Edges, text and texture fail the homogeneity test and are left alone.
uniform sampler2D InputBuffer;
uniform float sigma;    // noise sigma of u = sqrt(Y + offsetC), 0 disables the noise-aware test
uniform float offsetC;
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
    // The ring must be flat to within about 20 %: a thin real shadow or line has its own ring
    // samples along the line, which fail this, while a defect leaves the ring untouched.
    bool homog = mean > 1.0e-4 && hi < 1.25 * mean && lo > 0.8 * mean;
    bool speck = y < 0.7 * mean || y > 1.45 * mean;
    if (homog && speck) c *= clamp(mean / max(y, 1.0e-6), 0.3, 3.0);
    else if (sigma > 0.0) {
        // Noisy flat areas (dark sky): the ring test above cannot tell a defect from noise
        // there. In u the noise is uniform, so a dark pixel far below the mean of the ring
        // two pixels away, in a ring that is flat to within the noise, is a defect.
        const ivec2 ring2[16] = ivec2[16](
            ivec2(2, 0), ivec2(-2, 0), ivec2(0, 2), ivec2(0, -2),
            ivec2(2, 2), ivec2(-2, 2), ivec2(2, -2), ivec2(-2, -2),
            ivec2(2, 1), ivec2(2, -1), ivec2(-2, 1), ivec2(-2, -1),
            ivec2(1, 2), ivec2(-1, 2), ivec2(1, -2), ivec2(-1, -2));
        float us = 0.0, ulo = 1.0e9, uhi = 0.0;
        vec3 rgbSum = vec3(0.0);
        for (int k = 0; k < 16; k++) {
            vec3 rc = max(texelFetch(InputBuffer, clamp(p + ring2[k], ivec2(0), last), 0).rgb, vec3(0.0));
            rgbSum += rc;
            float v = sqrt(luma(rc) + offsetC);
            us += v;
            ulo = min(ulo, v);
            uhi = max(uhi, v);
        }
        float um = us * (1.0 / 16.0);
        float uc = sqrt(y + offsetC);
        // A real thin dark line drags its direct neighbours down with it (the lens blurs it over
        // more than a pixel); a defect leaves them at the level of the ring.
        float un4 = 0.25 * (sqrt(luma(max(texelFetch(InputBuffer, clamp(p + ivec2(1, 0), ivec2(0), last), 0).rgb, vec3(0.0))) + offsetC)
                          + sqrt(luma(max(texelFetch(InputBuffer, clamp(p - ivec2(1, 0), ivec2(0), last), 0).rgb, vec3(0.0))) + offsetC)
                          + sqrt(luma(max(texelFetch(InputBuffer, clamp(p + ivec2(0, 1), ivec2(0), last), 0).rgb, vec3(0.0))) + offsetC)
                          + sqrt(luma(max(texelFetch(InputBuffer, clamp(p - ivec2(0, 1), ivec2(0), last), 0).rgb, vec3(0.0))) + offsetC));
        if (uhi - ulo < 7.0 * sigma && uc < um - 3.5 * sigma && un4 > um - 1.5 * sigma) {
            c = rgbSum * (1.0 / 16.0);
        } else if (uc > um + 4.5 * sigma && un4 < um + 1.5 * sigma && uhi - ulo < 9.0 * sigma + 0.5 * (uc - um)) {
            // Bright dot (hot pixel the merge kept): far above a flat ring while its direct neighbours stay
            // at the ring level (a real small highlight is blurred over its neighbours by the lens).
            c = rgbSum * (1.0 / 16.0);
        }
    }
    Output = vec4(c, 1.0);
}
