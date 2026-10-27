precision highp float;
precision highp sampler2D;
// P349 (owner, 2026-10-26: «у нас солнце как круг выглядит на новой версии, сделай схоже с Арк камерой»): the glow of a light
// whose core has no data at all - R, G and B clipped in every frame, the ultrashort too (clip flags bits 0-2) - for WhyCombine's
// sun bloom. ArkCam clips everything above white, so a setting sun is a soft warm-white area with no edge; our Bento headroom
// keeps the glow round the core (2 - 3 x white) orange and the clipped core stands in it as a flat white disc with a hard rim.
//   MODE 0: the share of fully clipped pixels in each blockU x blockU block of the flags (whole frame ~192 blocks high)
//           P366 (owner, 2026-10-27, X200U ISZ at sea: «вот такие фото получаю с исз, исправь» - a milky haze and white blobs
//           round the foam): a pixel counts only when its clipped mean holds the ultrashort frame too (bit 5). Bits 0-2 alone
//           are any highlight the normal frames clip where no ultrashort sample was taken (foam and glitter the rejection
//           kept the ultrashort out of, shots without one): the bloom spread over the whole bright sea and sky.
//   MODE 1: one axis of the max-gaussian max_i exp(-(i / R)^2) v(x + i dir) of that share; both axes give
//           max over the blocks of exp(-d^2 / R^2) x share, the glow of the cores by distance. A core much smaller than a block
//           (an LED, a street lamp's filament, the moon here) has a small share and WhyCombine leaves it alone.
#define MODE 0
uniform sampler2D InputBuffer;  // MODE 0: the clip flags (R8 normalised, code = r * 255); MODE 1: the share to spread
uniform int blockU;             // MODE 0: block size, px
uniform ivec2 dirU;             // MODE 1: the axis
uniform float radiusU;          // MODE 1: R, texels
out vec4 Output;

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    ivec2 size = textureSize(InputBuffer, 0);
#if MODE == 0
    int n = 0, c = 0;
    for (int j = 0; j < blockU; j++) {
        for (int i = 0; i < blockU; i++) {
            ivec2 p = xy * blockU + ivec2(i, j);
            if (p.x >= size.x || p.y >= size.y) continue;
            int f = int(texelFetch(InputBuffer, p, 0).r * 255.0 + 0.5);
            n++;
            if ((f & 7) == 7 && (f & 32) != 0) c++;
        }
    }
    Output = vec4(n > 0 ? float(c) / float(n) : 0.0, 0.0, 0.0, 1.0);
#else
    int r = int(ceil(2.5 * radiusU));
    float m = 0.0;
    for (int i = -r; i <= r; i++) {
        ivec2 p = xy + dirU * i;
        if (p.x < 0 || p.y < 0 || p.x >= size.x || p.y >= size.y) continue;
        float t = float(i) / radiusU;
        m = max(m, exp(-t * t) * texelFetch(InputBuffer, p, 0).r);
    }
    Output = vec4(m, 0.0, 0.0, 1.0);
#endif
}
