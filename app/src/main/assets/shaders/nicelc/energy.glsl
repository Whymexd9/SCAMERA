precision highp float;
precision highp sampler2D;
// Local amplitude of a band: root of the Gaussian-weighted mean square of a Laplacian level
// (5x5 at the level's own resolution, so the window grows with the scale of the band).
uniform sampler2D InputBuffer;
out float Output;
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    ivec2 last = textureSize(InputBuffer, 0) - ivec2(1);
    float sum = 0.0, mass = 0.0;
    for (int j = -2; j <= 2; j++) {
        for (int i = -2; i <= 2; i++) {
            float v = texelFetch(InputBuffer, clamp(p + ivec2(i, j), ivec2(0), last), 0).r;
            float w = exp(-float(i * i + j * j) * 0.25);
            sum += w * v * v;
            mass += w;
        }
    }
    Output = sqrt(sum / mass);
}
