precision highp float;
precision highp sampler2D;
// NICE exposure fusion, step 2: Laplacian pyramid blend, coarse to fine.
// Image detail comes from the Laplacian level, weights from the Gaussian level.
uniform sampler2D gaussLevel;  // (dark, bright, wDark, wBright) at this level
uniform sampler2D lapLevel;    // Laplacian of the same channels
uniform sampler2D upsampled;   // fused result of the coarser level
uniform int useUpsampled;      // 0 at the coarsest level
uniform vec2 invSize;          // 1 / size of this level
uniform float detail;          // local contrast gain for band-pass detail
out vec4 result;
void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    vec4 g = texelFetch(gaussLevel, xy, 0);
    float wd = g.b, wb = g.a, ws = max(wd + wb, 1e-6);
    float fused;
    if (useUpsampled == 1) {
        vec4 l = texelFetch(lapLevel, xy, 0);
        fused = texture(upsampled, gl_FragCoord.xy * invSize).r + detail * (l.r * wd + l.g * wb) / ws;
    } else {
        fused = (g.r * wd + g.g * wb) / ws;
    }
    result = vec4(fused, 0.0, 0.0, 1.0);
}
