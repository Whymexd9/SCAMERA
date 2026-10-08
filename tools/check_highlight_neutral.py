#!/usr/bin/env python3
"""Blown highlights stay neutral and clip edges carry no blue / violet / cyan fringe: the LMC hybrid highlight recovery
(VivoNiceRgb) on the SHIPPED shaders, wired as VivoNiceRgb.Run does (hlrecovery/chanprep -> hlrecovery/reduce x2 ->
vivohdr/nicergb hlModeU 1 -> vivohdr/clipband) with the worker's clip flags, 1x grid.

Scene A, after the vivo X200 Ultra window of 2026-10-08 (native Quad mosaic, no Bento, white point 0.526 / 1 / 0.623,
base-frame clip 1.0 per channel, cellClip flags): a blown window where G and B clipped and R stayed at 0.65..0.95 of its
clip, a net in front of it - 1-px threads that only the R sites see (R dips, G and B stay clipped) and 2-px threads dark
in every channel - plus a partially clipped sky-blue patch (only G clipped, R and B measured, B/R 1.3), a cyan sign (G and
B clipped, R at 4 % of the clip) and a ramp where R rises from 0.2 to 0.95 of its clip under clipped G and B.
Checks:
- the window and the R-only threads are neutral (OKLab chroma < 0.02; the shaders before 2026-10-08: 0.05, lavender);
- no lavender / purple: B > 1.02 G with R >= G nowhere in the window;
- the dark threads stay neutral and darker than the window (luminance <= 0.7 x);
- the sky patch keeps its colour (B/R within 3 % of the input), the cyan sign keeps its chroma (within 5 %);
- the ramp has no hue jump (max 1-px OKLab step < 0.02).

Scene B, after the lamp of 2026-10-08 (Bento k = 7.95, white point 0.354 / 1 / 0.575): a neutral light (20 x white)
next to a neutral shell (0.08) with axial chromatic aberration - B blurred wider than G and centred 0.35 px outwards, R a
little wider - which leaves a blue-violet line on the shell and a blue edge on the light; a red sticker 5-9 px from the
edge; a blown neutral cloud next to a blue sky; a dark blue wall next to the light.
Checks:
- fringe: max OKLab chroma within 8 px of the edge < 0.04 (before: 0.36), away from the sticker's window (there the dark
  side IS red and the halo pixels take that colour);
- the red sticker's core keeps its chroma (within 5 %); the sky and the blue wall 3 px and more from the flagged border
  keep theirs (within 5 %);
- pixels without flags further than 2 x the clip-band radius are passed through (white balance only).
Usage: check_highlight_neutral.py [--shaders DIR] [--report]
"""
from pathlib import Path
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
_progs = {}


def program(name):
    if name not in _progs:
        _progs[name] = ctx.program(vertex_shader=VS, fragment_shader='#version 430\n' + (SH / name).read_text(encoding='utf-8'))
    return _progs[name]


def texture(a, dtype='f4', linear=False):
    a = np.ascontiguousarray(a)
    t = ctx.texture((a.shape[1], a.shape[0]), 1 if a.ndim == 2 else a.shape[2], a.tobytes(), dtype=dtype)
    f = moderngl.LINEAR if linear else moderngl.NEAREST
    t.filter = (f, f)
    t.repeat_x = t.repeat_y = False
    return t


def draw(p, size, textures, uniforms):
    out = ctx.texture(size, 4, dtype='f2')   # p.getMain() / the chroma textures: FLOAT_16 RGBA
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
    fbo.release()
    return out


def read(t):
    return np.frombuffer(t.read(), np.float16).reshape(t.height, t.width, 4)[..., :3].astype(np.float32)


