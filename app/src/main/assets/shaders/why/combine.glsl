precision highp float;
precision highp sampler2D;
// P305: the pixel coordinates (ivec2 xy) are highp too. A fragment shader's ints default to mediump, which Adreno runs as
// fp16: the dither below took float(xy) and its phase 0.067 x + 0.0058 y in fp16, so instead of +-0.5 LSB noise it had 4..16
// values in plates 1..4 px wide and 11..43 rows tall (wider and taller with x), which jpegli's dead-zone quantisation kept as
// vertical streaks on every smooth area (the 50 MP ship's hull, the sky). Desktop GL runs mediump as fp32 (host replays).
precision highp int;
// WHY tone, final step (WhyCombine): ef_guided_combine of libfc_suppressor.so [fc_cl_source.cl:1399-1844] on every
// output pixel, with the high-frequency detail taken from the full-size Sabre merge instead of Google's guide a3.
//  1. colour/base: cubic B-spline of the colour source (whyLow after bracket_dn on the 1x grid, whyMid on the 2x grid)
//     and of the fused luma; target_lin = ACES^-1(fused^2.2); gain = target_lin / (max(Y709, max/2, 1e-4) * ae);
//     rgb = orig * ae * gain (channel ratios kept: shadows are lifted without a hue shift).
//  2. Bento ceiling (clip > 1.05) -> OKLab macro contrast around L 0.4, vibrance with saturated-colour protection,
//     chroma denoise, hue direction kept -> linear -> Bento roll-off -> AgX (Kraken inset/outset of the kernel,
//     Jed Smith sigmoid, pow 2.4) -> hue lock.
//  3. detail: delta = cbrt(ae * Y_full) - cbrt(ae * B-spline(Y(whyLow))) of the full-size linear input (same colour
//     chain as why/low.glsl), added to OKLab L after the tone and never multiplied by the shadow lift
//     [:1759-1762], halved towards white [:1786-1789]. The base luminance equals target_lin (the gain normalises
//     the colour source's own luminance away), so the delta is measured against the whyLow grid of the fusion.
//     detailRefU 1 (WhyCombine): like the kernel's guide a3 ("normalized to [0, 1]") the luminance is bounded,
//     min(ae * Y, 1), with the B-spline of its box mean (WhyDetailRef) as reference, and the delta is scaled by
//     (L_base / cbrt(ref))^2 where the tone compresses (L_base below the cube-root reference). Without both the
//     unbounded delta of a lamp edge (|delta| up to 0.7) put black trenches (8 bit 0..5) around every light.
//     sharpU 1 (WhyLumaSharpen ran): the luminance is WhyCam's own sharpened guide S(Ya), Ya = min(m * Y, 1), as the
//     kernel takes delta = cbrt(S(a3)) - cbrt(a5) [:1528-1577]; WhyDetailRef is then in the same domain m and the
//     compression scaling compares against it in the ae domain (x cbrt(ae / m)).
//  4. display: pure power 1/gamma, film toe below 0.15, optional IGN dither [:1805-1843].
// Integer grid arithmetic everywhere (the output reaches 8192 px; float pixel coordinates lose precision on Adreno).
// signedColourU 1 (the working RGB is signed: SCAM Hybrid without the denoise or after the NLM engine, scamrgb signedU): the
// B-spline colour is
// clamped at zero after the interpolation, not per tap - the colour source holds box means of the signed merge, and a
// clamp per tap lifted the mean of a channel near zero (red mottling of a dark teal curtain under the shadow lift).
// 0 (unset: SCAM HDR, the hybrid after the SCAM denoise): per tap as before. The taps can be negative there as well (a
// saturated colour outside sRGB after the colour matrix); clamping those after the interpolation would move colour edges.
// P370 signedHueU 1 (WhyCombine, LiveWhyTone; scam_dev ccm_signed_hue, default 1): a colour the camera matrix put outside
// sRGB (a negative channel: LEDs, neon, a deep sky) takes its OKLab lightness and hue from the signed colour
// (origS, the same interpolation without the clamp), not from the per-channel clip (470 nm read +27 deg, 505 nm -20 deg): the
// hue direction, the hue lock and the gamut compression then act on the colour the matrix made, and its final gamut mapping
// fades the chroma cut in (fromOklabInGamutSoft). A pixel with no negative channel and no clipped tap, monochrome, and the
// whole shader with signedHueU 0 / unset are as before, bit for bit.
// P3_OUT 1 (P46 «Цветовое пространство» Display P3, WhyCombine sets it): the working colour stays linear sRGB / Rec.709
// coordinates, but nothing clamps it at the sRGB gamut - the clamps at 0 act on the P3 coordinates (clampP3), AgX takes the
// colour with its OKLab chroma reduced into the positive sRGB range and the chroma lost there comes back afterwards
// (x C_in / C_reduced), the gamut limit is P3, and the output is display-encoded P3. A colour inside sRGB takes exactly the
// sRGB operations (the same image, written in P3 primaries); a wider one keeps up to P3 of its chroma. P3_OUT 0 (the
// default): every #if P3_OUT block below drops out and the #else branches are the sRGB shader unchanged.
// COLOUR_LOOK 1 (P134 «Цвет», processing.color.ColourLook; WhyCombine sets it only when a colour setting is off its default):
// in scene OKLab before the hue direction is saved - monochrome filter, skin protection of positive gains, vibrance by
// brightness, the 8-band mixer, skin hue / evenness; the gamut compression of the linear colour; the hue-lock mode; in
// display OKLab after the hue lock - the grading of shadows / mid-tones / highlights. COLOUR_LOOK 0: every block drops out
// and the shader is the one before P134 (tools/check_colour_output.py pins its token stream).
// TONE_OP 1 (P144, processing.color.ToneCurve; WhyCombine sets it for an operator other than AgX): the AgX tonescale gives way
// to WhyCam 1.42's ACES (eval_acescg), Uchimura (uchimura_curve) or the own curve (u_tex_custom_curve) on the same AgX inset
// and outset (toneOpU 1 / 2 / 3). TONE_OP 0: AgX as before (the token stream stays the pinned one).
#define P3_OUT 0
#define COLOUR_LOOK 0
#define TONE_OP 0
// P134 «Плёночный LUT»: a 3D LUT (.cube, FilmLut) on the display-encoded output, mixed by its strength; WhyCombine sets 1
#define FILM_LUT 0
// P154 «Мягкость засветов»: blown light lands smoothly on the white point, the path to white goes up (WhyCombine sets 1)
#define HL_LANDING 0
// P216: real Bento headroom above WhyAe's capped clip (WhyCombine sets 1 when WhyAe's hr > 0; 0 = the shader before P216)
#define HEADROOM_WIDE 0
// P314 POINT_FUSED 1 (the RAW viewfinder, LiveWhyTone; never the photo): with fU 1 the fused luma is the pixel's own guide
// through the fusion's curve (FusedCurve: why/gf.glsl fusedAt with its P291 monotonic envelope, tabulated on the CPU after each
// viewfinder AE run - the guided filter's value on a flat area) instead of WhyFused, and WhyColour is the viewfinder's linear
// frame, times inScaleU here. POINT_FUSED 0: every block below drops out (the photo's shader, token for token).
#define POINT_FUSED 0
// P365 RAW_SOURCE 1 (with POINT_FUSED 1: the RAW viewfinder's fused pass, LiveWhyTone.drawFused; never the photo; owner,
// 2026-10-27: «В арк камере тоже есть живой видоискатель, сделай его такимже по требованиям как там»): the linear colour comes
// straight from the RAW frame at texCoord (utils/import_vfraw.glsl: rawdevelop_fs's linear output, and the 3 x 3 colour
// neighbourhood from the same range-filtered quads / cell means at the neighbouring pixels' frame positions, textureGather), the
// pixel grid is gl_FragCoord less the viewport's origin (vpOriginU, vpSizeU) - one pass instead of the linear frame, the tone and
// the copy to the screen.
// RAW_SOURCE 0: every block below drops out (the photo's shader, token for token; tools/check_vf_fused.py).
#define RAW_SOURCE 0
// P349 SUN_BLOOM 1: the glow round fully clipped cores (SunBloom); 0: the shader before P349, token for token.
#define SUN_BLOOM 0
// P369 FILM_SIM 1 (WhyCombine and LiveWhyTone set it with a film simulation, never with FILM_LUT): the film is fed the exposed
// scene light - the colour source x ae before the fusion's gain, the shoulders, OKLab, AgX and the display chain, with the
// full-size detail as a luminance ratio - through a log-encoded table of the model (FilmChain.scene), and its print replaces our
// output (mixed by the simulation's strength below 1). FILM_SIM 0: every block below drops out (tools/check_film_sim.py).
#define FILM_SIM 0
#import interpolation
uniform sampler2D InputBuffer;      // full-size white-balanced linear camera RGB (the merge after denoising)
uniform sampler2D GainMap;          // lens shading gains
uniform sampler2D WhyLow;           // whyLow (before bracket_dn): Y_low of the detail
uniform sampler2D WhyColour;        // colour source (whyLow after bracket_dn, or whyMid on the 2x grid)
uniform sampler2D WhyFused;         // fused display luma (.r), whyLow size
uniform sampler2D WhyDetailRef;     // detailRefU 1: box mean of min(m * Y709, 1) (.r), whyLow size (why/low.glsl DETAIL_REF)
uniform sampler2D WhyLumaS;         // sharpU 1: S(Ya) of WhyLumaSharpen (.r), output grid
uniform mat3 sensorToIntermediate;
uniform mat3 intermediateToSRGB;
uniform vec3 neutralPointU;         // unset -> 1
uniform float inScaleU;             // <= 0 -> 1
uniform int fU;                     // whyLow box factor (2 at 1x, 4 on the 2x grid; 1 = same size, kernel bypass); <= 0 -> 2
uniform int colourFU;               // box factor of the colour source; <= 0 -> fU
uniform int signedColourU;          // 1: B-spline colour clamped after the interpolation (signed input); 0: per tap
uniform int signedHueU;             // P370: 1 = OKLab of a colour outside sRGB from its signed value; 0 / unset = from the clip
uniform float aeU;                  // <= 0 -> 1
uniform float clipU;                // data ceiling; <= 0 -> 1
uniform float toeU;                 // ACES toe; <= 0 -> 0.05
uniform float gammaInvU;            // 1 / ef_gamma; <= 0 -> 1 / 2.2
uniform float macroU;               // OKLab macro contrast; <= 0 -> 1.1
uniform vec3 vibU;                  // vibrance: overall, sky, green (0 = off)
uniform float vibHlU;               // P341: vibrance of the light tones (OKLab L 0.45 .. 0.75 up), lightness kept (0 = off)
uniform float chromaDnU;            // chroma denoise of strongly lifted pixels (0 = off)
uniform float clarityU;             // delta * (1 + clarity)
uniform float flatProtectU;         // flat-area protection of the delta (0 = off)
uniform float detailGainU;          // why_detail_gain; 0 (unset) = no detail
uniform int detailRefU;             // 1: bounded, compression-scaled delta against WhyDetailRef; unset -> unbounded delta
uniform int sharpU;                 // 1: the delta's luminance is WhyLumaS; unset -> the full-size merge
uniform int fullInputU;             // P349: 1 = InputBuffer is the full-size image (not given back above 100 MP); 0 / unset: it may not be
uniform float sharpToneU;           // sharpU 1: ae / m (WhyLumaS domain -> ae domain); <= 0 -> 1
uniform float deltaChromaU;         // 0..1: a darkening delta scales a, b with L (hue and relative chroma kept); 0 = kernel
uniform float filmToeU;             // film toe (0 = off)
uniform vec4 agxAU;                 // slope, shoulder power, toe power, saturation; slope <= 0 -> 2.7, 1.35, 1.6, 1
uniform vec4 agxBU;                 // min EV, max EV, EV, look; all 0 (unset) -> -8.5, 3.5, 0.3, 4
uniform int ditherU;                // 1 = interleaved-gradient dither +-0.5 LSB (as the kernel); unset -> none
uniform int headroomU;              // 1: real headroom above 1 (clipped RAW recovered / Bento); 0: the excess is WB headroom
#if HEADROOM_WIDE == 1
// P216: the real ceiling of the Bento data above the capped clip (WhyAe hr). With it the highlights go through WhyCam 1.58's
// shoulder on the tonemapper top instead of the 1.23 kernel's two shoulders (all above 1 into <= 1.35, then a roll-off from
// 0.5), which put everything from 1.5 to 8 x white on one grey; the landing on white follows this ceiling; hrDetailU > 0 adds
// the full-size highlight detail the bounded detail reference drops.
uniform float hrU;
uniform float hrKneeU;              // shoulder knee x the tonemapper top; <= 0 -> 0.5 (WhyCam 1.58 hdr_shoulder_knee: 0.6)
uniform float hrRoomU;              // shoulder headroom x the top; <= 0 -> 0.4 (hdr_shoulder_headroom: 0.35)
uniform float hrDetailU;            // highlight detail: share of the full-size log contrast kept in OKLab L (0 = off)
#endif
uniform float hlWhiteU;             // path to white of saturated light entering the shoulder (0 = off)
#if SUN_BLOOM == 1
uniform sampler2D SunBloom;         // P349: the glow share of the fully clipped cores (scamhdr/suncore.glsl), linear filter
uniform vec2 sunGridU;              // output uv -> SunBloom uv (its blocks cover a little more than the image)
uniform float sunBloomU;            // strength 0..1
#endif
#if POINT_FUSED == 1
uniform sampler2D FusedCurve;       // R32F n x 1: the fused display luma at log2 I = fusedCurveU.x + i / fusedCurveU.y
uniform vec2 fusedCurveU;           // x: log2 I of entry 0, y: entries per EV
#endif
#if RAW_SOURCE == 1
#import vfraw
in vec2 texCoord;                   // P365: the frame position of the pixel (rawdevelop_vs)
uniform ivec2 vpOriginU;            // P365: the viewport's origin in the framebuffer
uniform ivec2 vpSizeU;              // P365: the viewport's size (the tone size)
#endif
#if HL_LANDING == 1
uniform float hlSoftU;              // P154 highlight landing: where it starts below the data ceiling (fraction, 0 = off)
uniform sampler2D WhyHl;            // P154: the blurred share of the ceiling (why/hlmask.glsl), whyLow size, linear filter
#endif
#if COLOUR_LOOK == 1
uniform vec3 skinU;                 // skin: protection of positive gains 0..1, hue shift -1..1 (x 12 deg), evenness 0..1
uniform vec2 vibLU;                 // vibrance of the shadows, of the highlights (-1..1)
uniform float mixU[24];             // mixer: band * 3 + (hue, saturation, lightness), -1..1
uniform int mixOnU;                 // 1: some mixer value is not 0
uniform vec3 monoU;                 // monochrome filter transmission; 0 = colour
uniform float gamutU;               // gamut compression 0..1
uniform int hueLockU;               // 0 full (as before), 1 blues only, 2 off
uniform vec4 gradeAU;               // grading offsets in OKLab: shadows a, b, mid-tones a, b
uniform vec4 gradeBU;               // highlights a, b, balance -1..1, blending 0..1
#endif
#if TONE_OP == 1
uniform int toneOpU;                // 1 ACES, 2 Uchimura, 3 own curve
uniform vec4 acesU;                 // ACES: pre-gain, a, d, toe
uniform vec4 uchiAU;                // Uchimura: pre-gain, post-gain, P (max brightness), a (contrast)
uniform vec4 uchiBU;                // Uchimura: m (linear start), l (linear length), c (toe power), b (black offset)
uniform vec4 curveU[64];            // own curve: 256 samples over x 0..1 (0.5 + EV / 10 around the 0.18 grey)
#endif
#if FILM_LUT == 1
precision highp sampler3D;
uniform highp sampler3D FilmLut;
uniform vec2 lutU;                  // x strength 0..1, y LUT size N (texel centres: c (N - 1) / N + 0.5 / N)
uniform vec3 lutMinU;               // the file's DOMAIN_MIN
uniform vec3 lutScaleU;             // 1 / (DOMAIN_MAX - DOMAIN_MIN)
#endif
#if FILM_SIM == 1
precision highp sampler3D;
uniform highp sampler3D FilmLut;    // P369: the film's print (encoded sRGB) of scene light, per channel on a log grid
uniform vec2 lutU;                  // x the simulation's strength 0..1 (1: the film alone), y the table's size N
uniform float filmExpU;             // the film's exposure over ae: the highlight overflow's cut undone x 0.184 / AE target
uniform vec2 filmLogU;              // the grid: node 0 at 0.184 x 2^x, y stops across (FilmSim.LOG_LO, LOG_SPAN)
#endif
out vec4 Output;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
#if P3_OUT == 1
// Linear sRGB <-> linear Display P3 (D65), column-major (processing.color.OutputColour.srgbToP3 / p3ToSrgb).
const mat3 SRGB_TO_P3 = mat3(0.8224619687, 0.0331941989, 0.0170826307, 0.1775380313, 0.9668058011, 0.0723974407,
                             0.0, 0.0, 0.9105199286);
