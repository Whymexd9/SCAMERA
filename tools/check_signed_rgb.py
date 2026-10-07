#!/usr/bin/env python3
"""Signed merge RGB through the LMC hybrid import and the stages after it, on the SHIPPED shaders: no mean bias.

The hybrid merge keeps its noise signed and clips once. A per-pixel max(0) of a channel near zero lifts its mean by up to
half its noise: on the OPPO Find X8 Ultra (2026-10-07, ISO 489) the red of a dark teal curtain came out x1.5 along the
frame edge, where fewer donor frames overlap and the noise is twice as high, and the ARK shadow lift showed it as a red
band and red mottling. The scene here: a dark field with a low red channel (R ~ its noise, a third of the samples
negative), the first columns twice as noisy (the frame edge), a few non-finite samples.
Checks:
- vivohdr/nicergb (hlModeU 1, as VivoNiceRgb on a hybrid shot, signedU 1): the per-column mean colour of the edge and of
  the inside equals the input's (white balance only, |bias| < 0.5 %); with signedU 0 (the former clamp, still the SCAM HDR
  route) the edge red is lifted by > 20 % (the check sees the bias); where no channel is negative both modes give the same
  output, bit for bit; non-finite samples come out as 0;
- chromadn/despeckle: the signed field keeps its mean colour (|bias| < 2 % of the red), output finite;
- chromadn/down (NLM colour stage): the box mean of the signed values (no lift), as numpy;
- ark/low colour mode: the box mean of the signed values (no lift); DETAIL_REF 1 keeps its per-pixel clamp, the box mean
  of min(ae * Y(max(rgb, 0)), 1), as ark/combine's detail luminance;
- ark/combine B-spline colour: signedColourU 0 (SCAM HDR, the hybrid after the LMC denoise) is the former per-tap clamp,
  bit for bit, also for the negative taps of a colour outside sRGB; signedColourU 1 (signed hybrid input) clamps after the
  interpolation (the red of a dark teal field is not lifted); non-negative taps give the same output in both modes;
- the NLM denoise engine (NiceDenoise.runNlm: chromadn/luma, down, filter, nlm, down4, coarse, apply as the Java sets them,
  despeckle off; signedU 1 = signed hybrid input): non-negative input gives the output of signedU 0 bit for bit (luma and
  chroma on, luma alone, chroma alone); on the signed dark field the luminance is not lifted by a clamp per pixel (chroma
  alone: |Y bias| < 0.5 %, the clamp lifts the noisy edge by > 3 %), with the chroma denoise off the colour is not lifted
  either (|red bias| at the edge < 5 % and at most 0.6 x the clamp's, which lifts it by > 20 %), with both on the edge red
  stays within 2 % and Y within 3 %; output finite.
Usage: check_signed_rgb.py [--shaders DIR] [--report]
"""
from pathlib import Path
import re
import sys
import numpy as np
import moderngl

ROOT = Path(__file__).resolve().parents[1]
SH = ROOT / 'app/src/main/assets/shaders'
if '--shaders' in sys.argv:
    SH = Path(sys.argv[sys.argv.index('--shaders') + 1])
REPORT = '--report' in sys.argv

try:
    ctx = moderngl.create_standalone_context(backend='egl', require=430)
except Exception:
    ctx = moderngl.create_standalone_context(require=430)
VS = '#version 430\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.0-1.0,0,1);}'

WP = np.array([0.4248047, 1.0, 0.6044922], np.float32)   # OPPO X8U camera 2 white point of the shot
W, H = 384, 256
EDGE = 4                                                 # noisier columns (the frame edge)
IDENT = (1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)


def source(name, defines=None):
    out = ['#version 430']
    for line in (SH / name).read_text(encoding='utf-8').splitlines():
        m = re.match(r'\s*#import\s+(\S+)', line)
        if m:
            out.append((SH / 'utils' / ('import_' + m.group(1) + '.glsl')).read_text(encoding='utf-8'))
            continue
        d = re.match(r'\s*#define\s+(\w+)\s+', line)
        if d and defines and d.group(1) in defines:
            line = '#define %s %s' % (d.group(1), defines[d.group(1)])
        out.append(line)
    return '\n'.join(out)


def program(name, defines=None):
    return ctx.program(vertex_shader=VS, fragment_shader=source(name, defines))