def recover(raw, flags, wp, lo, hi):
    """VivoNiceRgb.Run, per-channel branch with clip flags and the chroma statistics (scale 1)."""
    h, w = flags.shape
    inp = texture(raw.astype(np.float32))
    gm = texture(np.ones((1, 1, 4), np.float16), 'f2', linear=True)
    ft = texture(flags.astype(np.uint8), 'f1')
    block = 8
    common = {'whitePoint': tuple(map(float, wp)), 'inverseSize': (1.0 / w, 1.0 / h), 'cropOffset': (0, 0),
              'clipLoU': tuple(map(float, lo)), 'clipHiU': tuple(map(float, hi)), 'clipFlagsU': 1}
    near = ((w + block - 1) // block, (h + block - 1) // block)
    c8 = draw(program('hlrecovery/chanprep.glsl'), near, {'InputBuffer': inp, 'GainMap': gm, 'ClipFlags': ft},
              dict(common, blockU=block, pxStepU=1))
    wide = ((near[0] + 3) // 4, (near[1] + 3) // 4)
    c8.filter = (moderngl.LINEAR, moderngl.LINEAR)
    c32 = draw(program('hlrecovery/reduce.glsl'), wide, {'InputBuffer': c8}, {})
    c32.filter = (moderngl.LINEAR, moderngl.LINEAR)
    c128 = draw(program('hlrecovery/reduce.glsl'), ((wide[0] + 3) // 4, (wide[1] + 3) // 4), {'InputBuffer': c32}, {})
    c128.filter = (moderngl.LINEAR, moderngl.LINEAR)
    nice = draw(program('vivohdr/nicergb.glsl'), (w, h),
                {'InputBuffer': inp, 'GainMap': gm, 'ClipFlags': ft, 'Chroma8': c8, 'Chroma32': c32, 'Chroma128': c128},
                dict(common, hlModeU=1, hlStrengthU=1.0, chromaU=1, blockU=block, chromaLimitU=0.35, defringeU=0.85,
                     signedU=1))
    band = draw(program('vivohdr/clipband.glsl'), (w, h), {'InputBuffer': nice, 'ClipFlags': ft},
                {'radiusU': 3, 'zoneU': 2, 'strengthU': 1.0})
    out = read(band)
    for t in (inp, gm, ft, c8, c32, c128, nice, band):
        t.release()
    return out


M1 = np.array([[0.4122214708, 0.5363325363, 0.0514459929], [0.2119034982, 0.6806995451, 0.1073969566],
               [0.0883024619, 0.2817188376, 0.6299787005]])
M2 = np.array([[0.2104542553, 0.7936177850, -0.0040720468], [1.9779984951, -2.4285922050, 0.4505937099],
               [0.0259040371, 0.7827717662, -0.8086757660]])
LUM = np.array([0.2126, 0.7152, 0.0722], np.float32)


def ab(rgb):
    """OKLab a, b of the chromaticity (scaled to mean 1): hue and chroma independent of the level."""
    x = np.maximum(rgb, 0) / np.maximum(rgb.mean(-1, keepdims=True), 1e-6)
    return (np.cbrt(np.maximum(x @ M1.T, 0)) @ M2.T)[..., 1:]


def chroma(rgb):
    return np.linalg.norm(ab(rgb), axis=-1)


def dilate(m, r):
    out = m.copy()
    for dy in range(-r, r + 1):
        for dx in range(-r, r + 1):
            out |= np.roll(np.roll(m, dy, 0), dx, 1)
    return out


def erode(m, r):
    return ~dilate(~m, r)


def blur1d(a, sigma, axis):
    """Gaussian blur along one axis (edge-clamped)."""
    if sigma <= 0:
        return a
    r = int(np.ceil(3 * sigma))
    k = np.exp(-0.5 * (np.arange(-r, r + 1) / sigma) ** 2)
    k /= k.sum()
    pad = [(0, 0)] * a.ndim
    pad[axis] = (r, r)
    p = np.pad(a, pad, mode='edge')
    out = np.zeros_like(a)
    n = a.shape[axis]
    for i, w in enumerate(k):
        out += w * np.take(p, np.arange(i, i + n), axis=axis)
    return out


def shift_x(a, s):
    """Linear sub-pixel shift along x (positive: content moves right), edge-clamped."""
    x = np.clip(np.arange(a.shape[1]) - s, 0, a.shape[1] - 1)
    x0 = np.floor(x).astype(int)
    x1 = np.minimum(x0 + 1, a.shape[1] - 1)
    f = (x - x0)[None, :]
    return a[:, x0] * (1 - f) + a[:, x1] * f


fail = []


def check(ok, msg):
    print(('PASS ' if ok else 'FAIL ') + msg)
    if not ok:
        fail.append(msg)


# ------------------------------------------------------------------ scene A: blown window, no Bento
WPA = np.array([0.5263672, 1.0, 0.6230469], np.float32)
H, W = 192, 320
yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
raw = np.zeros((H, W, 3), np.float32)
raw[:] = np.array([0.03, 0.05, 0.035], np.float32)                       # dark frame around the window
WIN = (xx >= 16) & (xx < 208) & (yy >= 16) & (yy < 176)
rfrac = 0.65 + 0.30 * (0.5 + 0.5 * np.sin(xx / 23.0) * np.cos(yy / 17.0))  # R at 0.65..0.95 of its clip
raw[WIN] = np.stack([rfrac, np.ones_like(rfrac), np.ones_like(rfrac)], -1)[WIN]
THIN = WIN & (((xx.astype(int) + 2 * yy.astype(int)) % 24) == 0)         # 1-px threads only the R sites see
raw[THIN, 0] = 0.30
THICK = WIN & ((np.abs(yy - 0.5 * xx - 20) % 40) < 2) & ~THIN             # 2-px threads dark in every channel
raw[THICK] = 0.5 * WPA
SKY = (xx >= 224) & (xx < 304) & (yy >= 16) & (yy < 72)                   # G clipped, R / B measured, B/R 1.3 (wb)
raw[SKY] = np.array([1.0, 1.4, 1.3], np.float32) * WPA * np.array([1.0, 1.0, 1.0], np.float32)
raw[SKY] = np.minimum(raw[SKY], 1.0)
SIGN = (xx >= 224) & (xx < 304) & (yy >= 88) & (yy < 120)                # cyan sign: G and B clipped, R at 4 %
raw[SIGN] = (0.04, 1.0, 1.0)
RAMP = (xx >= 224) & (xx < 304) & (yy >= 136) & (yy < 176)               # R from 0.2 to 0.95 of its clip
raw[RAMP] = np.stack([0.2 + 0.75 * (xx - 224) / 80.0, np.ones_like(xx), np.ones_like(xx)], -1)[RAMP]
clipped = (raw >= 0.97).any(-1)
flags = np.zeros((H, W), np.uint8)
flags[dilate(clipped, 1)] |= 8
flags[clipped] |= 7                                                       # cellClip: every colour of a clipped cell
out = recover(raw, flags, WPA, (1.0, 1.0, 1.0), (0.0, 0.0, 0.0))
c_in = raw / WPA

core = erode(WIN, 3) & ~dilate(THICK, 1)
ch = chroma(out[core])
check(ch.max() < 0.02, 'window neutral (G and B clipped, R 0.65..0.95): max chroma %.4f < 0.02 (input %.3f)'
      % (ch.max(), chroma(c_in[core]).mean()))
o = out[core]
lav = (o[:, 2] > 1.02 * o[:, 1]) & (o[:, 0] >= o[:, 1])
check(lav.sum() == 0, 'no lavender in the window: %d px with B > 1.02 G and R >= G' % lav.sum())
thin = THIN & erode(WIN, 3) & ~dilate(THICK, 1)
check(chroma(out[thin]).max() < 0.02, 'R-only threads neutral: max chroma %.4f < 0.02' % chroma(out[thin]).max())
thick = erode(THICK, 0) & erode(WIN, 3)
lw = np.median(out[core] @ LUM)
check(chroma(out[thick]).max() < 0.02 and np.percentile(out[thick] @ LUM, 95) <= 0.7 * lw,
      'dark threads neutral and darker: max chroma %.4f, p95 luminance %.2f vs window %.2f'
      % (chroma(out[thick]).max(), np.percentile(out[thick] @ LUM, 95), lw))
sky = erode(SKY, 3)
br_in = (c_in[sky][:, 2] / c_in[sky][:, 0]).mean()
br_out = (out[sky][:, 2] / out[sky][:, 0]).mean()
check(abs(br_out / br_in - 1) < 0.03, 'partially clipped sky keeps its colour: B/R %.3f -> %.3f' % (br_in, br_out))
sign = erode(SIGN, 3)
cs_in, cs_out = chroma(c_in[sign]).mean(), chroma(out[sign]).mean()
check(abs(cs_out / cs_in - 1) < 0.05, 'cyan sign keeps its colour: chroma %.3f -> %.3f' % (cs_in, cs_out))
ramp = erode(RAMP, 3)
a = ab(out)
st = np.linalg.norm(a[:, 1:] - a[:, :-1], axis=-1)[(ramp[:, 1:] & ramp[:, :-1])]
check(st.max() < 0.02, 'ramp under clipped G and B: max 1-px hue step %.4f < 0.02' % st.max())
out_a = out

# ------------------------------------------------------------------ scene B: clip edge with axial CA, Bento
WPB = np.array([0.3544922, 1.0, 0.5751953], np.float32)
K = 7.95
H, W = 160, 320
yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
edge = 100 + 0.15 * (yy - 80)                                             # slanted edge of the lamp
dist = xx - edge
wb = np.full((H, W, 3), 0.08, np.float32)                                 # neutral shell
LAMP = (dist >= 0) & (xx < 160)
wb[LAMP] = 20.0
STICK = (dist >= -9) & (dist < -5) & (yy >= 20) & (yy < 50)               # red sticker on the shell
wb[STICK] = (0.30, 0.06, 0.05)
WALL = (xx >= 160) & (xx < 176)                                           # dark blue wall right of the light
wb[WALL] = (0.02, 0.03, 0.08)
CLOUD = (xx >= 176) & (xx < 250) & ((xx - 213) ** 2 / 30.0 ** 2 + (yy - 80) ** 2 / 50.0 ** 2 <= 1)
SKYB = (xx >= 176) & ~CLOUD
wb[SKYB] = (0.35, 0.55, 1.0)
wb[CLOUD] = 20.0
sig, sh = (1.1, 0.8, 1.6), (0.0, 0.0, -0.35)                              # axial CA: B wider, re-centred 0.35 px off
for c in range(3):
    v = blur1d(blur1d(wb[..., c], sig[c], 1), 0.5 * sig[c], 0)
    wb[..., c] = shift_x(v, sh[c]) if c == 2 else v
raw = np.minimum(wb * WPB, K).astype(np.float32)
base = (raw >= 0.98).any(-1)
clippedB = (raw >= 0.98 * K).any(-1)
flags = np.zeros((H, W), np.uint8)
bento = dilate(base, 3)
flags[bento] |= 16
flags[bento & dilate(base, 1)] |= 8 | 64
flags[dilate(clippedB, 2)] |= 8 | 64
flags[clippedB] |= 7 | 32 | 8
out = recover(raw, flags, WPB, (1.0, 1.0, 1.0), (K, K, K))
c_in = raw / WPB

band = (dist >= -8) & (dist < 5) & (xx < 160) & ~dilate(STICK, 4) & (yy >= 4) & (yy < H - 4)
check(chroma(out[band]).max() < 0.04, 'clip-edge fringe: max chroma within 8 px of the edge %.4f < 0.04 (input %.3f)'
      % (chroma(out[band]).max(), chroma(c_in[band]).max()))
stick = erode(STICK, 1)
check(abs(chroma(out[stick]).mean() / chroma(c_in[stick]).mean() - 1) < 0.05,
      'red sticker keeps its colour: chroma %.3f -> %.3f' % (chroma(c_in[stick]).mean(), chroma(out[stick]).mean()))
fl = (flags & 15) != 0
for name, m in (('blue sky next to a blown cloud', SKYB & ~dilate(fl, 3) & (xx < W - 4)),
                ('dark blue wall next to the light', WALL & ~dilate(fl, 3))):
    cin, cout = chroma(c_in[m]).mean(), chroma(out[m]).mean()
    check(m.sum() > 100 and abs(cout / cin - 1) < 0.05, '%s keeps its colour: chroma %.3f -> %.3f (%d px)' % (name, cin, cout, m.sum()))
away = ~dilate(fl, 7)
check(np.abs(out[away] - c_in[away]).max() <= 2e-3 * max(1.0, float(c_in[away].max())),
      'pass-through away from the flags: max |diff| %.2e' % np.abs(out[away] - c_in[away]).max())

if REPORT:
    try:
        from PIL import Image
        for name, v in (('a', out_a), ('b', out)):
            v = v / (1 + v / 2.0)
            Image.fromarray((np.clip(v, 0, 1) ** (1 / 2.2) * 255).astype(np.uint8)).save('check_highlight_neutral_%s.png' % name)
    except ImportError:
        pass
if fail:
    print('highlight neutral FAIL: %d check(s)' % len(fail))
    sys.exit(1)
print('highlight neutral PASS')