const mat3 P3_TO_SRGB = mat3(1.2249401763, -0.0420569547, -0.0196375546, -0.2249401763, 1.0420569547, -0.0786360456,
                             0.0, 0.0, 1.0982736001);
// sRGB coordinates of a colour with its negative P3 components set to 0 (the P3 counterpart of max(c, 0)).
vec3 clampP3(vec3 c) { return P3_TO_SRGB * max(SRGB_TO_P3 * c, vec3(0.0)); }
#endif

vec3 sceneLinear(ivec2 xy, ivec2 size) {
    vec3 inColor = max(texelFetch(InputBuffer, xy, 0).rgb, vec3(0.0));
    vec4 gains = textureBicubicHardware(GainMap, vec2(xy) / vec2(size));
    gains.rgb = vec3(gains.r, (gains.g + gains.b) / 2.0, gains.a);
    float lsc = dot(gains.rgb, vec3(1.0 / 3.0));
    vec3 neutral = neutralPointU.r + neutralPointU.g + neutralPointU.b > 0.0 ? neutralPointU : vec3(1.0);
    float scale = inScaleU > 0.0 ? inScaleU : 1.0;
    return intermediateToSRGB * (sensorToIntermediate * (inColor * neutral * (lsc * scale)));
}

float max3(vec3 v) { return max(v.r, max(v.g, v.b)); }
#if FILM_SIM == 1
#if P3_OUT == 1
// P369: the film's sRGB print to the P3 file (whose transfer is the sRGB curve): decode, P3 primaries, encode
vec3 filmDec(vec3 v) { return mix(v / 12.92, pow((v + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), v)); }
vec3 filmEnc(vec3 y) { y = clamp(y, 0.0, 1.0); return mix(12.92 * y, 1.055 * pow(y, vec3(1.0 / 2.4)) - 0.055, step(vec3(0.0031308), y)); }
#endif
#endif
#if POINT_FUSED == 1
// P314: the fused luma of exposed linear colour c: the guide of why/gf.glsl prepare() (I = max(Y709, max / 2); every layer
// meters I x em, so a pixel's own weights and lumas depend on I alone) through the tabulated curve, linear between entries
float pointFused(vec3 c) {
    c = max(c, vec3(0.0));
    float I = max(dot(c, LUMA), max3(c) * 0.5);
    int n = textureSize(FusedCurve, 0).x;
    float t = clamp((log2(max(I, 1.0e-12)) - fusedCurveU.x) * fusedCurveU.y, 0.0, float(n - 1));
    int i0 = min(int(t), n - 1);
    int i1 = min(i0 + 1, n - 1);
    return max(mix(texelFetch(FusedCurve, ivec2(i0, 0), 0).r, texelFetch(FusedCurve, ivec2(i1, 0), 0).r, t - float(i0)), 0.0);
}
#endif
float cbrtp(float x) { return pow(x, 1.0 / 3.0); }

vec4 bsplineWeights(float x) {
    float x2 = x * x, x3 = x2 * x;
    return vec4((1.0 - 3.0 * x + 3.0 * x2 - x3) / 6.0, (4.0 - 6.0 * x2 + 3.0 * x3) / 6.0,
                (1.0 + 3.0 * x + 3.0 * x2 - 3.0 * x3) / 6.0, x3 / 6.0);
}

// P311: how much an whyLow tap is a light, in units of the bound of the detail reference (m: its domain): its brightest
// channel from 2 x to 4 x the bound (a red, blue or pink LED: the clipped channel 3 .. 20 x while the luminance box mean stays
// near the bound, also for a ring thinner than the 2 x 2 box), or the geometric mean of that channel and the luminance from
// 1.4 x to 2.4 x (a near-white light clipped at 1.5 .. 3 x, a burst without the ultrashort frame). Bright saturated surfaces
// (a yellow object at a window, a hull under orange lamps: the max channel 1.2 .. 2.6 x after the colour matrix, the
// luminance 0.5 .. 1.5 x) stay below both.
float lightShare(vec3 lowTap, float m) {
    float mx = max(max3(lowTap), 0.0) * m;
    float y = max(dot(lowTap, LUMA), 0.0) * m;
    return max(smoothstep(2.0, 4.0, mx), smoothstep(1.4, 2.4, sqrt(mx * y)));
}

