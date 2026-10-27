precision highp float;
precision highp sampler2D;
// WHY luma sharpening (WhyLumaSharpen), P214e R1 (owner, 2026-10-18; PLAN "Research 2026-10-17"): on a mosaic shot (a Quad /
// Tetra stream merged on the mosaic, Parameters.mosaicBlock > 1) the sharpening reads the 3x3-smoothed guide B(Ya) and only
// its detail S(B(Ya)) - B(Ya) is added to Ya. The site lattice of the mosaic merge reaches the sharpening through B only at
// cos^2(pi fx) cos^2(pi fy) of its amplitude - the diagonal 4 px one (0.25, +-0.25 cycles / px) at a quarter, the 2 px one
// (0.5 cycles / px) not at all - and otherwise passes through at its own level, instead of being lifted x2.5-2.9 (diagonal)
// by the USM, the bilateral high boost and the Richardson-Lucy stages. Measured on the owner's 50 MP burst: diagonal lattice
// 0.084 / 0.093 % -> 0.045 / 0.049 % of the level (0.032 / 0.036 % unsharpened). Image detail below ~0.2 cycles / px keeps
// most of its sharpening (B passes 0.65 of it at 0.2, 0.9 at 0.1).
// P337 (owner, 2026-10-25: a mesh on the sea of a 3.7x telephoto shot): a plain Bayer shot reads Ya without its 2 px lattice
// (mode 3): the merge leaves the checker / row / column alternation at 10 % of the high band (sea, vivo X200 Ultra tele), the
// USM, bilateral and Richardson-Lucy stages lifted it to 16 % in the JPEG (row alternation 1.8 x ArkCam's). Mode 3 takes out
// exactly the 2 px alternations of the 5 x 5 sites around (weights (1 2 2 2 1) / 8 per axis, as scamdn/lgrain): a constant or
// a linear Ya gives 0 in them, every other detail is sharpened as before, and the lattice comes back at its own level (mode 2).
//  modeU 0: Output = B(InputBuffer), B = [1 2 1]^T [1 2 1] / 16, edge pixels repeated     -> R16F: the sharpening's source
//  modeU 3: Output = InputBuffer - its 2 px alternations (P337, plain Bayer)             -> R16F: the sharpening's source
//  modeU 1: Output = InputBuffer - Smoothed = Ya - B, B as stored by mode 0              -> R16F: the residual D (Ya goes)
//  modeU 2: Output = max(Sharpened + Residual, 0) = Ya + S(B) - B                        -> R16F: WhyCombine's WhyLumaS
// Ya + S - B is never formed from a B computed again (a driver may round the fp16 store differently from the shader): D is
// the difference of two fp16 planes, exact in fp16 wherever B lies within a factor 2 of Ya (all but the strongest edges) and
// is not below the fp16 normal range, so where the chain leaves B alone (S = B) the result is Ya bit for bit, else within an
// ulp of it.
// P364: GATHER 1 - mode 3 reads its 5 x 5 sites as nine 2 x 2 textureGather blocks instead of 25 texelFetch, the same taps
// added in the same order (row by row, left to right) with the same expression; InputBuffer is clamp-to-edge, so a block past
// the border repeats the edge texels as clamp() did (bit-identical). 0 = the loop.
#define GATHER 0
uniform sampler2D InputBuffer;      // modes 0, 1: Ya (.r), output grid
uniform sampler2D Smoothed;         // mode 1: B(Ya) (.r) of mode 0
uniform sampler2D Sharpened;        // mode 2: S(B(Ya)) (.r), the chain's last output
uniform sampler2D Residual;         // mode 2: D = Ya - B (.r) of mode 1
uniform int modeU;
out vec4 Output;

#if GATHER == 1
// mode 3: tap (i, j) of value t added to lat, as the loop below adds it
float latTap(float lat, int i, int j, float t) {
    const float w5[5] = float[5](0.125, 0.25, 0.25, 0.25, 0.125);
    float sj = (j & 1) == 0 ? 1.0 : -1.0;
    float si = (i & 1) == 0 ? 1.0 : -1.0;
    float v = t * w5[i + 2] * w5[j + 2];
    return lat + (si * sj + sj + si) * v;   // checker + row + column alternation, sign + at xy
}
#endif

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
    if (modeU == 3) {
#if GATHER == 1
        // blocks (x0, y0) .. (x0 + 1, y0 + 1) by a gather at the centre of texel (x0 + 1, y0 + 1): .w (x0, y0), .z (x0 + 1, y0),
        // .x (x0, y0 + 1), .y (x0 + 1, y0 + 1)
        vec2 inv = 1.0 / vec2(last + ivec2(1));
        float lt = 0.0;
        for (int by = -2; by <= 2; by += 2) {
            vec4 g0 = textureGather(InputBuffer, (vec2(xy + ivec2(-2, by)) + 1.0) * inv);
            vec4 g1 = textureGather(InputBuffer, (vec2(xy + ivec2(0, by)) + 1.0) * inv);
            vec4 g2 = textureGather(InputBuffer, (vec2(xy + ivec2(2, by)) + 1.0) * inv);
            lt = latTap(lt, -2, by, g0.w); lt = latTap(lt, -1, by, g0.z); lt = latTap(lt, 0, by, g1.w);
            lt = latTap(lt, 1, by, g1.z); lt = latTap(lt, 2, by, g2.w);
            if (by < 2) {
                lt = latTap(lt, -2, by + 1, g0.x); lt = latTap(lt, -1, by + 1, g0.y); lt = latTap(lt, 0, by + 1, g1.x);
                lt = latTap(lt, 1, by + 1, g1.y); lt = latTap(lt, 2, by + 1, g2.x);
            }
        }
        Output = vec4(texelFetch(InputBuffer, xy, 0).r - lt, 0.0, 0.0, 1.0);
        return;
#endif
        const float w5[5] = float[5](0.125, 0.25, 0.25, 0.25, 0.125);
        float lat = 0.0;
        for (int j = -2; j <= 2; j++) {
            float sj = (j & 1) == 0 ? 1.0 : -1.0;
            for (int i = -2; i <= 2; i++) {
                float si = (i & 1) == 0 ? 1.0 : -1.0;
                float v = texelFetch(InputBuffer, clamp(xy + ivec2(i, j), ivec2(0), last), 0).r * w5[i + 2] * w5[j + 2];
                lat += (si * sj + sj + si) * v;   // checker + row + column alternation, sign + at xy
            }
        }
        Output = vec4(texelFetch(InputBuffer, xy, 0).r - lat, 0.0, 0.0, 1.0);
        return;
    }
    float s = 0.0;
    for (int dy = -1; dy <= 1; dy++) {
        float row = 0.0;
        for (int dx = -1; dx <= 1; dx++)
            row += (dx == 0 ? 2.0 : 1.0) * texelFetch(InputBuffer, clamp(xy + ivec2(dx, dy), ivec2(0), last), 0).r;
        s += (dy == 0 ? 2.0 : 1.0) * row;
    }
    Output = vec4(s * 0.0625, 0.0, 0.0, 1.0);
}
