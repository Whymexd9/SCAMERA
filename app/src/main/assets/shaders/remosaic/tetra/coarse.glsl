#version 300 es
precision highp float;
precision highp int;
uniform sampler2D RawBuffer;
uniform ivec2 size;
uniform ivec2 phase;
uniform ivec4 quadColors;
out vec4 Output;
void main() {
    ivec2 cell = ivec2(gl_FragCoord.xy);
    vec4 means = vec4(0.0);
    for (int q = 0; q < 4; ++q) {
        float sum = 0.0, count = 0.0;
        ivec2 origin = cell * 8 + ivec2(q % 2, q / 2) * 4 - phase;
        for (int y = 0; y < 4; ++y) for (int x = 0; x < 4; ++x) {
            ivec2 p = origin + ivec2(x,y);
            if (any(lessThan(p,ivec2(0))) || any(greaterThanEqual(p,size))) continue;
            sum += texelFetch(RawBuffer,p,0).r;
            count += 1.0;
        }
        // An empty quadrant can occur in the extra phase cell at the border.
        // Guide sampling clamps each quadrant to its own nonempty cell range.
        int c = quadColors[q];
        means[q] = (count > 0.0 ? sum/count : 0.0);
    }
    Output = means;
}
