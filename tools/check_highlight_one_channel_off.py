#!/usr/bin/env python3
"""Blown Bento sky with one channel 'off' stays neutral and seamless: the LMC hybrid highlight recovery (VivoNiceRgb) on the
SHIPPED shaders, wired as VivoNiceRgb.Run does (hlrecovery/chanprep -> hlrecovery/reduce x2 -> vivohdr/nicergb hlModeU 1 ->
vivohdr/clipband) with the worker's clip flags, 1x grid. research/highlights/ONEPLUS15_PINK.md (P58).

Scene after the OnePlus 15 shot IMG_20261008_180208 (camera 2, Bento k = 2.7085 from 2 ultrashorts, white point
0.456 / 1 / 0.63; log: "hi=2.4784698(plateau),2.4837546(plateau),0.0(off) max=2.75,2.52,2.52 usClipped=0.0", CLIP FLAGS
R=118 G=52 B=90 ultrashort=0): the ultrashort saturates at 0.915 k, below the worker's flag threshold (0.98 of its white),
so the worker flags no ultrashort clipping (no bit 5, usClipped 0) and Java finds the clip only as a histogram plateau of R
and G. Blown sunset sky through leaves: R and G of the sky at that clip, B measured at 0.55..0.75 k (B never clips there:
no plateau, "off"), a sun patch where B saturates too (0.93 k), leaves (dark green, base frames, no flags), the Bento
mask (bit 4) over the sky, the clip-border bits 3 + 6 on a ring along the leaves and on scattered 8-px cells inside the
sky (where the base frames' clipped samples were excluded), an unclipped pink cloud and an orange glow (R clipped, G and B
measured) inside the mask.
Before the fix the sky was pink / magenta (R > B > G: no level inside the mask without flags, raw R = G after white
balance) with white blotches on the bit-3 cells (the only pixels that were recovered).
Levels: the old ones as VivoNiceRgb.channelClip gave them (B filled with k) and the new ones (B from the measured clip /
its own maximum, the unflagged-clip gate on: VivoNiceRgbOneChannelOffTest asserts the same numbers from the same
histograms).
Checks (new shaders + new levels):
- the sky and the sun are neutral (OKLab chroma < 0.02; before: about 0.1, pink);
- no magenta: G >= 0.97 min(R, B) inside the mask;
- seamless: the bit-3 cells and the cells without flags inside the sky differ by < 1 % in luminance and < 0.005 in chroma;
- the sky is not darker than its own white-balanced clip of G (no grey sky);
- the pink cloud (unclipped, inside the mask) and the leaves (outside) are passed through unchanged; the orange glow keeps
  its colour (chroma within 10 %);
- the old shaders with the old levels reproduce the bug (pink sky, blotches): the scene is the case, not a lucky pass.
With --baseline DIR (an older asset shader tree): byte-identity of the new shaders against it for the level combinations
of the logs where no channel is off (gate unset), on this scene and on a Bento lamp scene - the shots that must render as
before.
Usage: check_highlight_one_channel_off.py [--shaders DIR] [--baseline DIR] [--report]
"""
from pathlib import Path
import sys
import numpy as np
import moderngl

ROOT = Path(__file__).resolve().parents[1]
SH = ROOT / 'app/src/main/assets/shaders'
if '--shaders' in sys.argv:
    SH = Path(sys.argv[sys.argv.index('--shaders') + 1])
BASE = Path(sys.argv[sys.argv.index('--baseline') + 1]) if '--baseline' in sys.argv else None
REPORT = '--report' in sys.argv

try:
    ctx = moderngl.create_standalone_context(backend='egl', require=430)
except Exception:
    ctx = moderngl.create_standalone_context(require=430)
VS = '#version 430\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.0-1.0,0,1);}'
_progs = {}


def program(tree, name):
    key = (str(tree), name)
    if key not in _progs:
        _progs[key] = ctx.program(vertex_shader=VS, fragment_shader='#version 430\n' + (tree / name).read_text(encoding='utf-8'))
    return _progs[key]


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


def read(t, n=3):
    return np.frombuffer(t.read(), np.float16).reshape(t.height, t.width, 4)[..., :n].copy()


