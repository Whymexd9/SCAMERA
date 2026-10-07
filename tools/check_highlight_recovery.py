#!/usr/bin/env python3
"""Per-channel highlight recovery of the LMC hybrid (VivoNiceRgb) on the SHIPPED shaders, wired as VivoNiceRgb.Run does:
hlrecovery/chanprep -> hlrecovery/reduce x2 -> vivohdr/nicergb (hlModeU 1) -> vivohdr/clipband, with the worker's clip
flags, 1x grid (8-pixel chroma blocks), chroma limit 0.35, defringe 0.85, band 1.

Synthetic scene after the vivo X200 Ultra lamp of 2026-10-07 (a white rounded rectangle, peach and cyan patches in the
clipped diffuser, a dotted dark-red line along its clip edge): a Bento highlight (k = 8, white point 0.375 / 1 / 0.5625)
whose light runs from a warm unclipped rim (G/B clipped nowhere) through a zone where only G clipped into a core where G
and B clipped (R never does), with an unclipped bluish top edge, a slightly cyan glow along its left edge (the censored
colours next to a dark end cap) and a dotted red line on the dark side of that edge (border pixels whose colours came
from different sides of the edge). Flags as the worker writes them: clipped cells -> bits 0-2 (cellClip: every colour),
5 (ultrashort), 3 and 4; bit 3 (+ 6, the worker's clip-border pass) over the whole Bento area where the base frames
clipped; bit 4 = the Bento mask. Next to it the cases the recovery was tuned for: a red and a green LED and an orange
light (one clipped channel; with cellClip the others are flagged at their real values), a blue screen inside the Bento
mask (bit 3 everywhere, B near its clip).

Checks:
- the lamp has no block-shaped hue or level jumps: every 1-px OKLab ab step inside it (input edges excluded) < 0.01,
  every 1-px log-luminance step beyond the true scene's own step < 0.02;
- the core where two channels clipped is neutral white (chroma < 0.02), never darker than the one-channel zone next to it;
- no magenta: G >= 0.95 min(R, B) wherever the recovery acted;
- the recovered core joins its unclipped rim: hue step across the boundary of the clipped cells < 0.01 per pixel;
- the dotted red line on the dark side of the clip edge disappears (dots vs the pixels between them < 0.02);
- the LEDs, the orange light and the blue screen keep their colour (chroma within 3 % of the input);
- pixels without flags away from the band are passed through unchanged (white balance only).
Usage: check_highlight_recovery.py [--shaders DIR] [--report]   (DIR: another asset shader tree, e.g. an older one)
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

WP = np.array([0.375, 1.0, 0.5625], np.float32)
K = 8.0
W, H = 512, 320


def program(name):
    return ctx.program(vertex_shader=VS, fragment_shader='#version 430\n' + (SH / name).read_text(encoding='utf-8'))


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


def recover(raw, flags, lo=(1.0, 1.0, 1.0), hi=(K, K, K)):
    """VivoNiceRgb.Run, per-channel branch with clip flags and the chroma statistics (scale 1)."""
    h, w = flags.shape
    inp = texture(raw.astype(np.float32))
    gm = texture(np.ones((1, 1, 4), np.float16), 'f2', linear=True)
    ft = texture(flags.astype(np.uint8), 'f1')
    block, step = 8, 1
    common = {'whitePoint': tuple(map(float, WP)), 'inverseSize': (1.0 / w, 1.0 / h), 'cropOffset': (0, 0),
              'clipLoU': lo, 'clipHiU': hi, 'clipFlagsU': 1}
    near = ((w + block - 1) // block, (h + block - 1) // block)
    c8 = draw(program('hlrecovery/chanprep.glsl'), near, {'InputBuffer': inp, 'GainMap': gm, 'ClipFlags': ft},
              dict(common, blockU=block, pxStepU=step))
    wide = ((near[0] + 3) // 4, (near[1] + 3) // 4)
    c8.filter = (moderngl.LINEAR, moderngl.LINEAR)
    c32 = draw(program('hlrecovery/reduce.glsl'), wide, {'InputBuffer': c8}, {})
    c32.filter = (moderngl.LINEAR, moderngl.LINEAR)
    c128 = draw(program('hlrecovery/reduce.glsl'), ((wide[0] + 3) // 4, (wide[1] + 3) // 4), {'InputBuffer': c32}, {})
    c128.filter = (moderngl.LINEAR, moderngl.LINEAR)
    nice = draw(program('vivohdr/nicergb.glsl'), (w, h),
                {'InputBuffer': inp, 'GainMap': gm, 'ClipFlags': ft, 'Chroma8': c8, 'Chroma32': c32, 'Chroma128': c128},
                dict(common, hlModeU=1, hlStrengthU=1.0, chromaU=1, blockU=block, chromaLimitU=0.35, defringeU=0.85))
    band = draw(program('vivohdr/clipband.glsl'), (w, h), {'InputBuffer': nice, 'ClipFlags': ft},
                {'radiusU': 3, 'zoneU': 2, 'strengthU': 1.0})
    out = read(band)
    for t in (inp, gm, ft, c8, c32, c128, nice, band):
        t.release()
    return out


# ------------------------------------------------------------------ OKLab
M1 = np.array([[0.4122214708, 0.5363325363, 0.0514459929], [0.2119034982, 0.6806995451, 0.1073969566],
               [0.0883024619, 0.2817188376, 0.6299787005]])
M2 = np.array([[0.2104542553, 0.7936177850, -0.0040720468], [1.9779984951, -2.4285922050, 0.4505937099],
               [0.0259040371, 0.7827717662, -0.8086757660]])


def ab(rgb):
    """OKLab a, b of the chromaticity (scaled to mean 1): hue and chroma independent of the level."""
    x = np.maximum(rgb, 0) / np.maximum(rgb.mean(-1, keepdims=True), 1e-6)
    return (np.cbrt(np.maximum(x @ M1.T, 0)) @ M2.T)[..., 1:]


def dilate(m, r):
    out = m.copy()
    for dy in range(-r, r + 1):
        for dx in range(-r, r + 1):
            out |= np.roll(np.roll(m, dy, 0), dx, 1)
    return out


def erode(m, r):
    return ~dilate(~m, r)


# ------------------------------------------------------------------ scene (white-balanced truth -> worker raw + flags)
yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
wb = np.full((H, W, 3), 0.04, np.float32)                      # dark wall
LAMP = (xx >= 96) & (xx < 352) & (yy >= 32) & (yy < 288)
CAP = (xx >= 64) & (xx < 96) & (yy >= 32) & (yy < 288)          # dark end cap left of the diffuser
t = np.clip((yy - 32) / 256.0, 0, 1)                            # 0 at the top of the lamp, 1 at its bottom
ramp = t / 0.1
level = (19.0 - 16.0 * t ** 1.3) * (0.35 + 0.65 * np.clip(ramp, 0, 1) ** 2 * (3 - 2 * np.clip(ramp, 0, 1)))
warm = np.array([1.35, 1.0, 0.74], np.float32)                   # the unclipped rim at the bottom: the lamp's warm glow
white = np.array([1.06, 1.0, 0.95], np.float32)
cool = np.array([0.95, 1.0, 1.08], np.float32)                   # the unclipped top edge of the diffuser: bluish white
tint = white + (warm - white) * np.clip((t - 0.15) / 0.75, 0, 1)[..., None]
tint = tint + (cool - white) * np.clip(1 - (t - 0.02) / 0.1, 0, 1)[..., None]
across = 1.0 - 0.10 * ((xx - 224) / 128.0) ** 2                  # a little darker towards the sides
wb[LAMP] = (tint * (level * across)[..., None])[LAMP]
glow = LAMP & (xx < 104)                                         # cyan cast of the censored colours along the dark edge
wb[glow] *= np.array([0.80, 1.10, 1.08], np.float32)
wb[CAP] = 0.15
# dotted line on the dark side of the edge: every other row a red-ish border pixel (R from the bright side)
DOTS = (xx == 95) & (yy >= 40) & (yy < 280) & ((yy.astype(int) % 4) < 2)
wb[DOTS] = np.array([0.9, 0.30, 0.35], np.float32)
# the cases the recovery was tuned for (true white-balanced colours)
disc = lambda cx, cy, r: (xx - cx) ** 2 + (yy - cy) ** 2 <= r * r
RED, GREEN, ORANGE = disc(410, 60, 14), disc(410, 130, 14), disc(410, 200, 14)
wb[RED] = (30.0, 0.6, 0.25)
wb[GREEN] = (0.2, 30.0, 0.6)
wb[ORANGE] = (30.0, 6.0, 1.0)
SCREEN = (xx >= 384) & (xx < 448) & (yy >= 240) & (yy < 300)
wb[SCREEN] = (0.9, 2.7, 7.6)                                     # blue screen, B near the ultrashort clip

raw = np.minimum(wb * WP, K)                                    # camera channels, the ultrashort's clip at k
clipped = (raw >= 0.98 * K).any(-1)                             # clipped cells (cellClip: every colour of the cell)
base = (raw >= 0.98).any(-1)                                    # where the base frames clipped
bento = dilate(base, 4)
flags = np.zeros((H, W), np.uint8)
flags[bento] |= 16
flags[bento & dilate(base, 1)] |= 8 | 64                        # base frame's clipped samples at weight 1 - m
flags[dilate(clipped, 2)] |= 8 | 64                             # the clip border
flags[clipped] |= 7 | 32 | 8
flags[DOTS] |= 8 | 16 | 64

out = recover(raw, flags)
c = raw / WP
fail = []


def check(ok, msg):
    print(('PASS ' if ok else 'FAIL ') + msg)
    if not ok:
        fail.append(msg)


# 1) no block-shaped hue / level jumps inside the lamp (its own outer border and real edges of the scene excluded)
inner = erode(LAMP & ~glow, 3)
a = ab(out)
li = np.log(np.maximum(wb @ np.array([0.2126, 0.7152, 0.0722], np.float32), 1e-4))  # the true scene's level
lo_ = np.log(np.maximum(out @ np.array([0.2126, 0.7152, 0.0722], np.float32), 1e-4))
steps, lsteps = [], []
for dy, dx in ((0, 1), (1, 0)):
    da = np.linalg.norm(a[dy:, dx:] - a[:H - dy, :W - dx], axis=-1)
    di = np.abs(li[dy:, dx:] - li[:H - dy, :W - dx])
    m = inner[dy:, dx:] & inner[:H - dy, :W - dx]
    steps.append(da[m & (di < 0.15)])
    lsteps.append((np.abs(lo_[dy:, dx:] - lo_[:H - dy, :W - dx]) - di)[m & (di < 0.15)])
steps, lsteps = np.concatenate(steps), np.concatenate(lsteps)
check(steps.max() < 0.01, 'lamp hue continuity: max 1-px OKLab step %.4f (p99.9 %.4f) < 0.01' % (steps.max(), np.percentile(steps, 99.9)))
check(lsteps.max() < 0.02, 'lamp level continuity: max 1-px log-luminance step beyond the true scene step %.4f < 0.02' % lsteps.max())

# 2) the two-channel-clipped core is neutral and not darker than the one-channel zone next to it
two = inner & (raw[..., 1] >= 0.98 * K) & (raw[..., 2] >= 0.98 * K) & (raw[..., 0] < 0.9 * K)
one = inner & (raw[..., 1] >= 0.98 * K) & (raw[..., 2] < 0.85 * K)
chroma_two = np.linalg.norm(a[two], axis=-1)
check(two.sum() > 1000 and chroma_two.max() < 0.02, 'two-channel core neutral: max chroma %.4f < 0.02 (%d px)' % (chroma_two.max(), two.sum()))
lum = out @ np.array([0.2126, 0.7152, 0.0722], np.float32)
check(np.percentile(lum[two], 5) >= np.percentile(lum[one], 95) * 0.98,
      'core not darker than the one-channel zone: p5 %.2f vs p95 %.2f' % (np.percentile(lum[two], 5), np.percentile(lum[one], 95)))

# 3) no magenta where the recovery acted
acted = inner & ((flags & 7) != 0)
o = out[acted]
check((o[:, 1] >= 0.95 * np.minimum(o[:, 0], o[:, 2])).all(), 'no magenta: min G/min(R,B) %.3f >= 0.95' % (o[:, 1] / np.minimum(o[:, 0], o[:, 2])).min())

# 4) the recovered core joins its unclipped rim (hue across the boundary of the clipped cells, along the lamp's columns)
col = np.arange(150, 300)
last = [int(np.max(np.where(clipped[:, x])[0])) for x in col]  # the lowest clipped row of every column
jump = max(float(np.linalg.norm(a[r + 1, x] - a[r, x])) for r, x in zip(last, col))
check(jump < 0.01, 'core joins its rim: hue step across the clip boundary %.4f < 0.01' % jump)

# 5) the dotted line on the dark side of the clip edge: no alternation along the edge (dots against the pixels between them)
between = (xx == 95) & (yy >= 40) & (yy < 280) & ~DOTS
alt = np.linalg.norm(a[DOTS].mean(0) - a[between].mean(0))
check(alt < 0.02, 'dark-side dotted line: chroma alternation along the edge %.4f < 0.02' % alt)

# 6) saturated lights and the blue screen keep their colour
for name, m in (('red LED', RED), ('green LED', GREEN), ('orange', ORANGE), ('blue screen', SCREEN)):
    core = erode(m, 3)
    cin = np.linalg.norm(ab(np.minimum(c[core], (K / WP)[None])), axis=-1).mean()
    cout = np.linalg.norm(a[core], axis=-1).mean()
    check(abs(cout / cin - 1) < 0.03, '%s keeps its colour: chroma %.3f -> %.3f' % (name, cin, cout))

# 7) unflagged pixels away from the band: white balance only
away = ~dilate((flags & 15) != 0, 3)
check(np.abs(out[away] - c[away]).max() <= 2e-3 * max(1.0, float(c[away].max())), 'pass-through away from the flags: max |diff| %.2e'
      % np.abs(out[away] - c[away]).max())

if REPORT:
    try:
        from PIL import Image
        v = out / (1 + out / 20.0) / 10.0
        Image.fromarray((np.clip(v, 0, 1) ** (1 / 2.2) * 255).astype(np.uint8)).save('check_highlight_recovery.png')
    except ImportError:
        pass
if fail:
    print('highlight recovery FAIL: %d check(s)' % len(fail))
    sys.exit(1)
print('highlight recovery PASS')
