precision highp float;
precision highp int;
precision highp sampler2D;
// SCAM noise reduction on the Sabre 2x grid: the pyramids start at the SENSOR scale. One sensor-scale pixel q
// from the 2x RGB: luminance through the 8-tap kernel of the luma pyramid (kD8 = [-3,-7,17,57,57,17,-7,-3]/128,
// taps 2q-3 .. 2q+4), colour differences through the chroma pyramid's {1,3,3,1}/8 (taps 2q-1 .. 2q+2); returned
// as RGB so the despeckle and the final Laplacian re-assembly see the same image.
// Each pair of taps of equal sign is one bilinear fetch (the input is linearly filtered): kD8 = 4 x 4 fetches,
// {1,3,3,1} = 2 x 2. Positions are integer pixel indices plus a constant fraction.
// Precision: "precision highp int" is required here. The fragment default int is mediump, and vec2(int) of a mediump
// int is a mediump (fp16 on Adreno) float: 2q + 0.5 then loses the 0.5 above 1024 and snaps to 4-pixel steps above
// 4096 (the 2x grid is ~8192 wide) - the same failure as the GPU final resize (period-4 duplicate pixels).
// Non-finite samples are zeroed (see scamdn/yuv).
uniform sampler2D InputBuffer;
out vec4 Output;
float finite1(float v) { return (isnan(v) || isinf(v)) ? 0.0 : v; }
vec3 finite3(vec3 c) { return vec3(finite1(c.r), finite1(c.g), finite1(c.b)); }
void main() {
    highp ivec2 q = ivec2(gl_FragCoord.xy);
    highp vec2 inv = 1.0 / vec2(textureSize(InputBuffer, 0));
    highp vec2 base = vec2(q * 2) + 0.5;         // centre of the fine pixel 2q
    // kD8 pairs (-3,-7) (17,57) (57,17) (-7,-3) at offsets -3,-1,1,3 from 2q, weights -10, 74, 74, -10 (/128).
    const vec4 wy = vec4(-10.0, 74.0, 74.0, -10.0) / 128.0;
    const vec4 oy = vec4(-3.0 + 0.7, -1.0 + 57.0 / 74.0, 1.0 + 17.0 / 74.0, 3.0 + 0.3);
    const vec3 kY = vec3(0.2126, 0.7152, 0.0721996);
    const vec3 kU = vec3(-0.162450244, -0.546494309, 0.708944715);
    const vec3 kV = vec3(0.999996748, -0.908302439, -0.091693333);
    float y = 0.0;
    for (int j = 0; j < 4; j++) {
        for (int i = 0; i < 4; i++) {
            vec3 c = finite3(texture(InputBuffer, (base + vec2(oy[i], oy[j])) * inv).rgb);
            y += wy[i] * wy[j] * dot(c, kY);
        }
    }
    // {1,3,3,1}/8: pairs (1,3) at 2q-1 -> 2q-0.25 and (3,1) at 2q+1 -> 2q+1.25, half the weight each.
    vec2 uv = vec2(0.0);
    for (int j = 0; j < 2; j++) {
        for (int i = 0; i < 2; i++) {
            vec3 c = finite3(texture(InputBuffer, (base + vec2(i == 0 ? -0.25 : 1.25, j == 0 ? -0.25 : 1.25)) * inv).rgb);
            uv += 0.25 * vec2(dot(c, kU), dot(c, kV));
        }
    }
    vec3 yuv = vec3(y, uv);
    Output = vec4(dot(yuv, vec3(0.999999632, -0.000000001, 0.787402639)),
                  dot(yuv, vec3(1.00000065, -0.132114184, -0.234062881)),
                  dot(yuv, vec3(1.000000187, 1.308706208, -0.000000397)), 1.0);
}