// P311 (owner, 2026-10-21, a pink LED ring: a crimson line along its inner side instead of a smooth rise into the light): the
// detail delta of a pixel beside a clipped light, measured against the side of its neighbourhood that is not the light.
// The B-spline reference spreads the light (its box means at the bound and the falloff boxes next to them) ~4 px into the
// dark side, and the cube root of that linear mix lifts the reference far more than the base lifts there (the fused luma
// mixes in the display domain, the light stops at its tone ceiling), so the delta dug a lobe 2-6 px wide below the dark
// side itself (host replay of the blue ring: L 0.59 between 0.63 and 0.69; 8 bit blue 202 in a 235 glow).
//  1. where the light lies: the light share of the whyLow taps in a disc of 4 whyLow px around the pixel (8 output px on
//     the 1x grid; the weight tapers to 0 over its last px, so a tap enters and leaves the disc at weight 0), its mass H,
//     first moment M and second moment S. side = |M|^4 / (H M^T S M) = (mean offset along M)^2 / (mean squared offset
//     along M): 1 when all the light lies on one side of the pixel at one distance (a rim), 0 when it surrounds the pixel
//     or lies on both sides (a dark line or hole in front of a light), in between for a light spread in depth.
//  2. the reference of the far side: the same 16 B-spline taps weighted by how far they lie behind the pixel, away from the
//     light along M (full weight from 1 whyLow px, none closer than 0.5 px), so neither the light nor its falloff boxes
//     are in it.
//  3. at a rim the negative delta is the pixel against that far side, never below 0 or the plain delta: the base's own
//     smooth rise into the light shows, dark texture of the dark side (darker than its far side) keeps its delta.
// Blended by side (0.3 .. 0.7) and faded in by the disc's light mass (0 .. 2: a light share summed over ~50 taps does not
// alias against the whyLow grid, as the 16-tap share of the B-spline footprint did - dashed rims beside lights at 2 x white
// and LED rows; review): every input is a smooth function of the tap values and of the pixel's position (no rounding, no tap
// set chosen by a threshold), and with no light in the footprint the delta is unchanged bit for bit. The plain delta stays
// where the light surrounds the pixel; dark objects up to ~7 px wide in front of a light keep their depth.
// Cost: the light share of the 16 footprint taps on every pixel of the bounded path (~200 ALU); ~66 extra texel reads only
// where the delta is negative and a light tap is in the footprint (0.1 - 0.6 % of the pixels of the test scenes).
// P349: brightAe > 0 - the same rule for light above white instead of a clipped light: a tap's share is its luminance x brightAe
// from 0.7 to 1.1 of white (the sun's glow by a window frame, 1 - 2 x white, under lightShare's 2 x). There the darkening goes
// down to the far side's level (a rung's edge against the glow stays as sharp as ArkCam's), and a pixel in a 1 px trench
// (trench 1: trenchAt - the merge's own ring at a hard edge, the line along the window's frame by the sun) takes none;
// a thin dark object with the light on both sides (side ~0) keeps its delta.
float rimDeltaBy(float delta, float y, float yRef, int bx, int by, float fx, float fy, float m, float brightAe, float trench) {
    ivec2 last = textureSize(WhyDetailRef, 0) - ivec2(1);
    vec2 mom = vec2(0.0);
    vec3 sec = vec3(0.0);
    float mass = 0.0;
    for (int dy = -3; dy <= 4; dy++) {
        for (int dx = -3; dx <= 4; dx++) {
            vec2 v = vec2(float(dx) - fx, float(dy) - fy);
            float k = 1.0 - smoothstep(3.0, 4.0, length(v));
            if (k <= 0.0) continue;
            vec3 tap = texelFetch(WhyLow, clamp(ivec2(bx + dx, by + dy), ivec2(0), last), 0).rgb;
            float h = k * (brightAe > 0.0 ? smoothstep(0.7, 1.1, max(dot(tap, LUMA), 0.0) * brightAe) : lightShare(tap, m));
            mass += h;
            mom += h * v;
            sec += h * vec3(v.x * v.x, v.x * v.y, v.y * v.y);
        }
    }
    float m2 = dot(mom, mom);
    float q = sec.x * mom.x * mom.x + 2.0 * sec.y * mom.x * mom.y + sec.z * mom.y * mom.y;
    float side = m2 * m2 / max(mass * q, 1e-20);
    vec2 n = mom / max(sqrt(m2), 1e-12);
    vec4 wx = bsplineWeights(fx), wy = bsplineWeights(fy);
    float sum = 0.0, wsum = 0.0;
    for (int dy = -1; dy <= 2; dy++) {
        for (int dx = -1; dx <= 2; dx++) {
            vec2 v = vec2(float(dx) - fx, float(dy) - fy);
            float w = wx[dx + 1] * wy[dy + 1] * smoothstep(-0.25, 0.25, -dot(v, n) - 0.75);
            sum += w * max(texelFetch(WhyDetailRef, clamp(ivec2(bx + dx, by + dy), ivec2(0), last), 0).r, 0.000001);
            wsum += w;
        }
    }
    float yFar = (sum + 0.001 * yRef) / (wsum + 0.001);
    float dFar = cbrtp(max(y, 0.000001)) - cbrtp(max(yFar, 0.000001));
    float rim = smoothstep(0.3, 0.7, side) * smoothstep(0.0, 2.0, mass);
    // P349: beside light above white the pixel's own luma dips below the far side too (the merge's 1 px ring at a hard edge,
    // sharpened): there the darkening goes down to the far side's level and no further - a sharp edge, no trench
    if (brightAe > 0.0) return mix(delta, max(delta, mix(cbrtp(max(yFar, 0.000001)) - cbrtp(max(yRef, 0.000001)), 0.0, trench)), rim);
    return mix(delta, max(delta, min(dFar, 0.0)), rim);
}

float lightRimDelta(float delta, float y, float yRef, float lightW, int bx, int by, float fx, float fy, float m) {
    return rimDeltaBy(delta, y, yRef, bx, by, fx, fy, m, 0.0, 0.0);
}

// P349: the full-size merge's luminance at a clamped pixel.
float sceneY(ivec2 p, ivec2 size) {
    return max(dot(sceneLinear(clamp(p, ivec2(0), size - ivec2(1)), size), LUMA), 0.0);
}

// P349: 1 where the merge itself has a 1 px trench - the pixel darker by 3.5 - 6 % than both its neighbours along a row or a
// column (its ring at a hard edge: the line along the window's frame by the sun). Read from the merge, not the sharpened luma:
// the sharpening deepens every dark band at an edge (a rung's lower edge, 2.5 % in the merge) into one.
float trenchAt(ivec2 xy, ivec2 size) {
    if (fullInputU != 1) return 0.0;
    float c = sceneY(xy, size);
    float h = min(sceneY(xy - ivec2(1, 0), size), sceneY(xy + ivec2(1, 0), size));
    float v = min(sceneY(xy - ivec2(0, 1), size), sceneY(xy + ivec2(0, 1), size));
    return smoothstep(0.035, 0.06, 1.0 - c / max(max(h, v), 0.000001));
}

// Output pixel x on a grid reduced by a box of f: centre (x + 0.5) / f - 0.5 as an integer base and a fraction.
void gridPos(int x, int f, out int base, out float frac) {
    int q = x / f;
    int r = x - q * f;
    float c = float(2 * r + 1 - f) / float(2 * f);
    if (c < 0.0) { base = q - 1; frac = c + 1.0; } else { base = q; frac = c; }
}

vec3 finiteTap(vec3 c) {
    if (isnan(c.r) || isinf(c.r)) c.r = 1.0;
    if (isnan(c.g) || isinf(c.g)) c.g = 1.0;
    if (isnan(c.b) || isinf(c.b)) c.b = 1.0;
    return c;
}

vec3 sanitize(vec3 c) {
    return finiteTap(max(c, vec3(0.0)));
}

#if TONE_OP == 1
// WhyCam 1.42 eval_acescg: Narkowicz ACES with the toe-driven b, e of the inverse below and its own pre-gain, a, d.
float toneAces(float v) {
    float x = max(v, 0.0) * acesU.x;
    float b = max(acesU.w * 0.75, 0.005);
    float e = max(0.14 - (acesU.w - 0.04) * 0.8, 0.03);
    return (x * (acesU.y * x + b)) / (x * (2.43 * x + acesU.z) + e);
}

// WhyCam 1.42 uchimura_curve (Gran Turismo): toe power c up to m, a linear section of slope a and length l, a shoulder to P.
float toneUchimura(float v) {
    float x = max(v, 0.0) * uchiAU.x;
    float P = uchiAU.z, a = uchiAU.w, m = uchiBU.x, l = uchiBU.y, c = uchiBU.z;
    float l0 = ((P - m) * l) / a;
    float s0 = m + l0;
    float s1 = m + a * l0;
    float c2 = (a * P) / (P - s1);
    float cp = -c2 / P;
    float w0 = 1.0 - smoothstep(0.0, m, x);
    float w2 = step(m + l0, x);
    float w1 = 1.0 - w0 - w2;
    float t = m * pow(max(x / m, 0.0), c) + uchiBU.w;
    float sh = P - (P - s1) * exp(cp * (x - s0));
    float li = m + a * (x - m);
    return (t * w0 + li * w1 + sh * w2) * uchiAU.y;
}

// The own curve at x 0..1: linear between its 256 samples.
float curveSample(int i) {
    return curveU[i >> 2][i & 3];
}
float toneCurve(float x) {
    float f = clamp(x, 0.0, 1.0) * 255.0;
    int i = min(int(f), 254);
    return mix(curveSample(i), curveSample(i + 1), f - float(i));
}
#endif

// Exact quadratic inverse of the kernel's ACES curve, pre-gain 1.5, a 2.51, d 0.59 [:987-1004].
float inverseAcescg(float y, float toe) {
    float b = max(toe * 0.75, 0.005);
    float e = max(0.14 - (toe - 0.04) * 0.8, 0.03);
    y = clamp(y, 0.0, 2.51 / 2.43 - 0.01);
    float A = 2.43 * y - 2.51;
    float B = 0.59 * y - b;
    float C = e * y;
    float disc = B * B - 4.0 * A * C;
    if (disc < 0.0) return 0.0;
    float x = (-B - sqrt(disc)) / (2.0 * A - 0.000001);
    return max(x / 1.5, 0.0);
}

vec3 toOklab(vec3 c) {
    float l = 0.4122214708 * c.r + 0.5363325363 * c.g + 0.0514459929 * c.b;
    float m = 0.2119034982 * c.r + 0.6806995451 * c.g + 0.1073969566 * c.b;
    float s = 0.0883024619 * c.r + 0.2817188376 * c.g + 0.6299787005 * c.b;
    l = cbrtp(max(l, 0.0)); m = cbrtp(max(m, 0.0)); s = cbrtp(max(s, 0.0));
    return vec3(0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
                1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
                0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s);
}

// P370 (B2): OKLab of a signed colour (outside sRGB after the camera matrix): the cube root keeps its sign (= toOklab for
// LMS >= 0).
vec3 toOklabSigned(vec3 c) {
    vec3 lms = vec3(0.4122214708 * c.r + 0.5363325363 * c.g + 0.0514459929 * c.b,
                    0.2119034982 * c.r + 0.6806995451 * c.g + 0.1073969566 * c.b,
                    0.0883024619 * c.r + 0.2817188376 * c.g + 0.6299787005 * c.b);
    lms = sign(lms) * vec3(cbrtp(abs(lms.x)), cbrtp(abs(lms.y)), cbrtp(abs(lms.z)));
    return vec3(0.2104542553 * lms.x + 0.7936177850 * lms.y - 0.0040720468 * lms.z,
                1.9779984951 * lms.x - 2.4285922050 * lms.y + 0.4505937099 * lms.z,
                0.0259040371 * lms.x + 0.7827717662 * lms.y - 0.8086757660 * lms.z);
}

