precision highp float;
precision highp int;
precision highp sampler2D;
// LMC noise reduction on the Sabre 2x grid, Laplacian re-assembly: out = RGB_2x + RGB(Up2(Den0 - X0)), where the
// pyramids ran from the sensor-scale image X0 = Down(RGB_2x). Without noise reduction this is the identity; no
// restoring pass works "through a pixel" on the 2x grid. The colour finer than the sensor scale (uv(RGB_2x) -
// Up2(uv(X0)): above the sensor's colour resolution, mostly colour noise of the 2x grid that the pyramid never
// sees) is kept only at the share keep, so the colour of the 2x output is denoised like the 1x one; luma keeps its
// 2x detail. With Delta = (Y(Den0) - Y(X0), UV(Den0) - keep UV(X0)) from lmcdn/cbf mode 3 this is
//   Y = Y(RGB_2x) + Up2(Delta.x),  UV = keep UV(RGB_2x) + Up2(Delta.yz)
// (Up2 is linear), one upsampled texture. Up2 = bilinear x2 at the sensor-scale position p/2 - 0.25 from four integer
// texel fetches: a float coordinate (p + 0.5) / 2 is an fp16 value on Adreno (4-pixel steps above 4096 px).
// Optional fade of the colour in the darkest pixels (a colour clearly stronger than a black-level tint is kept, see
// lmcdn/cbf); a non-finite input sample is zeroed (see lmcdn/yuv).
uniform sampler2D InputBuffer;  // RGB on the 2x grid
uniform sampler2D Delta;        // sensor scale: (Y change, UV(Den0) - keep UV(X0))
uniform float keepU;            // share of the 2x-only colour residual kept (unset 0 = colour from the sensor scale)
uniform int fadeU;
uniform vec2 darkFadeU;
uniform vec2 darkChromaU;       // colour deviation |RGB - mean| kept despite the fade (lmcdn/cbf; 0 = off)
out vec4 Output;
const vec3 kY = vec3(0.2126, 0.7152, 0.0721996);
const vec3 kU = vec3(-0.162450244, -0.546494309, 0.708944715);
const vec3 kV = vec3(0.999996748, -0.908302439, -0.091693333);
vec3 toRgb(vec3 c) {
    return vec3(dot(c, vec3(0.999999632, -0.000000001, 0.787402639)),
                dot(c, vec3(1.00000065, -0.132114184, -0.234062881)),
                dot(c, vec3(1.000000187, 1.308706208, -0.000000397)));
}
float finite1(float v) { return (isnan(v) || isinf(v)) ? 0.0 : v; }
vec3 up2(ivec2 p) {             // bilinear x2 at p/2 - 0.25: weights 9/16, 3/16, 3/16, 1/16 (even p: -1 side, odd: +1)
    ivec2 lim = textureSize(Delta, 0) - 1;
    ivec2 b = p >> 1, o = ((p & 1) << 1) - 1;
    ivec2 n = clamp(b + o, ivec2(0), lim);
    b = min(b, lim);
    return 0.5625 * texelFetch(Delta, b, 0).rgb + 0.1875 * texelFetch(Delta, ivec2(n.x, b.y), 0).rgb
         + 0.1875 * texelFetch(Delta, ivec2(b.x, n.y), 0).rgb + 0.0625 * texelFetch(Delta, n, 0).rgb;
}
void main() {
    highp ivec2 p = ivec2(gl_FragCoord.xy);
    vec3 c = texelFetch(InputBuffer, p, 0).rgb;
    c = vec3(finite1(c.r), finite1(c.g), finite1(c.b));
    vec3 d = up2(p);
    float keep = clamp(keepU, 0.0, 1.0);
    vec3 yuv = vec3(dot(c, kY) + d.x, keep * vec2(dot(c, kU), dot(c, kV)) + d.yz);
    if (fadeU != 0) {
        vec3 rgb = toRgb(yuv);
        float m = dot(rgb, vec3(1.0 / 3.0));
        float t = smoothstep(darkFadeU.x, darkFadeU.y, m);
        if (darkChromaU.y > 0.0) t = max(t, smoothstep(darkChromaU.x, darkChromaU.y, length(rgb - vec3(m))));
        yuv.yz *= t;
    }
    Output = vec4(max(toRgb(yuv), vec3(0.0)), 1.0);
}
