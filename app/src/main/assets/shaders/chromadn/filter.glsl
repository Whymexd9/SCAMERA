precision highp float;
precision highp sampler2D;
// Colour noise removal, step 2 (runs three times with step 1, 2, 4): 5x5 dilated
// bilateral filter of the colour ratios q=c/mean(c) at half resolution. A neighbour counts
// when its ratios differ by no more than the colour noise expected at that brightness and
// its luminance is close; the pixel's own luminance is kept.
uniform sampler2D InputBuffer;
uniform int step;
uniform float strength;   // 0..1 blend towards the filtered colour
uniform float tolerance;  // colour-noise scale (ratio units at mean level 0.02)
out vec4 Output;
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    ivec2 last = textureSize(InputBuffer, 0) - ivec2(1);
    vec3 c0 = max(texelFetch(InputBuffer, p, 0).rgb, vec3(0.0));
    float y0 = max(dot(c0, vec3(1.0 / 3.0)), 1.0e-6);
    vec3 q0 = c0 / y0;
    vec3 sum = vec3(0.0);
    float mass = 0.0;
    for (int j = -2; j <= 2; j++) {
        for (int i = -2; i <= 2; i++) {
            vec3 c = max(texelFetch(InputBuffer, clamp(p + ivec2(i, j) * step, ivec2(0), last), 0).rgb, vec3(0.0));
            float y = max(dot(c, vec3(1.0 / 3.0)), 1.0e-6);
            vec3 q = c / y;
            vec3 d = q - q0;
            float sigma = clamp(tolerance * sqrt(0.02 / y), 0.03, 0.5);
            float dl = log2(y / y0);
            float w = exp(-float(i * i + j * j) * 0.22 - dot(d, d) / (2.0 * sigma * sigma) - dl * dl / 0.72);
            sum += q * w;
            mass += w;
        }
    }
    vec3 q = mix(q0, sum / max(mass, 1.0e-8), strength);
    Output = vec4(y0 * q, 1.0);
}
