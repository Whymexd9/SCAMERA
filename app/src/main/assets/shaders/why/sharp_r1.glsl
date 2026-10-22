precision highp float;
precision highp sampler2D;
// WHY luma sharpening (WhyLumaSharpen), P214e R1 (owner, 2026-10-18; PLAN "Research 2026-10-17"): on a mosaic shot (a Quad /
// Tetra stream merged on the mosaic, Parameters.mosaicBlock > 1) the sharpening reads the 3x3-smoothed guide B(Ya) and only
// its detail S(B(Ya)) - B(Ya) is added to Ya. The site lattice of the mosaic merge reaches the sharpening through B only at
// cos^2(pi fx) cos^2(pi fy) of its amplitude - the diagonal 4 px one (0.25, +-0.25 cycles / px) at a quarter, the 2 px one
// (0.5 cycles / px) not at all - and otherwise passes through at its own level, instead of being lifted x2.5-2.9 (diagonal)
// by the USM, the bilateral high boost and the Richardson-Lucy stages. Measured on the owner's 50 MP burst: diagonal lattice
// 0.084 / 0.093 % -> 0.045 / 0.049 % of the level (0.032 / 0.036 % unsharpened). Image detail below ~0.2 cycles / px keeps
// most of its sharpening (B passes 0.65 of it at 0.2, 0.9 at 0.1). A plain Bayer shot never runs this program.
//  modeU 0: Output = B(InputBuffer), B = [1 2 1]^T [1 2 1] / 16, edge pixels repeated     -> R16F: the sharpening's source
//  modeU 1: Output = InputBuffer - Smoothed = Ya - B, B as stored by mode 0              -> R16F: the residual D (Ya goes)
//  modeU 2: Output = max(Sharpened + Residual, 0) = Ya + S(B) - B                        -> R16F: WhyCombine's WhyLumaS
// Ya + S - B is never formed from a B computed again (a driver may round the fp16 store differently from the shader): D is
// the difference of two fp16 planes, exact in fp16 wherever B lies within a factor 2 of Ya (all but the strongest edges) and
// is not below the fp16 normal range, so where the chain leaves B alone (S = B) the result is Ya bit for bit, else within an
// ulp of it.
uniform sampler2D InputBuffer;      // modes 0, 1: Ya (.r), output grid
uniform sampler2D Smoothed;         // mode 1: B(Ya) (.r) of mode 0
uniform sampler2D Sharpened;        // mode 2: S(B(Ya)) (.r), the chain's last output
uniform sampler2D Residual;         // mode 2: D = Ya - B (.r) of mode 1
uniform int modeU;
out vec4 Output;

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    if (modeU == 2) {
        Output = vec4(max(texelFetch(Sharpened, xy, 0).r + texelFetch(Residual, xy, 0).r, 0.0), 0.0, 0.0, 1.0);
        return;
    }
    if (modeU == 1) {
        Output = vec4(texelFetch(InputBuffer, xy, 0).r - texelFetch(Smoothed, xy, 0).r, 0.0, 0.0, 1.0);
        return;
    }
    ivec2 last = textureSize(InputBuffer, 0) - ivec2(1);
    float s = 0.0;
    for (int dy = -1; dy <= 1; dy++) {
        float row = 0.0;
        for (int dx = -1; dx <= 1; dx++)
            row += (dx == 0 ? 2.0 : 1.0) * texelFetch(InputBuffer, clamp(xy + ivec2(dx, dy), ivec2(0), last), 0).r;
        s += (dy == 0 ? 2.0 : 1.0) * row;
    }
    Output = vec4(s * 0.0625, 0.0, 0.0, 1.0);
}