def texture(a, dtype='f4', linear=False):
    a = np.ascontiguousarray(a)
    t = ctx.texture((a.shape[1], a.shape[0]), 1 if a.ndim == 2 else a.shape[2], a.tobytes(), dtype=dtype)
    f = moderngl.LINEAR if linear else moderngl.NEAREST
    t.filter = (f, f)
    t.repeat_x = t.repeat_y = False
    return t


def draw(p, size, textures, uniforms, dtype='f2'):
    out = ctx.texture(size, 4, dtype=dtype)   # p.getMain() and the ARK low grid: FLOAT_16 RGBA
    out.filter = (moderngl.NEAREST, moderngl.NEAREST)
    out.repeat_x = out.repeat_y = False
    fbo = ctx.framebuffer([out])
    fbo.use()
    ctx.viewport = (0, 0) + size
    for unit, (k, t) in enumerate(textures.items()):
        t.use(unit)
        if k in p:
            p[k].value = unit
    for k, v in uniforms.items():
        if k in p:
            p[k].value = v
    ctx.vertex_array(p, []).render(vertices=3)
    ctx.finish()
    data = np.frombuffer(out.read(), np.float16 if dtype == 'f2' else np.float32).astype(np.float64).reshape(size[1], size[0], 4)
    fbo.release()
    out.release()
    return data[..., :3]


def scene():
    """Worker RGB (camera channels before WB / LSC): the dark low-red field with the noisier edge columns."""
    rng = np.random.default_rng(7)
    mean = np.array([3.0e-4, 1.2e-3, 8.0e-4])
    std = np.tile(np.array([2.8e-4, 6.0e-4, 4.0e-4]), (H, W, 1))
    std[:, :EDGE] = np.array([6.3e-4, 7.0e-4, 5.3e-4])
    raw = (mean + std * rng.standard_normal((H, W, 3))).astype(np.float32)
    raw[H - 1, W - 1] = (np.nan, 1e-3, np.inf)               # non-finite samples (the bottom-right corner)
    return raw, mean


def column_bias(out_wb, raw, cols):
    """Mean of the white-balanced output times the white point over the mean of the input, per channel (1 = unbiased)."""
    o = out_wb[:H - 1, cols] * WP
    r = raw[:H - 1, cols].astype(np.float64)
    return o.reshape(-1, 3).mean(0) / r.reshape(-1, 3).mean(0)


def nicergb(raw, signed):
    p = program('vivohdr/nicergb.glsl')
    t = texture(raw)
    g = texture(np.ones((1, 1, 4), np.float32), linear=True)
    try:
        # hybrid per-channel recovery path: nominal clip levels, no flags, no local chroma (no pixel near its clip)
        return draw(p, (W, H), {'InputBuffer': t, 'GainMap': g},
                    {'whitePoint': tuple(WP), 'hlModeU': 1, 'clipLoU': (1.0, 1.0, 1.0), 'clipHiU': (0.0, 0.0, 0.0),
                     'hlStrengthU': 1.0, 'clipFlagsU': 0, 'chromaU': 0, 'blockU': 8, 'chromaLimitU': 0.35, 'defringeU': 0.85,
                     'cropOffset': (0, 0), 'inverseSize': (1.0 / W, 1.0 / H), 'signedU': 1 if signed else 0})
    finally:
        t.release(); g.release(); p.release()


def combine(colour, signed):
    """ark/combine on a 2x reduced colour source (fU = colourFU = 2), no detail, identity colour chain."""
    cw, ch = colour.shape[1], colour.shape[0]
    rgba = np.concatenate([colour, np.ones((ch, cw, 1))], -1).astype(np.float32)
    fused = np.zeros((ch, cw, 4), np.float32)
    fused[..., 0] = 0.45
    full = np.full((2 * ch, 2 * cw, 4), 0.05, np.float32)
    texs = {'InputBuffer': texture(full), 'GainMap': texture(np.ones((1, 1, 4), np.float32), linear=True),
            'ArkLow': texture(rgba), 'ArkColour': texture(rgba), 'ArkFused': texture(fused), 'ArkDetailRef': texture(rgba),
            'ArkLumaS': texture(rgba)}
    p = program('ark/combine.glsl')
    try:
        return draw(p, (2 * cw, 2 * ch), texs,
                    {'sensorToIntermediate': IDENT, 'intermediateToSRGB': IDENT, 'neutralPointU': (1.0, 1.0, 1.0),
                     'inScaleU': 1.0, 'fU': 2, 'colourFU': 2, 'aeU': 1.0, 'clipU': 1.0, 'toeU': 0.05, 'gammaInvU': 1 / 2.2,
                     'macroU': 1.1, 'vibU': (0.0, 0.4, 0.2), 'detailGainU': 0.0, 'deltaChromaU': 1.0, 'filmToeU': 0.1,
                     'ditherU': 0, 'agxAU': (2.7, 1.35, 1.6, 1.0), 'agxBU': (-8.5, 3.5, 0.3, 4.0), 'headroomU': 0,
                     'hlWhiteU': 0.15, 'signedColourU': signed}, dtype='f4')
    finally:
        p.release()
        for x in texs.values():
            x.release()


