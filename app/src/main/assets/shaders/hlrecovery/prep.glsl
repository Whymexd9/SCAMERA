precision highp float;
precision highp sampler2D;
// Highlight recovery, step 1: per block (8x8 sensor pixels), the sum of the colour ratios c/Y
// of the pixels that are trustworthy colour sources (bright enough, not clipped) and their
// weight. rgba = (sum of weight*c/Y, sum of weight), both divided by the block area.
uniform sampler2D InputBuffer; // linear RGB, white balance applied (neutral = equal channels)
uniform float kFull;           // min(R,B)/G of a fully clipped pixel (sensor white through WB)
uniform float yRef;            // luminance of the scene's highlight reference
uniform int block;             // 8 * outputScale output pixels (8 at 1x, 16 on the Sabre 2x grid)
out vec4 Output;
float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
void main() {
    ivec2 origin = ivec2(gl_FragCoord.xy) * block;
    ivec2 last = textureSize(InputBuffer, 0) - ivec2(1);
    vec3 sumQ = vec3(0.0);
    float sumW = 0.0;
    for (int j = 0; j < block; j++) {
        for (int i = 0; i < block; i++) {
            vec3 c = max(texelFetch(InputBuffer, min(origin + ivec2(i, j), last), 0).rgb, vec3(0.0));
            float y = luma(c);
            float yn = y / max(yRef, 1.0e-6);
            float tint = min(c.r, c.b) / max(c.g, 1.0e-6);
            float clipped = smoothstep(1.0 + 0.45 * (kFull - 1.0), 1.0 + 0.8 * (kFull - 1.0), tint);
            float w = (1.0 - clipped) * smoothstep(0.03, 0.10, yn) * (1.0 - smoothstep(0.85, 1.0, yn));
            sumQ += w * c / max(y, 1.0e-6);
            sumW += w;
        }
    }
    float area = float(block * block);
    Output = vec4(sumQ / area, sumW / area);
}
