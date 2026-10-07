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
  of min(ae * Y(max(rgb, 0)), 1), as ark/combine's detail luminance.
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

    if fails:
        for f in fails:
            print('FAIL:', f)
        sys.exit(1)
    print('signed RGB PASS: nicergb edge bias %+.2f %% (clamped %+.1f %%), despeckle / down / ark low keep the mean'
          % (100 * (be[0] - 1), 100 * (oe[0] - 1)))


if __name__ == '__main__':
    main()