// P370 (B2): the share of the signed colour's OKLab: 0 while no channel is negative and the clip changed nothing (a colour
// inside sRGB is the former one bit for bit), all of it from a channel at -1 % of the largest (a 600 nm LED at -3 % read
// 6 deg off with a ramp to 8 %). What the clip took off the clamped colour o counts as well: at an LED's rim on a lighter
// ground the signed mean lies inside sRGB while clipped taps went into o (a 1 - 2 px rim of the clipped hue). No luminance
// gate (review): a light changed its hue along its own falloff and a gradient stepped by 36 codes; the B-spline colour's
// shadow chroma noise stays within x1.005, the per-pixel viewfinder's within x1.05 (tools/check_ccm_gamut.py).
// P3_OUT 1: the channels of the P3 render (a colour inside P3 is kept by clampP3 already; its round trip, 1e-4 of the largest
// channel, is no clip).
float signedHueWeight(vec3 s, vec3 o) {
#if P3_OUT == 1
    vec3 g = SRGB_TO_P3 * s;
    vec3 d = SRGB_TO_P3 * (o - s);
#else
    vec3 g = s;
    vec3 d = o - s;
#endif
    float top = max(max(g.r, max(g.g, g.b)), 0.000001);
    float neg = max(-min(g.r, min(g.g, g.b)), max(max(d.r, max(d.g, d.b)) - 0.0001 * top, 0.0)) / top;
    return smoothstep(0.0, 0.01, neg);
}

// OKLab -> linear sRGB without the final clamp (the kernel clamps or floors at the call site).
vec3 fromOklab(vec3 lab);

#if P3_OUT == 1
// OKLab -> linear sRGB coordinates of a colour inside the P3 gamut [0, 1]: the counterpart of fromOklabInGamut for P3.
vec3 fromOklabInGamutP3(vec3 lab) {
    lab.x = clamp(lab.x, 0.0, 1.0);
    vec3 c = SRGB_TO_P3 * fromOklab(lab);
    if (all(greaterThanEqual(c, vec3(-0.0005))) && all(lessThanEqual(c, vec3(1.0005)))) return P3_TO_SRGB * clamp(c, 0.0, 1.0);
    float lo = 0.0, hi = 1.0;
    for (int i = 0; i < 10; i++) {
        float mid = 0.5 * (lo + hi);
        vec3 t = SRGB_TO_P3 * fromOklab(vec3(lab.x, lab.yz * mid));
        if (all(greaterThanEqual(t, vec3(-0.0005))) && all(lessThanEqual(t, vec3(1.0005)))) lo = mid; else hi = mid;
    }
    return P3_TO_SRGB * clamp(SRGB_TO_P3 * fromOklab(vec3(lab.x, lab.yz * lo)), 0.0, 1.0);
}

// P370 (B2, review): fromOklabInGamutP3 for a pixel that took the signed colour (w: its share): the chroma cut fades in over an
// overshoot of the P3 channels of 0.0005 .. 0.004 (fromOklabInGamutSoft); w 0 is fromOklabInGamutP3.
vec3 fromOklabInGamutP3Soft(vec3 lab, float w) {
    lab.x = clamp(lab.x, 0.0, 1.0);
    vec3 c = SRGB_TO_P3 * fromOklab(lab);
    float over = max(max(-min(c.r, min(c.g, c.b)), max(c.r, max(c.g, c.b)) - 1.0), 0.0);
    vec3 cut = fromOklabInGamutP3(lab);
    return mix(cut, mix(P3_TO_SRGB * clamp(c, 0.0, 1.0), cut, smoothstep(0.0005, 0.004, over)), w);
}

// The colour with its OKLab chroma reduced (L and hue kept) until no sRGB channel is negative - AgX's inset and log take
// non-negative input - and k = the chroma ratio in / out (1 for a colour inside sRGB, which is returned unchanged).
vec3 intoSrgbPositive(vec3 c, out float k) {
    k = 1.0;
    if (min(c.r, min(c.g, c.b)) >= 0.0) return c;
    vec3 lab = toOklab(c);
    float lo = 0.0, hi = 1.0;
    for (int i = 0; i < 12; i++) {
        float mid = 0.5 * (lo + hi);
        vec3 t = fromOklab(vec3(lab.x, lab.yz * mid));
        if (all(greaterThanEqual(t, vec3(0.0)))) lo = mid; else hi = mid;
    }
    k = 1.0 / max(lo, 0.05);
    return max(fromOklab(vec3(lab.x, lab.yz * lo)), vec3(0.0));
}
#endif

// OKLab -> linear sRGB inside [0, 1]: L is kept, the chroma is reduced (hue kept) until every channel fits, so a bright
// saturated colour does not lose its texture to a per-channel clamp (a positive detail delta lowers its chroma instead).
vec3 fromOklabInGamut(vec3 lab) {
    lab.x = clamp(lab.x, 0.0, 1.0);
    vec3 c = fromOklab(lab);
    if (all(greaterThanEqual(c, vec3(-0.0005))) && all(lessThanEqual(c, vec3(1.0005)))) return clamp(c, 0.0, 1.0);
    float lo = 0.0, hi = 1.0;
    for (int i = 0; i < 10; i++) {
        float mid = 0.5 * (lo + hi);
        vec3 t = fromOklab(vec3(lab.x, lab.yz * mid));
        if (all(greaterThanEqual(t, vec3(-0.0005))) && all(lessThanEqual(t, vec3(1.0005)))) lo = mid; else hi = mid;
    }
    return clamp(fromOklab(vec3(lab.x, lab.yz * lo)), 0.0, 1.0);
}

// P370 (B2, review): fromOklabInGamut for a pixel that took the signed colour (w: its share): the chroma cut fades in over an
// overshoot of 0.0005 .. 0.004 instead of switching on at 0.0005 (a channel at -0.00048 kept the full chroma, at -0.00054 0.85
// of it: a 36-code step along an LED's glow); w 0 is fromOklabInGamut.
vec3 fromOklabInGamutSoft(vec3 lab, float w) {
    lab.x = clamp(lab.x, 0.0, 1.0);
    vec3 c = fromOklab(lab);
    float over = max(max(-min(c.r, min(c.g, c.b)), max(c.r, max(c.g, c.b)) - 1.0), 0.0);
    vec3 cut = fromOklabInGamut(lab);
    return mix(cut, mix(clamp(c, 0.0, 1.0), cut, smoothstep(0.0005, 0.004, over)), w);
}

vec3 fromOklab(vec3 lab) {
    float l = max(lab.x + 0.3963377774 * lab.y + 0.2158037573 * lab.z, 0.0);
    float m = max(lab.x - 0.1055613458 * lab.y - 0.0638541728 * lab.z, 0.0);
    float s = max(lab.x - 0.0894841775 * lab.y - 1.2914855480 * lab.z, 0.0);
    l = l * l * l; m = m * m * m; s = s * s * s;
    return vec3(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
                -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
                -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s);
}

// Jed Smith tone scale of the kernel [:1102-1122], py = 0.5.
float agxTonescale(float x, float slope, float sp, float tp, float px) {
    const float py = 0.5;
    if (x >= px) {
        float sBase = slope * (1.0 - px) / (1.0 - py);
        float sDenom = pow(slope * (1.0 - px), -sp);
        float ss = pow(max((pow(sBase, sp) - 1.0) * sDenom, 0.00001), -1.0 / sp);
        float ms = slope * (x - px) / max(ss, 0.00001);
        float fs = ms / pow(1.0 + pow(max(ms, 0.0), sp), 1.0 / sp);
        return ss * fs + py;
    }
    float tBase = slope * px / py;
    float tDenom = pow(slope * px, -tp);
    float ts = pow(max((pow(tBase, tp) - 1.0) * tDenom, 0.00001), -1.0 / tp);
    float mr = slope * (px - x) / max(ts, 0.00001);
    float ft = mr / pow(1.0 + pow(max(mr, 0.0), tp), 1.0 / tp);
    return py - ts * ft;
}

#if COLOUR_LOOK == 1
// OKLab hue centres of the mixer bands: red, orange, yellow, green, aqua, blue, purple, magenta.
const float BAND_HUE[8] = float[8](29.0, 53.0, 110.0, 143.0, 195.0, 264.0, 294.0, 344.0);

// Skin: OKLab hue around 50 deg and a chroma / lightness ratio of real skin (0.03..0.32): exposure independent.
float skinWeight(vec3 lab) {
    float c = length(lab.yz);
    if (c < 0.000001) return 0.0;
    float r = c / max(lab.x, 0.0001);
    float d = mod(degrees(atan(lab.z, lab.y)) - 50.0 + 180.0, 360.0) - 180.0;
    return exp(-(d * d) / 400.0) * smoothstep(0.03, 0.06, r) * (1.0 - smoothstep(0.22, 0.32, r));
}

// ACES Reference Gamut Compression: the distance of each channel from the achromatic maximum, compressed above its
// threshold, so a colour outside the gamut (a negative channel: neon, LED) keeps its hue instead of being clipped.
vec3 gamutCompress(vec3 c) {
    float ach = max3(c);
    if (ach <= 0.0) return c;
    const vec3 thr = vec3(0.815, 0.803, 0.880);
    const vec3 lim = vec3(1.147, 1.264, 1.312);
    const float pw = 1.2;
    vec3 d = (vec3(ach) - c) / ach;
    vec3 sc = (lim - thr) / pow(pow((vec3(1.0) - thr) / (lim - thr), vec3(-pw)) - vec3(1.0), vec3(1.0 / pw));
    vec3 x = max(d - thr, vec3(0.0)) / sc;
    vec3 dc = mix(d, thr + sc * x / pow(vec3(1.0) + pow(x, vec3(pw)), vec3(1.0 / pw)), step(thr, d));
    return vec3(ach) - dc * ach;
}
#endif

#if HL_LANDING == 1
// P163 (owner, 2026-10-16 13:27, an LED screen: white speckles in its lavender letters): the colour a light lands on, from
// its neighbourhood instead of the pixel alone - the colour source at the pixel ({@code own}) and on a ring of eight taps
// ~12 output px around it, each tap weighted by how near the ceiling it is (the dark or saturated background beside a light
// does not tint it). The brightest dots of a light (an LED wall's diodes clipped even in the ultrashort frames) come out of
// the highlight recovery neutral; alone they landed on white amid their tinted neighbours.
vec3 landingColour(ivec2 xy, int fc, float clipCeiling, vec3 own) {
    ivec2 lastColour = textureSize(WhyColour, 0) - ivec2(1);
    vec2 c = (vec2(xy) + 0.5) / float(fc);
    float r = 12.0 / float(fc);
    vec3 sum = own * 0.25;   // the pixel itself counts a quarter of a ring tap: alone (no bright ring) it is the colour
    float wsum = 0.25;
    for (int i = 0; i < 8; i++) {
        float a = float(i) * 0.7853982;
        ivec2 p = clamp(ivec2(c + vec2(cos(a), sin(a)) * r), ivec2(0), lastColour);
        vec3 tap = max(texelFetch(WhyColour, p, 0).rgb, vec3(0.0));
        if (!(tap.r < 1.0e30 && tap.g < 1.0e30 && tap.b < 1.0e30)) continue;
        float w = smoothstep(0.5, 0.9, max3(tap) / clipCeiling);
        sum += tap * w;
        wsum += w;
    }
    return sum / wsum;
}
#endif

