#version 300 es
// Channel means of a quad/tetra mosaic, reduced to one texel per tile.
//
// The remosaic aligns white balance before interpolating and restores it after,
// because interpolating raw channels that sit a stop apart pulls colour across
// the edges of every block. That needs the mean of each channel over the whole
// frame, and a full-frame reduction on the GPU is two passes: this one makes a
// tile grid, the next sums the grid.
//
// Output: r = sum of G, g = sum of B, b = sum of R, a = count of G samples.
// B and R counts are equal to each other and derivable from a, so three
// channels carry the sums and the fourth the count.
precision highp float;
precision highp int;
precision highp usampler2D;

uniform usampler2D RawBuffer;
uniform int rawWidth;
uniform int rawHeight;
/** Samples per colour block: 2 for quad bayer, 4 for tetra squared. */
uniform int blockSize;
/** Mosaic phase, as in the reference implementation's phase_x/phase_y. */
uniform ivec2 phase;
/** CFA quadrant order, 0=R 1=G 2=B, in reading order of the 2x2 of blocks. */
uniform ivec4 quadColors;
uniform float blackLevel;
uniform float whiteLevel;
/** Side of the square each invocation reduces. */
uniform int tileSize;

out vec4 Output;

int colorAt(ivec2 xy) {
    int period = blockSize * 2;
    int ry = (xy.y + phase.y) % period;
    int rx = (xy.x + phase.x) % period;
    int idx = (ry < blockSize)
            ? ((rx < blockSize) ? 0 : 1)
            : ((rx < blockSize) ? 2 : 3);
    return (idx == 0) ? quadColors.x : (idx == 1) ? quadColors.y
         : (idx == 2) ? quadColors.z : quadColors.w;
}

void main() {
    ivec2 tile = ivec2(gl_FragCoord.xy);
    ivec2 base = tile * tileSize;

    float sumG = 0.0, sumB = 0.0, sumR = 0.0, cntG = 0.0;
    float range = max(whiteLevel - blackLevel, 1.0);

    for (int j = 0; j < tileSize; j++) {
        int y = base.y + j;
        if (y >= rawHeight) break;
        for (int i = 0; i < tileSize; i++) {
            int x = base.x + i;
            if (x >= rawWidth) break;
            float v = (float(texelFetch(RawBuffer, ivec2(x, y), 0).r) - blackLevel) / range;
            v = clamp(v, 0.0, 1.0);
            int c = colorAt(ivec2(x, y));
            if (c == 1) { sumG += v; cntG += 1.0; }
            else if (c == 2) { sumB += v; }
            else { sumR += v; }
        }
    }
    Output = vec4(sumG, sumB, sumR, cntG);
}
