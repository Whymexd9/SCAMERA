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
//   MODE 2: P378 (owner, 2026-10-28: the pink / orange ring round a sun clipped at the sensor floor, «везде»): per block the
//           share of the pixels «Inpaint opposed» rebuilds - scamrgb's opposed branch: a channel at 0.987 of its clip (raw
//           >= 0.987 x the clip level, scamrgb's c >= clipW before the white balance and the shading, which scale both
//           sides) and no ultrashort content (flags: any of bits 0-3, neither 4 nor 5; without flags, cpu-single: no Bento
//           level at all) - as the block's weight smoothstep(0.05, 0.15, share) in .r (.g the share): a light of a block or
//           more counts in full wherever it sits on the grid (a disc a block wide covers >= 0.19 of one), a few clipped dots
//           (3 x 3 px: 0.035 of a 16 px block) not at all. MODE 1 spreads it for WhyCombine's highlight landing (HL_OPPOSED:
//           the low-chroma glow round such a light lands on white).
//           P378 review: a pixel counts only with every channel at 0.987 of its clip - the blown core «Inpaint opposed» leaves
//           neutral (G alone clipped is any pale surface: a blue sky whitened, a white stripe on a red band's edge). And the
//           light's own colour: the glow beside the core - pixels no channel of which clipped (no flag), from 0.6 of the clip
//           up (where the light outshines what is behind it), weighted smoothstep(0.6, 0.95) of their brightest channel -
//           through the shot's colour (toRenderU: the render's linear sRGB of camera RGB), its saturation 1 - min / max: .b the
//           block's weighted mean, .a the weight.
//   MODE 3: P378 review (the per-pixel cut-off drew white rings where a light's glow paled with distance): each block's light
//           decided once - by the saturation of the nearest glow (MODE 2's .b of the block that gives the maximum of
//           exp(-d^2 / R^2) x the glow's presence, its weight .a against glowFullU, 1 from a tenth of a block: a neighbour lamp's
//           brighter glow farther away does not decide it; two passes as MODE 1, finalU 0 along x into (weight, maximum, its
//           saturation), finalU 1 along y), a gate 1 for a pale warm
//           glow (a sun's aureole: R/G 1.25 - 1.35 measures 0.46 - 0.73, a lamp of 1.45 : 1 : 0.6 0.69) to 0 for a coloured
//           light (an orange lamp 1.6 : 1 : 0.45 measures 0.81 - 0.83 in front of a light wall, a red rim over 1; a warmer
//           aureole of R/G 1.4, 0.79, nearly as before P378), 1 with no glow within ~1.7 R (a core in its own white); out
//           (weight, gate).
//   MODE 1: also carries .g of the tap that gives the maximum (MODE 3's gate: the gate of the light that is nearest by the map).
#define MODE 0
#define GATE_LO 0.74
#define GATE_HI 0.80
uniform sampler2D InputBuffer;  // MODE 0: the clip flags (R8 normalised, code = r * 255); MODE 1: the share to spread (.g carried);
                                // MODE 2: scamrgb's input (camera RGB before the white balance, signed), the flags' grid; MODE 3:
                                // MODE 2's blocks
#if MODE == 2
uniform sampler2D ClipFlags;    // the clip flags (clipFlagsU 1)
uniform int clipFlagsU;         // 1: ClipFlags holds the worker's per-pixel flags; 0: none (cpu-single)
uniform vec3 clipLoU;           // scamrgb's clip levels per camera channel (raw units; <= 0: none)
uniform vec3 clipHiU;
uniform mat3 toRenderU;         // camera RGB -> the render's linear sRGB (WhyTone.colourMatrix x sensorToProPhoto)
#endif
uniform int blockU;             // MODE 0 / 2: block size, px
uniform ivec2 dirU;             // MODE 1 / 3: the axis
uniform float radiusU;          // MODE 1 / 3: R, texels
uniform int finalU;             // MODE 3: 0 the first axis, 1 the second and the gate
uniform float glowFullU;        // MODE 3: the glow weight of a block that counts in full
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
#elif MODE == 2
    // scamrgb's levelAt and its opposed branch: with flags a level wherever bits 0-3 are set, ultrashort content (4, 5) left to
    // the SCAM recovery; without flags the base frames' level, and nothing when a Bento level exists (every pixel holds it)
    vec3 lc = clipLoU.g > 0.0 ? clipLoU : clipHiU;
    bool level = lc.g > 0.0 && (clipFlagsU != 0 || clipHiU.g <= 0.0);
    int n = 0, c = 0;
    float gw = 0.0, gs = 0.0;
    for (int j = 0; j < blockU; j++) {
        for (int i = 0; i < blockU; i++) {
            ivec2 p = xy * blockU + ivec2(i, j);
            if (p.x >= size.x || p.y >= size.y) continue;
            n++;
            if (!level) continue;
            int f = clipFlagsU != 0 ? int(texelFetch(ClipFlags, p, 0).r * 255.0 + 0.5) : 0;
            vec3 v = texelFetch(InputBuffer, p, 0).rgb;
            // NaN / inf compare false: neither counted nor measured (scamrgb zeroes them)
            if (any(greaterThanEqual(v, 0.987 * lc))) {
                if (clipFlagsU != 0 && ((f & 15) == 0 || (f & 48) != 0)) continue;
                if (all(greaterThanEqual(v, 0.987 * lc))) c++;
            } else if ((f & 15) == 0) {
                vec3 r = v / lc;
                float w = smoothstep(0.6, 0.95, max(r.r, max(r.g, r.b)));
                if (w > 0.0) {
                    vec3 col = toRenderU * v;
                    float hi = max(col.r, max(col.g, col.b));
                    gs += w * (1.0 - min(col.r, min(col.g, col.b)) / max(hi, 0.000001));
                    gw += w;
                }
            }
        }
    }
    float share = n > 0 ? float(c) / float(n) : 0.0;
    Output = vec4(smoothstep(0.05, 0.15, share), share, gw > 0.0 ? gs / gw : 0.0, gw);
#elif MODE == 3
    int r = int(ceil(2.5 * radiusU));
    float m = 0.0, sat = 0.0;
    for (int i = -r; i <= r; i++) {
        ivec2 p = xy + dirU * i;
        if (p.x < 0 || p.y < 0 || p.x >= size.x || p.y >= size.y) continue;
        float t = float(i) / radiusU;
        vec4 b = texelFetch(InputBuffer, p, 0);
        float v = exp(-t * t) * (finalU == 0 ? clamp(b.a / glowFullU, 0.0, 1.0) : b.g);
        if (v > m) {
            m = v;
            sat = b.b;
        }
    }
    float w0 = texelFetch(InputBuffer, xy, 0).r;
    if (finalU == 0) Output = vec4(w0, m, sat, 1.0);
    else Output = vec4(w0, m > 0.05 ? 1.0 - smoothstep(GATE_LO, GATE_HI, sat) : 1.0, 0.0, 1.0);
#else
    int r = int(ceil(2.5 * radiusU));
    float m = 0.0, g = 0.0;
    for (int i = -r; i <= r; i++) {
        ivec2 p = xy + dirU * i;
        if (p.x < 0 || p.y < 0 || p.x >= size.x || p.y >= size.y) continue;
        float t = float(i) / radiusU;
        vec2 b = texelFetch(InputBuffer, p, 0).rg;
        float v = exp(-t * t) * b.r;
        if (v > m) {
            m = v;
            g = b.g;
        }
    }
    Output = vec4(m, g, 0.0, 1.0);
#endif
}