void main() {
#if RAW_SOURCE == 1
    // P365: the tone grid is the viewport (drawn on the screen at its origin, or into the tone target at 0, 0); the frame
    // positions of the neighbouring pixels from the derivatives of texCoord (taken here, in uniform control flow)
    ivec2 xy = ivec2(gl_FragCoord.xy) - vpOriginU;
    ivec2 size = vpSizeU;
    vec2 rawDx = dFdx(texCoord), rawDy = dFdy(texCoord);
#else
    ivec2 xy = ivec2(gl_FragCoord.xy);
    ivec2 size = textureSize(InputBuffer, 0);
#endif
    int f = fU > 0 ? fU : 2;
    int fc = colourFU > 0 ? colourFU : f;
    float ae = aeU > 0.0 ? aeU : 1.0;
    float clipCeiling = clipU > 0.0 ? clipU : 1.0;
    float toe = toeU > 0.0 ? toeU : 0.05;
    float gammaInv = gammaInvU > 0.0 ? gammaInvU : 1.0 / 2.2;
    float macro = macroU > 0.0 ? macroU : 1.1;
    vec4 agxA = agxAU.x > 0.0 ? agxAU : vec4(2.7, 1.35, 1.6, 1.0);
    vec4 agxB = agxBU == vec4(0.0) ? vec4(-8.5, 3.5, 0.3, 4.0) : agxBU;
    int look = int(agxB.w + 0.5);

    // === 1. B-spline colour, fused luma and Y_low [:1450-1497] ===
    vec3 orig = vec3(0.0);
    vec3 origS = vec3(0.0);     // P370: the same colour signed (finite taps, no clamp), for signedHueU
    float fused = 0.0;
    float yLow = 0.0;
    float yRef = 0.0;
    bool bounded = detailRefU == 1 && f != 1;
    float brightMax = 0.0;      // P349: the brightest whyLow tap of the footprint (luminance x ae: 1 = white)
    // P311: the light share of the reference's B-spline footprint, the whyLow position for lightRimDelta and the domain m of
    // WhyDetailRef (min(m * Y, 1); m = ae / sharpTone with WhyLumaS, else ae)
    float lightW = 0.0;
    int lbx = 0, lby = 0;
    float lfx = 0.0, lfy = 0.0;
    float refDomain = sharpU == 1 && sharpToneU > 0.0 ? ae / sharpToneU : ae;
    if (f == 1) {
        // same size as the low grid: the kernel's full-resolution bypass (no interpolation)
#if RAW_SOURCE == 1
        // P365: the pixel's linear colour from the RAW frame (the linear frame's value at xy)
        vec3 rawSigned = vfLinearSigned(texCoord);
        vec3 rawLin = max(rawSigned, vec3(0.0));
        origS = finiteTap(rawSigned);
#if P3_OUT == 1
        orig = clampP3(finiteTap(rawLin));
#else
        orig = sanitize(rawLin);
#endif
#else
#if P3_OUT == 1
        orig = clampP3(finiteTap(texelFetch(WhyColour, xy, 0).rgb));
#else
        orig = sanitize(texelFetch(WhyColour, xy, 0).rgb);
#endif
        origS = finiteTap(texelFetch(WhyColour, xy, 0).rgb);
#endif
#if POINT_FUSED == 1
        // P314 (review): the colour of the 3 x 3 neighbourhood at the pixel's own luminance - a mosaic's cell means take R, G and B
        // from sites 2 px apart (yellow / blue fringes at every sharp edge: chroma high-pass rms 1.09 -> 0.51, the photo 0.46; the
        // luma detail unchanged)
        {
#if RAW_SOURCE == 1
            // P365: the neighbours' colours from the RAW frame at their frame positions (the edge pixels' neighbours clamped into
            // the grid as before), with the pixel's lens-shading gain; the pixel itself is the linear value above
            vec3 rawGain = vfShading(texCoord);
            vec3 acc = vec3(0.0);
            vec3 accS = vec3(0.0);
            for (int dy = -1; dy <= 1; dy++)
                for (int dx = -1; dx <= 1; dx++) {
                    vec2 d = vec2(clamp(xy + ivec2(dx, dy), ivec2(0), size - ivec2(1)) - xy);
                    vec3 tap = rawLin;
                    if (d != vec2(0.0)) tap = color_transform * (vfColour(texCoord + d.x * rawDx + d.y * rawDy) * rawGain);
                    accS += finiteTap(d != vec2(0.0) ? tap : rawSigned);
#if P3_OUT == 1
                    acc += clampP3(finiteTap(tap));
#else
                    acc += sanitize(tap);
#endif
                }
#else
            ivec2 lastC = textureSize(WhyColour, 0) - ivec2(1);
            vec3 acc = vec3(0.0);
            vec3 accS = vec3(0.0);
            for (int dy = -1; dy <= 1; dy++)
                for (int dx = -1; dx <= 1; dx++) {
#if P3_OUT == 1
                    acc += clampP3(finiteTap(texelFetch(WhyColour, clamp(xy + ivec2(dx, dy), ivec2(0), lastC), 0).rgb));
#else
                    acc += sanitize(texelFetch(WhyColour, clamp(xy + ivec2(dx, dy), ivec2(0), lastC), 0).rgb);
#endif
                    accS += finiteTap(texelFetch(WhyColour, clamp(xy + ivec2(dx, dy), ivec2(0), lastC), 0).rgb);
                }
#endif
            acc *= 1.0 / 9.0;
            float yc = max(dot(orig, LUMA), 0.0), ya = dot(acc, LUMA);
            if (ya > 1.0e-6) orig = acc * (yc / ya);
            if (ya > 1.0e-6) origS = accS * (1.0 / 9.0) * (yc / ya);
        }
        orig *= inScaleU > 0.0 ? inScaleU : 1.0;
        origS *= inScaleU > 0.0 ? inScaleU : 1.0;
        fused = pointFused(orig * ae);
#else
        fused = max(texelFetch(WhyFused, xy, 0).r, 0.0);
#endif
#if RAW_SOURCE == 1
        yLow = max(dot(rawLin, LUMA), 0.000001);
#else
        yLow = max(dot(texelFetch(WhyLow, xy, 0).rgb, LUMA), 0.000001);
#endif
    } else {
        int bx, by;
        float fx, fy;
        gridPos(xy.x, f, bx, fx);
        gridPos(xy.y, f, by, fy);
        lbx = bx; lby = by; lfx = fx; lfy = fy;
        vec4 wx = bsplineWeights(fx), wy = bsplineWeights(fy);
        ivec2 lastLow = textureSize(WhyLow, 0) - ivec2(1);
        ivec2 lastFused = textureSize(WhyFused, 0) - ivec2(1);
        for (int dy = -1; dy <= 2; dy++) {
            for (int dx = -1; dx <= 2; dx++) {
                float w = wx[dx + 1] * wy[dy + 1];
                ivec2 p = ivec2(bx + dx, by + dy);
                fused += max(texelFetch(WhyFused, clamp(p, ivec2(0), lastFused), 0).r, 0.0) * w;
                vec3 lowTap = texelFetch(WhyLow, clamp(p, ivec2(0), lastLow), 0).rgb;
                yLow += max(dot(lowTap, LUMA), 0.000001) * w;
                if (bounded) {
                    yRef += max(texelFetch(WhyDetailRef, clamp(p, ivec2(0), lastLow), 0).r, 0.000001) * w;
                    lightW += lightShare(lowTap, refDomain) * w;
                }
                if (bounded) brightMax = max(brightMax, dot(max(lowTap, vec3(0.0)), LUMA) * ae);
            }
        }
        gridPos(xy.x, fc, bx, fx);
        gridPos(xy.y, fc, by, fy);
        wx = bsplineWeights(fx);
        wy = bsplineWeights(fy);
        ivec2 lastColour = textureSize(WhyColour, 0) - ivec2(1);
        for (int dy = -1; dy <= 2; dy++) {
            for (int dx = -1; dx <= 2; dx++) {
                ivec2 p = clamp(ivec2(bx + dx, by + dy), ivec2(0), lastColour);
                vec3 tap = texelFetch(WhyColour, p, 0).rgb;
#if P3_OUT == 1
                orig += (signedColourU != 0 ? finiteTap(tap) : clampP3(finiteTap(tap))) * (wx[dx + 1] * wy[dy + 1]);
#else
                orig += (signedColourU != 0 ? finiteTap(tap) : sanitize(tap)) * (wx[dx + 1] * wy[dy + 1]);
#endif
                origS += finiteTap(tap) * (wx[dx + 1] * wy[dy + 1]);
            }
        }
#if P3_OUT == 1
        if (signedColourU != 0) orig = clampP3(orig);
#else
        if (signedColourU != 0) orig = max(orig, vec3(0.0));
#endif
    }

    // === 2. ACES inversion and base gain [:1500-1509] ===
    float targetLin = inverseAcescg(pow(max(fused, 0.0), 2.2), toe);
    float origLuma = max(max(dot(orig, LUMA), max3(orig) * 0.5), 0.0001);
    float gain = targetLin / max(origLuma * ae, 0.0001);
    vec3 lin = orig * ae * gain;
    float linScale = ae * gain;  // P370: every scale lin takes before OKLab, for the signed colour
    // Saturation of the light (0 neutral .. 1 one channel): the shoulders below act on the max channel for low-saturation
    // colours (as the kernel) and on the luminance for saturated ones, so a saturated light keeps its tonal separation
    // instead of being pinned at its max channel (P11: the LED wall band of the X9 Ultra collapsed to one flat colour).
    float satLight = (max3(lin) - min(lin.r, min(lin.g, lin.b))) / max(max3(lin), 0.000001);
    float satW = smoothstep(0.50, 0.85, satLight);
    bool headroom = clipCeiling > 1.05 && headroomU != 0;
#if HEADROOM_WIDE == 1
    // P216: WhyCam 1.58's shoulder (before the AgX below) in place of the kernel's ceiling and roll-off
    bool wide = hrU > clipCeiling;
    if (wide) headroom = false;
#endif
    // Bento headroom ceiling: everything above 1 softly into <= 1.35 [:1515-1526]; only with real headroom (clipped data
    // or Bento), not for the WB headroom of unclipped saturated colour.
    if (headroom) {
        float mf = mix(max3(lin), dot(lin, LUMA), satW);
        if (mf > 1.0) {
            float ex = mf - 1.0;
            lin *= (1.0 + ex * 0.35 / (ex + 0.35)) / mf;
            linScale *= (1.0 + ex * 0.35 / (ex + 0.35)) / mf;
        }
    }

    // === 3. detail of the full-size merge (delta), applied after the tone ===
    float delta = 0.0;
    float lRef = 0.0;
    float sharpTone = sharpU == 1 && sharpToneU > 0.0 ? sharpToneU : 1.0;
    if (detailGainU != 0.0) {
        float yS = sharpU == 1 ? max(texelFetch(WhyLumaS, xy, 0).r, 0.0) : 0.0;
        float yFull = sharpU == 1 ? yS * sharpTone / ae : dot(sceneLinear(xy, size), LUMA);
        if (f == 1) {
            // kernel full-resolution mode: asymmetric clamp against dark trenches [:1538-1548]
            float lBase = cbrtp(max(dot(orig, LUMA) * ae, 0.000001));
            delta = clamp(cbrtp(max(yFull * ae, 0.000001)) - lBase, -min(0.035, lBase * 0.25), 0.050);
        } else if (bounded) {
            // bounded like the kernel's a3: min(m * Y, 1) (or its sharpened S) against the B-spline of its box mean
            lRef = cbrtp(max(yRef, 0.000001));
            float yD = sharpU == 1 ? yS : min(yFull * ae, 1.0);
            delta = cbrtp(max(yD, 0.000001)) - lRef;
            // P311: beside a clipped light, against the side of the neighbourhood that is not the light
            if (delta < 0.0 && lightW > 0.0) delta = lightRimDelta(delta, yD, yRef, lightW, lbx, lby, lfx, lfy, refDomain);
            // P349: and beside light above white (the window frame by the sun: a 1 px trench 2 - 3 px into the wall)
            if (delta < 0.0 && brightMax > 0.7) delta = rimDeltaBy(delta, yD, yRef, lbx, lby, lfx, lfy, refDomain, ae, trenchAt(xy, size));
            // P315 (owner, X8U LED fan rings: two parallel bright lines with a darker gap along the core): the guide is clamped
            // flat at 1 across a light's core while the reference takes in the dark beside it, so the positive delta ridged
            // just inside both rims and nowhere in the middle. No positive detail inside a light's footprint (HDR detail above
            // white stays with hrDetailU); the delta is unchanged bit for bit where no tap is a light.
            if (delta > 0.0) delta *= 1.0 - smoothstep(0.0, 1.0, lightW);
            // P349: nor on a pixel above white (the window's edge by the sun drew a bright tick at every rung's corner)
            if (delta > 0.0) delta *= 1.0 - smoothstep(0.8, 1.2, yFull * ae);
        } else {
            delta = cbrtp(max(yFull * ae, 0.000001)) - cbrtp(max(yLow * ae, 0.000001));
        }
        // P349 (owner, 2026-10-26, the sunset with the ladder: «грани у нас выглядят гораздо хуже чем у Арк камеры», «ступеньки
        // у Арк тоже выглядят отлично»): a gain above 1 (P340's detail by finish SNR, up to 1.7) lifts texture - the small
        // deltas - and leaves an edge's delta as it is. The whole delta x 1.7 overshot every edge the base (whyLow, B-spline)
        // has soft: a dark line with the merge's stair-steps along the dark side (rails and rungs against the sky, the window's
        // frame by the sun), where the merge itself is clean and ArkCam has none. Host replay: the lines gone, the rails'
        // cracks and the wall's grain kept. A gain up to 1 scales the delta as before.
        delta = detailGainU > 1.0 ? delta + (detailGainU - 1.0) * clamp(delta, -0.02, 0.02) : delta * detailGainU;
        // and next to light above white (luminance x AE over ~1: the sun's glow by the window's frame) the darkening delta stops
        // at the kernel's full-resolution floor (-0.035 L): there it is the edge itself, where ArkCam's bright side is clipped
        if (abs(clarityU) > 0.001) delta *= 1.0 + clarityU;
        if (flatProtectU > 0.001) {
            float smoothFactor = 1.0 - smoothstep(0.0, (flatProtectU / 10.0) * 0.015, abs(delta));
            delta = mix(delta, 0.0, smoothFactor);
        }
    }

#if FILM_SIM == 1
    // P369: the film's scene light - the colour source at the luminance of the delta's own base (whyLow's B-spline on the 2x
    // grid, where the colour is whyMid's: the band between them counted once), the full-size detail as the luminance ratio the
    // delta stands for (its guards included, before the compression scaling below: cbrt domain, ((l0 + delta) / l0)^3), x ae
    // x filmExpU (the AE's grey at the film's mid grey)
    vec3 filmLin;
    {
        vec3 c = max(orig, vec3(0.0));
        float cy = max(dot(c, LUMA), 0.000001);
        float baseY = fc == f ? cy : max(yLow, 0.000001);
        if (!(baseY < 1.0e30)) baseY = cy; // a non-finite whyLow tap (unsanitised, unlike whyMid's): the colour's own
        float yt = baseY;
        if (detailGainU != 0.0) {
            float l0 = lRef > 0.0 ? lRef : cbrtp(max((f == 1 ? cy : yLow) * ae, 0.000001));
            float rr = max(l0 + delta, 0.0) / l0;
            yt *= rr * rr * rr;
            if (bounded) {
                // the ratio is measured on the bounded reference (min(m Y, 1), with our P311 / P349 rim guards) while the base is
                // the unbounded mean: where the footprint holds light above the bound (or a light), a dark edge or a 2 px bar took
                // the bright side's energy (+7 .. +67 L* against the film model). There the pixel's own luminance: exact below
                // the bound, the full-size merge's (or the ratio's) at and above it
                float yP = sharpU == 1 ? max(texelFetch(WhyLumaS, xy, 0).r, 0.0) : min(dot(sceneLinear(xy, size), LUMA) * ae, 1.0);
                float yPix = yP / refDomain;
                float g = max(max(smoothstep(0.005, 0.03, 1.0 - yRef / max(yLow * refDomain, 0.000001)), smoothstep(0.5, 0.7, brightMax)),
                              step(0.000001, lightW));
                if (g > 0.0) {
                    float yTop = fullInputU == 1 ? max(dot(sceneLinear(xy, size), LUMA), yPix) : max(yt, yPix);
                    yt = mix(yt, mix(yPix, yTop, smoothstep(0.7, 1.0, yP)), g);
                }
            }
        }
        filmLin = c * (yt / cy) * (ae * filmExpU);
    }
#endif

    // === OKLab grading [:1599-1658] ===
#if COLOUR_LOOK == 1
    // Monochrome through a colour filter: the luminance of the filtered light (a neutral grey keeps its own).
    if (monoU != vec3(0.0)) lin = vec3(max(dot(lin, LUMA * monoU) / dot(LUMA, monoU), 0.0));
#endif
    vec3 lab = toOklab(lin);
    // P370 (B2): the colour as the matrix made it where it lies outside sRGB (monochrome keeps its grey); a branch, so a pixel
    // with no such channel is the former one bit for bit
    float signedW = 0.0;
#if COLOUR_LOOK == 1
    if (signedHueU != 0 && monoU == vec3(0.0)) {
#else
    if (signedHueU != 0) {
#endif
        signedW = signedHueWeight(origS, orig);
        if (signedW > 0.0) lab = mix(lab, toOklabSigned(origS * linScale), signedW);
    }
    if (abs(macro - 1.0) > 0.001) lab.x = max(0.40 + (lab.x - 0.40) * macro, 0.0001);
    float sat = max(length(lab.yz), 0.0001);
    float protect = max(1.0 - sat * 2.0, 0.0);
    float satMult = 1.0 + vibU.x * protect;
    if (abs(vibU.y) > 0.001 && lab.z < 0.0) satMult += vibU.y * clamp(-lab.z / sat, 0.0, 1.0) * protect;
    if (abs(vibU.z) > 0.001 && lab.y < 0.0) satMult += vibU.z * clamp(-lab.y / sat, 0.0, 1.0) * protect;
    lab.yz *= satMult;
    // P341 (owner, 2026-10-26: «нам нужно получить цвет у неба как у стока, при этом чтобы небо не темнило», «сделай чтобы было
    // лучше чем в стоке»): the AgX shoulder and the path to white take most of a pale bright sky's chroma (the 17:57 sunset at
    // OKLab-L 0.85: Lab chroma 8 / 22 against the stock camera's 25 / 42 at the same hue and a lower L). The light tones get
    // vibrance before the tone, L unchanged: x (1 + 1.6 vibHlU) for a pale colour, falling to none at chroma 0.10 - a sky that
    // has its colour already stays (the 3.7x sunset's glow at chroma 34 went to 66, red-orange, under a plain x 2.2; LMC 9.6 /
    // ArkCam 33 - 38), none on a neutral (its hue is noise below chroma 0.004). Host replay: the 1x sky 8 / 22 -> ~20 / ~33,
    // the 3.7x glow 34 -> ~42.
    if (vibHlU > 0.0) {
        float c0 = length(lab.yz);
        lab.yz *= 1.0 + 1.6 * vibHlU * smoothstep(0.45, 0.75, lab.x) * smoothstep(0.002, 0.008, c0) * max(1.0 - c0 / 0.10, 0.0);
    }
#if COLOUR_LOOK == 1
    float skinW = 0.0;
    if (length(lab.yz) > 0.000001) {
        skinW = skinWeight(lab);
        float keep = 1.0 - skinU.x * skinW;
        // skin protection: positive vibrance reaches skin only with (1 - protection)
        if (satMult > 1.0 && keep < 1.0) lab.yz *= (1.0 + (satMult - 1.0) * keep) / satMult;
        if (vibLU != vec2(0.0)) {
            float mSh = (1.0 - smoothstep(0.25, 0.45, lab.x)) * smoothstep(0.05, 0.12, lab.x);
            float mHl = smoothstep(0.6, 0.8, lab.x) * (1.0 - smoothstep(0.88, 0.98, lab.x));
            float g = (vibLU.x * mSh + vibLU.y * mHl) * max(1.0 - length(lab.yz) * 2.0, 0.0);
            if (g > 0.0) g *= keep;
            lab.yz *= max(1.0 + g, 0.0);
        }
        if (mixOnU == 1) {
            float c = length(lab.yz);
            float h = mod(degrees(atan(lab.z, lab.y)), 360.0);
            int i = 7;
            for (int k = 0; k < 8; k++) if (h >= BAND_HUE[k]) i = k;
            int j = i == 7 ? 0 : i + 1;
            float c0 = BAND_HUE[i];
            float c1 = j == 0 ? BAND_HUE[0] + 360.0 : BAND_HUE[j];
            float hh = h < c0 ? h + 360.0 : h;
            float wi = 0.5 + 0.5 * cos(3.14159265 * clamp((hh - c0) / (c1 - c0), 0.0, 1.0));
            float wj = 1.0 - wi;
            float cw = min(c / 0.12, 1.0);
            float ang = radians(23.0) * (wi * mixU[i * 3] + wj * mixU[j * 3]) * cw;
            lab.yz = mat2(cos(ang), sin(ang), -sin(ang), cos(ang)) * lab.yz;
            float sg = wi * mixU[i * 3 + 1] + wj * mixU[j * 3 + 1];
            if (sg > 0.0) sg *= keep;
            lab.yz *= max(1.0 + sg, 0.0);
            lab.x = max(lab.x + (wi * mixU[i * 3 + 2] + wj * mixU[j * 3 + 2]) * 0.15 * cw * sqrt(max(lab.x, 0.0)), 0.0001);
        }
        if (skinU.y != 0.0 || skinU.z != 0.0) {
            float w = skinWeight(lab);
            if (w > 0.0) {
                float c = length(lab.yz);
                float d = mod(atan(lab.z, lab.y) - radians(52.0) + 3.14159265, 6.28318531) - 3.14159265;
                float hn = radians(52.0) + d * (1.0 - skinU.z * w) + radians(12.0) * skinU.y * w;
                lab.yz = c * vec2(cos(hn), sin(hn));
            }
        }
    }
#endif
    if (chromaDnU > 0.001) {
        float lift = targetLin / max(origLuma * ae, 0.0001);
        if (lift > 4.0) lab.yz *= max(1.0 - chromaDnU * clamp((lift - 4.0) * 0.1, 0.0, 1.0), 0.0);
    }
    float inChroma = length(lab.yz);
    vec2 hueDir = inChroma > 0.000001 ? lab.yz / inChroma : vec2(0.0);
#if P3_OUT == 1
    vec3 scene = clampP3(fromOklab(lab));
#else
    vec3 scene = max(fromOklab(lab), vec3(0.0));
#endif
#if COLOUR_LOOK == 1
    if (gamutU > 0.0) {
#if P3_OUT == 1
        vec3 compressed = clampP3(P3_TO_SRGB * gamutCompress(SRGB_TO_P3 * fromOklab(lab)));
#else
        vec3 compressed = max(gamutCompress(fromOklab(lab)), vec3(0.0));
#endif
        scene = mix(scene, compressed, gamutU);
    }
#endif
#if HEADROOM_WIDE == 1
    if (wide) {
        // P216: WhyCam 1.58 (libopenhdr_engine, «RAWBracket's max-channel Reinhard shoulder»): above knee x the top of the
        // tonemapper softly into <= (knee + room) x top, the same measure as the kernel's shoulders (P11). Knee 0.5 (WhyCam
        // 0.6): the bright end starts rising towards white earlier, so a crane's clipped lines do not jump to 255 (owner: «нужно
        // чтобы не было резких белых пятен»); room 0.4: the limit lies a little above the AgX white, so the sun's disc climbs
        // steadily into a white core - with 0.3 its rim stood on a 251 plateau and the clipped core sat in it as a ring
        // (owner: «Солнце должно быть мягким без каких-либо ореолов»).
#if TONE_OP == 1
        float top = 4.0;
#else
        float top = 0.18 * exp2(agxB.y + max(agxB.z, 0.0));
#endif
        float knee = top * (hrKneeU > 0.0 ? hrKneeU : 0.5), room = top * (hrRoomU > 0.0 ? hrRoomU : 0.4);
        float mw = mix(max3(scene), dot(scene, LUMA), satW);
        if (mw > knee) {
            float ex = mw - knee;
            scene *= (knee + ex * room / (ex + room)) / mw;
        }
    }
#endif
    // Bento roll-off of the linear maximum above 0.5 [:1673-1681] (same shoulder measure as the ceiling)
    if (headroom) {
        float ml = mix(max3(scene), dot(scene, LUMA), satW);
        if (ml > 0.5) {
            float ex = ml - 0.5;
            float room = max((clipCeiling - 0.5) * 0.35, 1.5);
            scene *= (0.5 + ex / (1.0 + ex / room)) / ml;
        }
    }
    // Path to white: saturated light whose max channel enters the AgX shoulder fades towards its luminance, as a bright
    // coloured light does on film / in HDR+ (the input here is linear camera RGB, not an already desaturated render).
    if (hlWhiteU > 0.0) {
        float mx = max3(scene);
        float yS = dot(scene, LUMA);
#if TONE_OP == 1
        float w = hlWhiteU * satW * smoothstep(0.6, 2.4, mx);
#else
        // P310 (owner, LED fans: the rings' and the bar's colour went white): only where the light's brightest channel reaches
        // the AgX white (0.18 x 2^(maxEv - min(EV, 0)), where its tone curve ends: lg = 1 below); the scene gate 0.6 .. 2.4 above bleached every bright
        // coloured light whose colour the curve still holds (host replay: the bar's body keeps its tint like WhyCam's, its LED
        // dots and the rings' cores at the white still go to white, so a light stays the brightest thing around it)
        // P311 (owner, pink LED fan ring: a white core, then a hard edge and a saturated band on its outer side): full only at 2 x the white. Ending at
        // the white (0.8 .. 0.995: 0.3 EV) the ramp lay within one pixel of a ring's falloff (~0.3 EV per px): the other channels,
        // lifted to 15 % of the brightest one inside, dropped 3.5 EV there (8 bit R of a blue ring 180 -> 96 in 2 px after a
        // plateau). Over 1.3 EV they fall with the light; below 0.8 x the white nothing changes, from 2 x up it is the former core.
        float agxWhite = 0.18 * exp2(agxB.y + max(-agxB.z, 0.0));
        float w = hlWhiteU * satW * smoothstep(0.8 * agxWhite, 2.0 * agxWhite, mx);
#endif
#if HL_LANDING == 1
        // P154 (owner: «белый цвет никогда не уходил в серый»): towards the white of its brightest channel, not down to its
        // luminance (a grey of the same Y)
        scene = mix(scene, vec3(mx), clamp(w, 0.0, 0.9));
#else
        scene = mix(scene, vec3(yS), clamp(w, 0.0, 0.9));
#endif
    }

    // === AgX (Blender 4 / Kraken matrices of the kernel) [:1079-1094, :1687-1730] ===
#if P3_OUT == 1
    float chromaBack;
    scene = intoSrgbPositive(scene, chromaBack);
#endif
    vec3 ins = max(vec3(0.842479062253094 * scene.r + 0.0784335999999992 * scene.g + 0.0792237451477643 * scene.b,
                        0.0423282422610123 * scene.r + 0.8784686364697720 * scene.g + 0.0791661274605434 * scene.b,
                        0.0423756549057051 * scene.r + 0.0784336000000000 * scene.g + 0.8791429737931040 * scene.b), vec3(0.0));
#if TONE_OP == 1
    vec3 graded;
    if (toneOpU == 3) {
        // the own curve on -5..+5 EV around the grey, display values -> linear (2.4) -> the AgX outset, as AgX itself
        vec3 nx = clamp(vec3(0.5) + log2(max(ins, vec3(0.0000001)) / 0.18) * 0.1, 0.0, 1.0);
        vec3 curDisp = vec3(toneCurve(nx.r), toneCurve(nx.g), toneCurve(nx.b));
        vec3 dispLinC = pow(max(curDisp, vec3(0.0)), vec3(2.4));
        graded = vec3(1.19687902425835 * dispLinC.r - 0.09802088114013 * dispLinC.g - 0.09902975707567 * dispLinC.b,
                      -0.05289685400450 * dispLinC.r + 1.15190312998791 * dispLinC.g - 0.09896117934484 * dispLinC.b,
                      -0.05297163539640 * dispLinC.r - 0.09804337634422 * dispLinC.g + 1.15107253721836 * dispLinC.b);
    } else {
        // ACES / Uchimura per channel on the inset, a linear display value, then the outset (WhyCam: no 2.4 here)
        vec3 curOp = toneOpU == 2 ? vec3(toneUchimura(ins.r), toneUchimura(ins.g), toneUchimura(ins.b))
                                  : vec3(toneAces(ins.r), toneAces(ins.g), toneAces(ins.b));
        curOp = clamp(curOp, 0.0, 1.0);
        graded = vec3(1.19687902425835 * curOp.r - 0.09802088114013 * curOp.g - 0.09902975707567 * curOp.b,
                      -0.05289685400450 * curOp.r + 1.15190312998791 * curOp.g - 0.09896117934484 * curOp.b,
                      -0.05297163539640 * curOp.r - 0.09804337634422 * curOp.g + 1.15107253721836 * curOp.b);
    }
#else
    float minEv = agxB.x, maxEv = agxB.y, ev = agxB.z;
    float range = max(maxEv + max(ev, 0.0) - minEv, 1.0);
    float px = clamp(-minEv / range, 0.05, 0.95);
    vec3 lg = clamp((log2(max(ins, vec3(0.0000001)) / 0.18) + ev - minEv) / range, 0.0, 1.0);
    vec3 cur = vec3(agxTonescale(lg.r, agxA.x, agxA.y, agxA.z, px), agxTonescale(lg.g, agxA.x, agxA.y, agxA.z, px),
                    agxTonescale(lg.b, agxA.x, agxA.y, agxA.z, px));
    if (look == 3) {
        cur.r = pow(max(cur.r, 0.0), 0.96);
        cur.b = pow(max(cur.b, 0.0), 1.04);
    }
    vec3 dispLin = pow(max(cur, vec3(0.0)), vec3(2.4));
    vec3 graded = vec3(1.19687902425835 * dispLin.r - 0.09802088114013 * dispLin.g - 0.09902975707567 * dispLin.b,
                       -0.05289685400450 * dispLin.r + 1.15190312998791 * dispLin.g - 0.09896117934484 * dispLin.b,
                       -0.05297163539640 * dispLin.r - 0.09804337634422 * dispLin.g + 1.15107253721836 * dispLin.b);
    if (abs(agxA.w - 1.0) > 0.001) {
        float ol = dot(graded, LUMA);
#if COLOUR_LOOK == 1
        // P134 «Защита кожи»: the AgX saturation above 1 reaches skin only with (1 - protection), as vibrance does
        float agxSat = agxA.w > 1.0 ? 1.0 + (agxA.w - 1.0) * (1.0 - skinU.x * skinW) : agxA.w;
        graded = vec3(ol) + (graded - vec3(ol)) * agxSat;
#else
        graded = vec3(ol) + (graded - vec3(ol)) * agxA.w;
#endif
    }
#endif
    // In gamut by chroma, not per channel: a channel of a saturated light over 1 would otherwise stop its brightening.
#if P3_OUT == 1
    vec3 gradedLab = toOklab(max(graded, vec3(0.0)));
    gradedLab.yz *= chromaBack;
    graded = signedW > 0.0 ? fromOklabInGamutP3Soft(gradedLab, signedW) : fromOklabInGamutP3(gradedLab);
#else
    graded = signedW > 0.0 ? fromOklabInGamutSoft(toOklab(max(graded, vec3(0.0))), signedW) : fromOklabInGamut(toOklab(max(graded, vec3(0.0))));
#endif

    // === hue lock and post-tone detail [:1763-1802] ===
    vec3 post = toOklab(graded);
#if COLOUR_LOOK == 1
    if (look != 3 && inChroma > 0.000001 && hueLockU != 2) {
        vec2 locked = length(post.yz) * hueDir;
        post.yz = hueLockU == 1 ? mix(post.yz, locked, clamp(-hueDir.y, 0.0, 1.0)) : locked;
    }
    // grading: OKLab offsets of shadows / mid-tones / highlights by display lightness (zones around 0.5 - balance / 4)
    if (gradeAU != vec4(0.0) || gradeBU.xy != vec2(0.0)) {
        float m = 0.5 - 0.25 * gradeBU.z;
        float wd = 0.15 + 0.5 * gradeBU.w;
        float wSh = 1.0 - smoothstep(m - wd, m, post.x);
        float wHl = smoothstep(m, m + wd, post.x);
        float wMid = max(1.0 - wSh - wHl, 0.0);
        post.yz += (gradeAU.xy * wSh + gradeAU.zw * wMid + gradeBU.xy * wHl) * (1.0 - skinU.x * skinW);
    }
#else
    if (look != 3 && inChroma > 0.000001) post.yz = length(post.yz) * hueDir;
#endif
    if (lRef > 0.0) {
        // Where the tone compresses (Bento roll-off, fusion darkening, AgX shoulder: L_base below the cube-root
        // reference) the delta is scaled down with it; lifted shadows and mid-tones (ratio >= 1) keep the full delta.
        float compression = min(1.0, post.x / (lRef * (sharpTone != 1.0 ? cbrtp(sharpTone) : 1.0)));
        // P341 (owner, 2026-10-26: «на 31148 есть ступенька, а на новой нет»): a darkening delta scales with the compression, not
        // its square. A ladder rung 10 x darker than the sun's glow around it (2 - 4 x white, compressed ~0.3) kept 9 % of its
        // detail and read as glow; the fused base carries it only at half size. Brightening detail keeps the square (no
        // white rims around lights).
        delta *= delta < 0.0 ? compression : compression * compression;
    }
    float lBefore = post.x;
    post.x = clamp(post.x + delta * (1.0 - smoothstep(0.75, 1.0, post.x) * 0.5), 0.0001, 1.0);
#if HEADROOM_WIDE == 1
    // P216: the bounded reference (min(ae Y, 1)) gives no detail above white, and the colour / fusion grid is the 2 x 2 box:
    // with real headroom a bright pixel keeps hrDetailU of its log contrast against that grid (L ~ Y^(1/3)), only where it
    // and its neighbourhood are both above white (no trench along a dark edge next to a light), limited to -0.03 .. +0.12 L
    // (asymmetric like the kernel's full-resolution delta: a darker side of 0.12 drew 1 px trenches beside the clipped
    // highlight lines of a crane, the owner wants the highlights soft), never pushing a highlight onto white.
    if (wide && hrDetailU > 0.0) {
        float yF = dot(sceneLinear(xy, size), LUMA) * ae;
        float yL = yLow * ae;
        float gate = smoothstep(0.8, 1.6, min(yF, yL));
        if (gate > 0.0) {
            float ratio = max(yF, 0.000001) / max(yL, 0.000001);
            float dl = hrDetailU / 3.0 * log(ratio);
            // a pixel much darker than its neighbourhood is the rim of a light (the sun's disc beside its clipped core, the
            // sky beside a crane's clipped line), not texture: no trench there - it drew a ring inside the sun
            if (dl < 0.0) dl *= smoothstep(0.55, 0.85, ratio);
            float dHL = gate * clamp(dl, -0.03, 0.12);
            if (dHL > 0.0) dHL *= 1.0 - smoothstep(0.85, 1.0, post.x);
            post.x = clamp(post.x + dHL * post.x, 0.0001, 1.0);
        }
    }
#endif
    // The kernel keeps a, b while the delta moves L. At a strong edge the colour is the B-spline mix of both sides, and
    // on the dark side the delta pulls L far down: the same a, b at a low L read as a saturated purple/green line. Scaling
    // a, b with L there (a uniform linear scale of the colour) keeps the hue and the relative chroma instead.
    if (deltaChromaU > 0.0 && post.x < lBefore) post.yz *= mix(1.0, post.x / max(lBefore, 0.0001), clamp(deltaChromaU, 0.0, 1.0));
#if P3_OUT == 1
    graded = signedW > 0.0 ? fromOklabInGamutP3Soft(post, signedW) : fromOklabInGamutP3(post);
#else
    graded = signedW > 0.0 ? fromOklabInGamutSoft(post, signedW) : fromOklabInGamut(post);
#endif

    // === display: power 1/gamma, film toe [:1805-1825], dither [:1836-1843] ===
#if P3_OUT == 1
    // P3 values, display-encoded; the film toe scales them by the factor the sRGB render takes (same linear scale).
    vec3 outRgb = pow(max(SRGB_TO_P3 * graded, vec3(0.000001)), vec3(gammaInv));
    if (filmToeU > 0.0) {
        float ls = max(dot(pow(max(graded, vec3(0.000001)), vec3(gammaInv)), LUMA), 0.0001);
        if (ls < 0.15) outRgb *= (pow(max(ls / 0.15, 0.000001), 1.0 + filmToeU) * 0.15) / ls;
    }
#else
    vec3 outRgb = pow(max(graded, vec3(0.000001)), vec3(gammaInv));
    if (filmToeU > 0.0) {
        float ls = max(dot(outRgb, LUMA), 0.0001);
        if (ls < 0.15) outRgb *= (pow(max(ls / 0.15, 0.000001), 1.0 + filmToeU) * 0.15) / ls;
    }
#endif
    // P154 highlight landing (owner, X300 Ultra backlit window: the blown light rendered as a flat 0.92 grey whose gradient
    // stopped at once at the plateau; a first per-pixel landing on white made the curtain folds between the white blobs
    // read as grey stripes: «очень резкие переходы с уходом в серый»). Only with clipped data in the frame (headroomU):
    // the landing follows the BLURRED share of the ceiling (WhyHl, a gaussian of ~1.2 % of the frame width), so a blown
    // light lifts its bright surroundings gradually and fades into white; a shoulder 1 - (1 - t)^2 from 1 - hlSoftU of
    // the ceiling (steepest at its start, zero slope at white); pixels that are not bright themselves (below ~0.15 - 0.45
    // of the ceiling) are left alone, so dark objects in front of a light get no glow. It lands on the pixel's own colour
    // at full brightness (max channel 1: a coloured light keeps its hue), a near-neutral one (saturation below ~0.1) on
    // pure white.
#if HEADROOM_WIDE == 1
    if (wide) clipCeiling = hrU;   // P216: blown light lands on the real ceiling (a window at 4 - 8 x white is not blown)
#endif
#if HL_LANDING == 1
    if (headroomU != 0 && hlSoftU > 0.0) {
        float rBlur = texture(WhyHl, (vec2(xy) + 0.5) / vec2(size)).r;
        float t = clamp((rBlur - (1.0 - hlSoftU)) / hlSoftU, 0.0, 1.0);
        float landing = (1.0 - (1.0 - t) * (1.0 - t)) * smoothstep(0.15, 0.45, max3(orig) / clipCeiling);
        if (landing > 0.0) {
            // P159: a coloured light lands on ITS colour at full brightness, taken from the colour source before the tone
            // (the shoulder had bleached a blown orange to pale pink: the inside of a cone on an LED screen); a near-neutral
            // one (linear saturation below ~0.1) on pure white. P163: the light's colour from its neighbourhood.
            vec3 sceneTone = landingColour(xy, fc, clipCeiling, max(orig, vec3(0.0)));
            vec3 sN = clamp(sceneTone / max(max3(sceneTone), 0.000001), 0.0, 1.0);
            float satS = 1.0 - min(sN.r, min(sN.g, sN.b));
#if P3_OUT == 1
            vec3 sTop = pow(clamp(SRGB_TO_P3 * sN, 0.0, 1.0), vec3(gammaInv));
#else
            vec3 sTop = pow(sN, vec3(gammaInv));
#endif
            sTop /= max(max3(sTop), 0.000001);
            vec3 top = mix(sTop, vec3(1.0), 1.0 - smoothstep(0.08, 0.30, satS));
            outRgb = mix(outRgb, top, landing);
        }
    }
#endif
#if SUN_BLOOM == 1
    // P349 (owner, 2026-10-26: «у нас солнце как круг выглядит на новой версии, сделай схоже с Арк камерой»): a core with no
    // data at all (the setting sun's disc: R, G, B clipped in the ultrashort too) stood as a flat white disc with a hard rim in
    // its orange glow (2 - 3 x white, kept below white by the Bento headroom), where ArkCam, clipping all of it, shows a soft
    // warm-white area. Bright pixels round such a core move by distance towards warm white, white near the core; dark ones
    // (the ladder's rungs in front of the sun) keep their tone. Host replay of the owner's 17:57 sunset: the disc's rim gone,
    // the rungs kept; the moon (a core of ~300 px) and recovered windows (no fully clipped core) get none.
    {
        float m = texture(SunBloom, (vec2(xy) + 0.5) / vec2(size) * sunGridU).r;
        float b = smoothstep(0.4, 0.9, m);
        if (b > 0.0 && sunBloomU > 0.0) {
            vec3 lin = pow(clamp(outRgb, 0.0, 1.0), vec3(1.0 / gammaInv));
            float gate = smoothstep(0.55, 0.85, dot(lin, LUMA));
            vec3 top = mix(vec3(1.0, 0.93, 0.78), vec3(1.0), clamp(b * 1.5 - 0.5, 0.0, 1.0));
            top = max(top, lin / max(max3(lin), 0.000001));
            lin += (top - lin) * (b * gate * sunBloomU);
            outRgb = pow(lin, vec3(gammaInv));
        }
    }
#endif
#if FILM_LUT == 1
    {
        // trilinear lookup of the display value (the encoding a .cube look is made for), then the strength
        vec3 c = clamp((clamp(outRgb, 0.0, 1.0) - lutMinU) * lutScaleU, 0.0, 1.0);
        vec3 l = texture(FilmLut, c * ((lutU.y - 1.0) / lutU.y) + 0.5 / lutU.y).rgb;
        outRgb = mix(outRgb, l, clamp(lutU.x, 0.0, 1.0));
    }
#endif
#if FILM_SIM == 1
    {
        // P369: the film's print of the scene light (trilinear on the per-channel log grid, texel centres as FILM_LUT's); at
        // strength 1 the print alone (nothing of our tone, not even a NaN of it, reaches the photo), below it mixed with ours
        vec3 u = clamp((log2(max(filmLin, vec3(1.0e-7)) / 0.184) - filmLogU.x) / filmLogU.y, 0.0, 1.0);
        vec3 film = texture(FilmLut, u * ((lutU.y - 1.0) / lutU.y) + 0.5 / lutU.y).rgb;
#if P3_OUT == 1
        film = filmEnc(SRGB_TO_P3 * filmDec(film));
#endif
        outRgb = lutU.x >= 1.0 ? film : mix(outRgb, film, clamp(lutU.x, 0.0, 1.0));
    }
#endif
    if (ditherU == 1) {
        float d1 = fract(0.06711056 * float(xy.x) + 0.00583715 * float(xy.y));
        outRgb += (fract(52.9829189 * d1) - 0.5) / 255.0;
    }
    Output = vec4(clamp(outRgb, 0.0, 1.0), 1.0);
}
