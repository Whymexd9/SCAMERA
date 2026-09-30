precision highp float;
precision highp sampler2D;
// Post-network luma noise, coarse step 2: what the non-local means leaves at the scale of
// 16-64 px is a cloudy residue of the noise itself (blotches). At quarter resolution an
// edge-stopping smoothing over 13x13 samples (about 50 px) finds the flat level under it; the
// output is the correction (smoothed minus original) that the last step adds back at full
// resolution. Edges and real gradients are not crossed: the range weight is a few noise sigma.
uniform sampler2D InputBuffer;  // quarter-resolution u after the non-local means
uniform float tolerance;        // range scale in u units
out float Output;
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    ivec2 last = textureSize(InputBuffer, 0) - ivec2(1);
    float c = texelFetch(InputBuffer, p, 0).r;
    float sum = 0.0, mass = 0.0;
    float inv = 1.0 / (2.0 * tolerance * tolerance);
    for (int j = -6; j <= 6; j++) {
        for (int i = -6; i <= 6; i++) {
            float v = texelFetch(InputBuffer, clamp(p + ivec2(i, j), ivec2(0), last), 0).r;
            float d = v - c;
            float w = exp(-float(i * i + j * j) * 0.0385 - d * d * inv);
            sum += w * v;
            mass += w;
        }
    }
    Output = sum / mass - c;
}