def combine_checks():
    fails = []
    rng = np.random.default_rng(11)
    cw, ch = 48, 24
    colour = np.empty((ch, cw, 3))
    colour[:, :cw // 2] = (-0.02, 0.10, 0.12)            # deep cyan, R below zero after the sRGB matrix
    colour[:, cw // 2:] = (0.002, 0.012, 0.010)          # dark teal, R at its noise
    colour += rng.normal(0.0, [0.004, 0.003, 0.003], (ch, cw, 3))
    s0 = combine(colour, 0)
    s0c = combine(np.maximum(colour, 0), 0)
    s1 = combine(colour, 1)
    a0 = combine(np.abs(colour), 0)
    a1 = combine(np.abs(colour), 1)
    teal = (slice(4, 2 * ch - 4), slice(cw + 8, 2 * cw - 8))
    lift = s0[teal][..., 0].mean() / max(s1[teal][..., 0].mean(), 1e-9)
    if REPORT:
        print('ark/combine: per tap |raw - pre-clamped| %.2e, non-negative taps |signed - per tap| %.2e, '
              'dark teal red per tap / signed x%.3f' % (np.abs(s0 - s0c).max(), np.abs(a1 - a0).max(), lift))
    if not np.array_equal(s0, s0c):
        fails.append('ark/combine signedColourU 0: not the per-tap clamp of before (max diff %.2e)' % np.abs(s0 - s0c).max())
    if not np.array_equal(a0, a1):
        fails.append('ark/combine: non-negative taps differ between signedColourU 0 and 1 (max diff %.2e)' % np.abs(a1 - a0).max())
    if not lift > 1.1:
        fails.append('ark/combine signedColourU 1: the red of negative taps is still lifted (per tap / signed x%.3f)' % lift)
    return fails


LW = np.array([0.2126, 0.7152, 0.0722])
OFFSET_C = 0.008                                         # NiceDenoise offsetC
DARK_CHROMA = (1.5e-4, 4.0e-4)                           # LmcDenoise.darkChroma on signed hybrid input (defaults)


def nlm_engine(field, signed, luma, chroma, sigma):
    """NiceDenoise.runNlm without the despeckle (checked alone above), 1x grid, no effective-frame map: chromadn/luma ->
    down -> filter (stepU 1, 2) -> nlm -> down4 -> coarse -> apply with the uniforms the Java sets (strengths 0 / 1)."""
    h_, w_ = field.shape[:2]
    tin = texture(np.concatenate([field, np.ones((h_, w_, 1))], -1).astype(np.float32))
    eff = ctx.texture((1, 1), 1, np.zeros(1, np.uint16).tobytes(), dtype='u2')   # EffMap (usampler2D), unused: useEff 0
    eff.filter = (moderngl.NEAREST, moderngl.NEAREST)
    owned = [tin, eff]

    def up(a, linear=False):
        t = texture(np.ascontiguousarray(a, np.float32), linear=linear)   # FLOAT_16 values: exact in f4
        owned.append(t)
        return t

    def rgba(a):
        return np.concatenate([a, np.ones(a.shape[:2] + (1,))], -1)

    def run(name, size, texs, uni):
        p = program(name)
        try:
            return draw(p, size, texs, uni)
        finally:
            p.release()

    half, quarter = (w_ // 2, h_ // 2), (w_ // 4, h_ // 4)
    noisy = up(run('chromadn/luma.glsl', (w_, h_), {'InputBuffer': tin}, {'offsetC': OFFSET_C, 'signedU': signed})[..., 0])
    before = up(rgba(run('chromadn/down.glsl', half, {'InputBuffer': tin}, {'factorU': 2})))
    after = before
    if chroma > 0:
        for st in (1, 2):
            after = up(rgba(run('chromadn/filter.glsl', half, {'InputBuffer': after},
                                {'stepU': st, 'strength': 1.0, 'tolerance': max(1.0, chroma), 'sigmaU': max(sigma, 0.0008),
                                 'offsetC': OFFSET_C})))
    clean = coarse = noisy
    if luma > 0:
        clean = up(run('chromadn/nlm.glsl', (w_, h_), {'InputBuffer': noisy, 'EffMap': eff},
                       {'h': luma * max(0.0035, 3 * sigma), 'pxStepU': 1, 'useEff': 0})[..., 0])
        q = up(run('chromadn/down4.glsl', quarter, {'InputBuffer': clean}, {'factorU': 4})[..., 0])
        coarse = up(run('chromadn/coarse.glsl', quarter, {'InputBuffer': q}, {'tolerance': max(0.004, 3.5 * sigma)})[..., 0],
                    linear=True)                                     # apply samples Coarse with texture()
    grain = max(0.12, min(1.0, 0.0002 / (max(sigma, 1e-4) * luma))) if luma > 0 else 1.0
    try:
        return run('chromadn/apply.glsl', (w_, h_),
                   {'InputBuffer': tin, 'EffMap': eff, 'Before': before, 'After': after, 'Noisy': noisy, 'Clean': clean,
                    'Coarse': coarse},
                   {'sigma': sigma if luma > 0 else 0.0, 'useEff': 0, 'pxStepU': 1, 'lowRatio': 2.0, 'grain': grain,
                    'offsetC': OFFSET_C, 'lumaAmount': 1.0 if luma > 0 else 0.0, 'chromaAmount': 1.0 if chroma > 0 else 0.0,
                    'darkFade': (0.0008, 0.003), 'darkChroma': DARK_CHROMA, 'signedU': signed})
    finally:
        for t in owned:
            t.release()


def nlm_checks(raw):
    fails = []
    field = np.where(np.isfinite(raw), raw, 0).astype(np.float64) / WP      # white-balanced, as the denoise gets it
    edge, inside = slice(0, EDGE), slice(EDGE + 8, W - 8)
    y = field @ LW
    sigma = float(np.std(y[:, inside]) / (2 * np.sqrt(OFFSET_C + max(np.mean(y[:, inside]), 0))))

    def bias(o, cols):
        """(per-channel, luminance) mean of the output over the input's, minus 1 (rows 1..H-2: no border clamp)."""
        a, b = o[1:H - 1, cols].reshape(-1, 3).mean(0), field[1:H - 1, cols].reshape(-1, 3).mean(0)
        return a / b - 1, (a @ LW) / (b @ LW) - 1

    configs = {'luma+chroma': (1.0, 1.0), 'luma, chroma 0': (1.0, 0.0), 'chroma, luma 0': (0.0, 1.0)}
    pos = np.abs(field)
    for name, (luma, chroma) in configs.items():
        a, b = nlm_engine(pos, 1, luma, chroma, sigma), nlm_engine(pos, 0, luma, chroma, sigma)
        if not np.array_equal(a, b):
            fails.append('NLM %s: non-negative input differs between signedU 1 and 0 (%d px)'
                         % (name, int(np.any(a != b, axis=-1).sum())))
    res = {}
    for name, (luma, chroma) in configs.items():
        for signed in (1, 0):
            o = nlm_engine(field, signed, luma, chroma, sigma)
            if not np.all(np.isfinite(o)):
                fails.append('NLM %s signedU %d: non-finite output' % (name, signed))
            res[name, signed] = {'edge': bias(o, edge), 'inside': bias(o, inside)}
            if REPORT:
                print('NLM %-15s signedU %d: edge rgb %s Y %+.2f %% | inside rgb %s Y %+.2f %% | px < 0 %.1f %%'
                      % (name, signed, np.round(100 * res[name, signed]['edge'][0], 2), 100 * res[name, signed]['edge'][1],
                         np.round(100 * res[name, signed]['inside'][0], 2), 100 * res[name, signed]['inside'][1],
                         100 * (o < 0).any(-1).mean()))
    # chroma alone: the luminance of chromadn/luma (the pixel's level in apply) no longer lifted by the clamp per channel
    for part in ('edge', 'inside'):
        yb = res['chroma, luma 0', 1][part][1]
        if abs(yb) > 0.005:
            fails.append('NLM chroma, luma 0, signed: %s luminance biased by %+.2f %%' % (part, 100 * yb))
    if res['chroma, luma 0', 0]['edge'][1] < 0.03:
        fails.append('NLM chroma, luma 0, clamped: edge luminance lift %+.2f %% < 3 %% - the scene no longer shows the bias'
                     % (100 * res['chroma, luma 0', 0]['edge'][1]))
    # chroma 0: the pixel keeps its own colour difference, signed (no red lift where it is near zero)
    rs, rc = res['luma, chroma 0', 1], res['luma, chroma 0', 0]
    if abs(rs['edge'][0][0]) > 0.05:
        fails.append('NLM luma, chroma 0, signed: edge red biased by %+.1f %%' % (100 * rs['edge'][0][0]))
    if rc['edge'][0][0] < 0.2:
        fails.append('NLM luma, chroma 0, clamped: edge red lift %+.1f %% < 20 %% - the scene no longer shows the bias'
                     % (100 * rc['edge'][0][0]))
    for part in ('edge', 'inside'):
        if abs(rs[part][0][0]) > 0.6 * rc[part][0][0]:
            fails.append("NLM luma, chroma 0: %s red bias %+.1f %% not below 0.6 x the clamp's %+.1f %%"
                         % (part, 100 * rs[part][0][0], 100 * rc[part][0][0]))
    rb = res['luma+chroma', 1]['edge']
    if abs(rb[0][0]) > 0.02 or abs(rb[1]) > 0.03:
        fails.append('NLM luma+chroma, signed: edge red %+.1f %% / Y %+.1f %% biased' % (100 * rb[0][0], 100 * rb[1]))
    return fails, rs['edge'][0][0], rc['edge'][0][0]


def main():
    fails = []
    raw, mean = scene()
    edge, inside = slice(0, EDGE), slice(EDGE + 8, W - 8)

    # ---- nicergb
    new = nicergb(raw, True)
    old = nicergb(raw, False)
    be, bi = column_bias(new, raw, edge), column_bias(new, raw, inside)
    oe, oi = column_bias(old, raw, edge), column_bias(old, raw, inside)
    neg_share = (raw[:, edge, 0] < 0).mean()
    if REPORT:
        print('nicergb signed: edge %s inside %s | clamped: edge %s inside %s | red samples < 0 at the edge %.0f %%'
              % (np.round(be, 4), np.round(bi, 4), np.round(oe, 4), np.round(oi, 4), 100 * neg_share))
    if np.max(np.abs(be - 1)) > 0.005 or np.max(np.abs(bi - 1)) > 0.005:
        fails.append('nicergb signed: mean colour biased (edge %s, inside %s)' % (np.round(be, 4), np.round(bi, 4)))
    if oe[0] < 1.2:
        fails.append('nicergb clamped: edge red lift %.3f <= 1.2 - the scene no longer shows the bias' % oe[0])
    if not np.all(np.isfinite(new)) or np.any(new[H - 1, W - 1, [0, 2]] != 0.0):
        fails.append('nicergb signed: non-finite input not zeroed (%s)' % new[H - 1, W - 1])
    ok = np.all(raw >= 0, axis=-1) & np.all(np.isfinite(raw), axis=-1)
    if not np.array_equal(new[ok], old[ok]):
        fails.append('nicergb: pixels without a negative channel differ between signed and clamped (%d)'
                     % int(np.any(new[ok] != old[ok], axis=-1).sum()))

    # ---- chromadn/despeckle on the signed white-balanced field (the denoise input; numpy, so each stage is checked alone)
    new = np.where(np.isfinite(raw), raw, 0).astype(np.float64) / WP
    wb = np.concatenate([new, np.ones((H, W, 1))], -1).astype(np.float32)
    t = texture(wb)
    p = program('chromadn/despeckle.glsl')
    # noise sigma of u = sqrt(Y + 0.008) (the reading of chromadn/noiseest): sigma_Y / (2 sqrt(Y + 0.008))
    y = new[..., 0] * 0.2126 + new[..., 1] * 0.7152 + new[..., 2] * 0.0722
    sigma = float(np.std(y[:, inside]) / (2 * np.sqrt(0.008 + max(np.mean(y[:, inside]), 0))))
    ds = draw(p, (W, H), {'InputBuffer': t}, {'sigma': sigma, 'offsetC': 0.008, 'pxStepU': 1})
    p.release()
    for name, cols in (('edge', edge), ('inside', inside)):
        a, b = ds[:H - 1, cols].reshape(-1, 3).mean(0), new[:H - 1, cols].reshape(-1, 3).mean(0)
        rel = (a - b) / np.abs(b[0])
        if REPORT:
            print('despeckle %s: mean change / red %s' % (name, np.round(rel, 4)))
        if np.max(np.abs(rel)) > 0.02:
            fails.append('despeckle %s: mean colour changed by %s of the red' % (name, np.round(rel, 4)))
    if not np.all(np.isfinite(ds)):
        fails.append('despeckle: non-finite output')

    # ---- chromadn/down: box mean of the signed values
    p = program('chromadn/down.glsl')
    dn = draw(p, (W // 2, H // 2), {'InputBuffer': t}, {'factorU': 2})
    p.release()
    ref = new.reshape(H // 2, 2, W // 2, 2, 3).mean(axis=(1, 3))
    err = np.abs(dn - ref)[:-1, :-1].max()
    if REPORT:
        print('chromadn/down: max |GPU - numpy box mean| %.2e (mean red %.3e)' % (err, ref[..., 0].mean()))
    if err > 2e-6:
        fails.append('chromadn/down: not the box mean of the signed values (max error %.2e)' % err)

    # ---- ark/low: colour box mean of the signed values; DETAIL_REF keeps its per-pixel clamp
    g = texture(np.ones((1, 1, 4), np.float32), linear=True)
    colour = {'sensorToIntermediate': IDENT, 'intermediateToSRGB': IDENT, 'neutralPointU': (1.0, 1.0, 1.0), 'inScaleU': 1.0,
              'factorU': 2}
    p = program('ark/low.glsl')
    low = draw(p, (W // 2, H // 2), {'InputBuffer': t, 'GainMap': g}, colour)
    p.release()
    err = np.abs(low - ref)[:-1, :-1].max()
    lift = low[:, :EDGE // 2, 0].mean() / ref[:, :EDGE // 2, 0].mean()
    if REPORT:
        print('ark/low colour: max |GPU - signed box mean| %.2e, edge red mean / signed mean %.4f' % (err, lift))
    if err > 2e-6 or abs(lift - 1) > 0.002:
        fails.append('ark/low colour: not the signed box mean (max error %.2e, edge red x%.4f)' % (err, lift))
    ae = 40.0
    p = program('ark/low.glsl', {'DETAIL_REF': '1'})
    dref = draw(p, (W // 2, H // 2), {'InputBuffer': t, 'GainMap': g}, dict(colour, detailClipU=ae))
    p.release()
    pos = np.maximum(new, 0)
    yv = pos[..., 0] * 0.2126 + pos[..., 1] * 0.7152 + pos[..., 2] * 0.0722
    want = np.minimum(yv * ae, 1).reshape(H // 2, 2, W // 2, 2).mean(axis=(1, 3))
    err = np.abs(dref[..., 0] - want)[:-1, :-1].max()
    if REPORT:
        print('ark/low DETAIL_REF: max |GPU - clamped box mean| %.2e' % err)
    if err > 1e-4:
        fails.append('ark/low DETAIL_REF: not the box mean of min(ae Y(max(rgb, 0)), 1) (max error %.2e)' % err)
    t.release(); g.release()

    # ---- ark/combine B-spline colour: signedColourU 0 (SCAM HDR, hybrid after the denoise) clamps per tap as before, so
    # the colour matrix's negative taps (a saturated colour outside sRGB) change nothing; 1 (signed hybrid input) clamps
    # after the interpolation and no longer lifts a channel near zero; for non-negative taps both are the same.
    fails += combine_checks()

    # ---- the NLM denoise engine on the signed field (luminance and, with the chroma denoise off, colour clipped once)
    nf, nlm_signed, nlm_clamped = nlm_checks(raw)
    fails += nf

    if fails:
        for f in fails:
            print('FAIL:', f)
        sys.exit(1)
    print('signed RGB PASS: nicergb edge bias %+.2f %% (clamped %+.1f %%), despeckle / down / ark low keep the mean, '
          'NLM chroma 0 edge red %+.1f %% (clamped %+.1f %%)'
          % (100 * (be[0] - 1), 100 * (oe[0] - 1), 100 * nlm_signed, 100 * nlm_clamped))


if __name__ == '__main__':
    main()