def recover(tree, raw, flags, wp, lo, hi, unflagged=None, raw16=False):
    """VivoNiceRgb.Run, per-channel branch with clip flags and the chroma statistics (scale 1). unflagged None: the
    uniform is not set at all (as the old Java never set it); raw16: every intermediate as float16 bits (byte-identity)."""
    h, w = flags.shape
    inp = texture(raw.astype(np.float32))
    gm = texture(np.ones((1, 1, 4), np.float16), 'f2', linear=True)
    ft = texture(flags.astype(np.uint8), 'f1')
    block = 8
    common = {'whitePoint': tuple(map(float, wp)), 'inverseSize': (1.0 / w, 1.0 / h), 'cropOffset': (0, 0),
              'clipLoU': tuple(map(float, lo)), 'clipHiU': tuple(map(float, hi)), 'clipFlagsU': 1}
    if unflagged is not None:
        common['clipHiUnflaggedU'] = int(unflagged)
    near = ((w + block - 1) // block, (h + block - 1) // block)
    c8 = draw(program(tree, 'hlrecovery/chanprep.glsl'), near, {'InputBuffer': inp, 'GainMap': gm, 'ClipFlags': ft},
              dict(common, blockU=block, pxStepU=1))
    wide = ((near[0] + 3) // 4, (near[1] + 3) // 4)
    c8.filter = (moderngl.LINEAR, moderngl.LINEAR)
    c32 = draw(program(tree, 'hlrecovery/reduce.glsl'), wide, {'InputBuffer': c8}, {})
    c32.filter = (moderngl.LINEAR, moderngl.LINEAR)
    c128 = draw(program(tree, 'hlrecovery/reduce.glsl'), ((wide[0] + 3) // 4, (wide[1] + 3) // 4), {'InputBuffer': c32}, {})
    c128.filter = (moderngl.LINEAR, moderngl.LINEAR)
    nice = draw(program(tree, 'vivohdr/nicergb.glsl'), (w, h),
                {'InputBuffer': inp, 'GainMap': gm, 'ClipFlags': ft, 'Chroma8': c8, 'Chroma32': c32, 'Chroma128': c128},
                dict(common, hlModeU=1, hlStrengthU=1.0, chromaU=1, blockU=block, chromaLimitU=0.35, defringeU=0.85,
                     signedU=1))
    band = draw(program(tree, 'vivohdr/clipband.glsl'), (w, h), {'InputBuffer': nice, 'ClipFlags': ft},
                dict({'radiusU': 3, 'zoneU': 2, 'strengthU': 1.0},
                     **({'clipHiUnflaggedU': int(unflagged)} if unflagged is not None else {})))
    if raw16:
        out = [np.frombuffer(t.read(), np.uint16) for t in (c8, c32, c128, nice, band)]
    else:
        out = read(band).astype(np.float32)
    for t in (inp, gm, ft, c8, c32, c128, nice, band):
        t.release()
    return out


M1 = np.array([[0.4122214708, 0.5363325363, 0.0514459929], [0.2119034982, 0.6806995451, 0.1073969566],
               [0.0883024619, 0.2817188376, 0.6299787005]])
M2 = np.array([[0.2104542553, 0.7936177850, -0.0040720468], [1.9779984951, -2.4285922050, 0.4505937099],
               [0.0259040371, 0.7827717662, -0.8086757660]])
LUM = np.array([0.2126, 0.7152, 0.0722], np.float32)


def chroma(rgb):
    """OKLab chroma of the chromaticity (scaled to mean 1): independent of the level."""
    x = np.maximum(rgb, 0) / np.maximum(rgb.mean(-1, keepdims=True), 1e-6)
    return np.linalg.norm((np.cbrt(np.maximum(x @ M1.T, 0)) @ M2.T)[..., 1:], axis=-1)


def dilate(m, r):
    out = m.copy()
    for dy in range(-r, r + 1):
        for dx in range(-r, r + 1):
            out |= np.roll(np.roll(m, dy, 0), dx, 1)
    return out


def erode(m, r):
    return ~dilate(~m, r)


fail = []


def check(ok, msg):
    print(('PASS ' if ok else 'FAIL ') + msg)
    if not ok:
        fail.append(msg)


# ------------------------------------------------------------------ the OnePlus 15 sky through leaves
K = 2.708499
WP = np.array([0.456, 1.0, 0.63], np.float32)
LO = (1.0, 1.0, 1.0)
HI_OLD = (2.4784698, 2.4837546, K)              # channelClip before P58: the 'off' B filled with k
HI_NEW = (2.4784698, 2.4837546, 2.5186846)      # P58: B at its own sampled maximum (above the measured clips, below k)
H, W = 256, 384
rng = np.random.default_rng(58)
yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
SKY = np.zeros((H, W), bool)
for _ in range(70):                             # openings between the leaves, 3..28 px
    cx, cy, r = rng.uniform(0, 300), rng.uniform(0, H), rng.uniform(3, 28)
    SKY |= (xx - cx) ** 2 + (yy - cy) ** 2 <= r * r
SUN = (xx - 150) ** 2 + (yy - 128) ** 2 <= 30 ** 2
SKY |= SUN
CLOUD = (xx >= 316) & (xx < 372) & (yy >= 20) & (yy < 100)     # unclipped pink cloud inside the mask
GLOW = (xx >= 316) & (xx < 372) & (yy >= 140) & (yy < 230)     # orange glow: R clipped, G and B measured
SKY &= ~((xx >= 304))
raw = np.zeros((H, W, 3), np.float32)
leaf = 0.5 + 0.5 * np.sin(xx / 5.0) * np.cos(yy / 7.0)
raw[:] = np.stack([0.03 + 0.03 * leaf, 0.08 + 0.06 * leaf, 0.025 + 0.02 * leaf], -1) * WP    # dark green leaves
clipR, clipG = HI_OLD[0], HI_OLD[1]
skyB = (0.55 + 0.20 * (yy / H)) * K                                       # B measured, 0.55..0.75 k
noise = rng.uniform(0.0, 0.002, (H, W, 3)).astype(np.float32)
raw[SKY, 0] = clipR * (1 - noise[SKY, 0])
raw[SKY, 1] = clipG * (1 - noise[SKY, 1])
raw[SKY, 2] = skyB[SKY]
raw[SUN, 2] = 0.9299 * K * (1 - noise[SUN, 2])                            # B saturates too
raw[CLOUD] = np.array([0.60, 0.42, 0.36], np.float32) * K * np.array([1, 1, 1], np.float32)
raw[GLOW] = np.stack([clipR * (1 - noise[..., 0]), 0.62 * K + 0 * xx, 0.25 * K + 0 * xx], -1)[GLOW]
BRIGHT = SKY | CLOUD | GLOW
flags = np.zeros((H, W), np.uint8)
MASK = dilate(BRIGHT, 3)
flags[MASK] |= 16
ring = MASK & dilate(~BRIGHT, 2)                                          # border along the leaves (worker rim pass: 6)
cells = (rng.uniform(size=(H // 8 + 1, W // 8 + 1)) < 0.35).repeat(8, 0).repeat(8, 1)[:H, :W]
SCAT = MASK & cells                                                       # base clipped samples excluded here
flags[ring | SCAT] |= 8 | 64
sun_core = SUN & ~dilate(~SUN, 3)
fl3 = (flags & 8) != 0
c_in = raw / WP


def sky_stats(out, label):
    core = SKY & erode(SKY, 3) & ~dilate(SUN, 3)
    ch = chroma(out[core])
    mag = MASK & BRIGHT & ~CLOUD & ~GLOW
    o = out[mag]
    magenta = (o[:, 1] < 0.97 * np.minimum(o[:, 0], o[:, 2])).sum()
    a, b = core & fl3 & erode(SCAT, 2), core & ~fl3 & ~dilate(SCAT, 2)
    # the sky's level follows B (measured, a vertical ramp): compare each flagged cell with the unflagged pixels of its
    # own rows, not two medians over different heights
    lum = out @ LUM
    rowb = np.array([np.median(lum[y][b[y]]) if b[y].any() else np.nan for y in range(lum.shape[0])])
    ys = np.nonzero(a)[0]
    rel = lum[a] / rowb[ys]
    la, lb = np.nanmedian(rel), 1.0
    return {'core': core, 'chroma': ch, 'magenta': magenta, 'blot_lum': abs(la / lb - 1),
            'blot_chroma': abs(np.median(chroma(out[a])) - np.median(chroma(out[b]))), 'na': a.sum(), 'nb': b.sum()}


# the bug, reproduced: old shaders (their levelAt) with the old levels; the new shaders without the gate behave the same
old_tree = BASE if BASE is not None else SH
out_old = recover(old_tree, raw, flags, WP, LO, HI_OLD, None if BASE is not None else 0)
s = sky_stats(out_old, 'old')
check(s['chroma'].mean() > 0.06 and s['magenta'] > 1000 and s['blot_chroma'] > 0.03,
      'bug reproduced (old levels%s): sky chroma %.3f (pink), %d magenta px, blotch chroma step %.3f'
      % (', ' + str(BASE) if BASE is not None else ', gate unset', s['chroma'].mean(), s['magenta'], s['blot_chroma']))

out = recover(SH, raw, flags, WP, LO, HI_NEW, 1)
s = sky_stats(out, 'new')
check(s['chroma'].max() < 0.02, 'sky neutral (R, G at the 0.915 k clip, B measured): max chroma %.4f < 0.02 (input %.3f)'
      % (s['chroma'].max(), chroma(c_in[s['core']]).mean()))
ch_sun = chroma(out[sun_core])
check(ch_sun.max() < 0.02, 'sun (all three saturated) neutral: max chroma %.4f < 0.02' % ch_sun.max())
check(s['magenta'] == 0, 'no magenta inside the mask: %d px with G < 0.97 min(R, B)' % s['magenta'])
check(s['blot_lum'] < 0.01 and s['blot_chroma'] < 0.005,
      'seamless: bit-3 cells vs cells without flags: luminance %.4f < 0.01, chroma %.4f < 0.005 (%d / %d px)'
      % (s['blot_lum'], s['blot_chroma'], s['na'], s['nb']))
lvl = np.percentile(out[s['core']].min(-1), 5)
check(lvl >= 0.99 * clipG / WP[1], 'sky not grey: p5 of the smallest channel %.3f >= the white-balanced G clip %.3f'
      % (lvl, clipG / WP[1]))
cl = erode(CLOUD, 4)
check(np.abs(out[cl] - c_in[cl]).max() <= 2e-3 * c_in[cl].max(),
      'unclipped pink cloud inside the mask passed through: max |diff| %.2e' % np.abs(out[cl] - c_in[cl]).max())
lv = ~dilate(MASK, 7)
check(np.abs(out[lv] - c_in[lv]).max() <= 2e-3 * max(1.0, float(c_in[lv].max())),
      'leaves outside the mask passed through: max |diff| %.2e' % np.abs(out[lv] - c_in[lv]).max())
gl = erode(GLOW, 4)
cg_in, cg_out = chroma(c_in[gl]).mean(), chroma(out[gl]).mean()
check(abs(cg_out / cg_in - 1) < 0.10, 'orange glow (R clipped, G and B measured) keeps its colour: chroma %.3f -> %.3f'
      % (cg_in, cg_out))
out_new = out

# ------------------------------------------------------------------ byte-identity where no channel is off
if BASE is not None:
    # a Bento lamp with the worker's flags (bit 5 where the ultrashort clipped) next to the OnePlus scene
    WPL = np.array([0.354, 1.0, 0.575], np.float32)
    lamp = raw.copy()
    lamp_flags = flags.copy()
    LAMP = (xx - 220) ** 2 / 60.0 ** 2 + (yy - 100) ** 2 / 40.0 ** 2 <= 1
    lamp[LAMP] = np.minimum(np.array([9.0, 8.5, 7.0], np.float32) * (1 + 0.3 * np.sin(xx / 9.0))[LAMP, None], 8.0)
    lamp_flags[LAMP & (lamp.max(-1) >= 7.84)] |= 7 | 32 | 8
    combos = [  # (scene, flags, wp, lo, hi) from the logs, no channel off
        ('vivo / OnePlus all hi off (hi disabled)', raw, flags, WP, (1.0, 1.0, 1.0), (0.0, 0.0, 0.0)),
        ('OnePlus 18:08 hi 7.98 / 7.99 plateau, 7.99 nominal', lamp, lamp_flags, WPL, (1.0, 1.0, 1.0),
         (7.979865, 7.9880958, 7.99138)),
        ('OnePlus 18:10 hi 8.0 nominal x3', lamp, lamp_flags, WPL, (1.0, 1.0, 1.0), (8.0, 8.0, 8.0)),
        ('OnePlus 18:06 no Bento, lo plateau', lamp / 8.0, lamp_flags & ~np.uint8(48), WPL,
         (0.9996566, 0.99992037, 0.9995305), (0.0, 0.0, 0.0)),
        ('OnePlus 18:02 levels, OLD fill (gate unset)', raw, flags, WP, LO, HI_OLD),
    ]
    for name, sc, fg, wp, lo, hi in combos:
        a = recover(BASE, sc, fg, wp, lo, hi, None, raw16=True)
        b = recover(SH, sc, fg, wp, lo, hi, 0, raw16=True)
        same = all(np.array_equal(x, y) for x, y in zip(a, b))
        diff = sum(int((x != y).sum()) for x, y in zip(a, b))
        check(same, 'byte-identity vs %s: %s (chroma 8/32/128, nicergb, clipband: %d differing halves)' % (BASE, name, diff))

if REPORT:
    try:
        from PIL import Image
        for name, v in (('old', out_old), ('new', out_new)):
            v = v / (1 + v / 6.0)
            Image.fromarray((np.clip(v / v.max(), 0, 1) ** (1 / 2.2) * 255).astype(np.uint8)).save(
                'check_highlight_one_channel_off_%s.png' % name)
    except ImportError:
        pass
if fail:
    print('highlight one channel off FAIL: %d check(s)' % len(fail))
    sys.exit(1)
print('highlight one channel off PASS')
