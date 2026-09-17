#version 300 es
// Per-site response profile inside a colour block, reduced to a tile grid.
//
// The 16 photosites of a tetra block are not equal. Measured on a flat sky over
// every block of a frame, this sensor gives R a 12% spread with a dip in the
// central 2x2, green a 13% spread rising towards the centre, and B about 4% -
// crosstalk and microlens geometry, fixed per sub-position and different for
// each colour. The remosaic treats all sites of a block as interchangeable
// samples of one colour, so that profile enters the interpolated green and,
// with the opposite sign for R and B, the colour differences taken against it.
// On flat ground the kernel covers the sub-positions symmetrically and most of
// it cancels; over fine structure the weights go one-sided and it does not,
// which is the colour cast on detail.
//
// A vendor remosaic carries this as a calibration table. Nothing publishes one
// for this sensor, so it is measured from the frame itself: averaged over the
// whole field, scene content cancels and the fixed pattern is what remains.
//
// One texel per (tile, sub-position), holding the sum for each of the four
// quadrants of the 2x2 of blocks. Sample counts are equal across sub-positions
// within a tile by construction, so the normalisation downstream never needs
// them.
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
uniform float blackLevel;
uniform float whiteLevel;
/** Side of the square each tile covers; a multiple of the mosaic period. */
uniform int tileSize;

out vec4 Output;

void main() {
    ivec2 texel = ivec2(gl_FragCoord.xy);
    // The texel grid is the tile grid expanded by the block: one texel per
    // sub-position within the block.
    ivec2 tile = texel / blockSize;
    ivec2 sub = texel % blockSize;

    int period = blockSize * 2;
    // Phase space: the block grid starts at the origin here, which makes the
    // quadrant index a plain division and keeps the loop free of corrections.
    ivec2 origin = tile * tileSize;

    vec4 sums = vec4(0.0);
    float range = max(whiteLevel - blackLevel, 1.0);

    for (int cy = 0; cy < tileSize; cy += period) {
        for (int cx = 0; cx < tileSize; cx += period) {
            ivec2 cell = origin + ivec2(cx, cy);
            // Validity is decided per cell, with the worst-case offset inside
            // it, so a skipped cell is skipped for every sub-position of this
            // tile alike. Equal counts are what let the normalisation below
            // ignore them entirely.
            ivec2 lo = cell - phase;
            ivec2 hi = lo + ivec2(period - 1);
            if (lo.x < 0 || lo.y < 0 || hi.x >= rawWidth || hi.y >= rawHeight) continue;

            for (int q = 0; q < 4; q++) {
                ivec2 quad = ivec2(q % 2, q / 2) * blockSize;
                ivec2 xy = cell + quad + sub - phase;
                float v = (float(texelFetch(RawBuffer, xy, 0).r) - blackLevel) / range;
                v = clamp(v, 0.0, 1.0);
                if (q == 0) sums.x += v;
                else if (q == 1) sums.y += v;
                else if (q == 2) sums.z += v;
                else sums.w += v;
            }
        }
    }

    Output = sums;
}
