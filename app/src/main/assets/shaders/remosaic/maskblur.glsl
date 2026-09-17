#version 300 es
// One axis of the mask-normalised blur used to interpolate a sparse channel.
//
// The reference does conv1d(conv1d(channel)) and the same on the mask, then
// divides. Normalising by the blurred mask rather than by the kernel's own
// weight is what makes this work on a mosaic: a pixel's estimate is the mean of
// the samples that actually exist near it, whatever their arrangement, so no
// assumption about the pattern is baked into the filter.
//
// Two channels are carried together: x = masked values, y = the mask itself.
// The division happens at the end of the second pass, not here, because a
// partial sum divided by a partial weight is not the same number.
precision highp float;

uniform sampler2D InputBuffer;
uniform ivec2 size;
/** 0 horizontal, 1 vertical. */
uniform int axis;
/**
 * Width of the flat kernel. 9 covers a tetra block, 5 a quad block, 1 means no
 * convolution at all - the sample itself, if it exists.
 *
 * Smaller is not simply worse. Averaging correlates the noise between
 * neighbouring pixels, and a multi-frame merge downstream expects noise to be
 * independent per pixel: too much smoothing here and the merge misreads it. A
 * narrow kernel looks blockier on its own but leaves the noise honest.
 */
uniform int kernelSize;
/** Final pass divides, intermediate passes do not. */
uniform int divide;

out vec4 Output;

float weightAt(int i, int n) {
    if (n <= 1) return 1.0;
    // [1,2,2,2,1]/8 for n=5, [1,2,...,2,1]/16 for n=9: flat inside, half at the
    // ends. A flat kernel over exactly one block period averages each block
    // evenly; the tapered ends stop the block edges from stepping.
    float w = (i == 0 || i == n - 1) ? 1.0 : 2.0;
    float total = float(2 * (n - 2)) + 2.0;
    return w / total;
}

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    int pad = (kernelSize - 1) / 2;
    vec2 acc = vec2(0.0);

    for (int i = 0; i < kernelSize; i++) {
        ivec2 p = xy;
        if (axis == 1) p.x = clamp(xy.x + i - pad, 0, size.x - 1);
        else           p.y = clamp(xy.y + i - pad, 0, size.y - 1);
        acc += texelFetch(InputBuffer, p, 0).xy * weightAt(i, kernelSize);
    }

    if (divide == 1) {
        // A positive mask must preserve a constant channel exactly.
        // Adding an epsilon to every weight creates a phase-dependent bias.
        Output = vec4((acc.y > 0.0 ? acc.x / acc.y : 0.0), acc.y, 0.0, 1.0);
    } else {
        Output = vec4(acc.x, acc.y, 0.0, 1.0);
    }
}
