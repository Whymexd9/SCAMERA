#!/usr/bin/env python3
"""Clip guard (P368): camera RGB that sits at one common ceiling is not multiplied into a colour by the white balance and the
colour matrix. The SCAM Hybrid highlight recovery (ScamRgb) on the SHIPPED shaders, wired as ScamRgb.Run does
(hlrecovery/chanprep -> hlrecovery/reduce x2 -> scamhdr/scamrgb hlModeU 1 -> scamhdr/clipband) with the worker's clip flags,
then the colour chain of the WHY tone: why/low (sensorToIntermediate, the colour matrix) and why/combine (HEADROOM_WIDE,
HL_LANDING), as WhyStats / WhyCombine set them.

Owner, 2026-10-27 (OPPO Find X8 Ultra, build 31132, a frosted ceiling bulb): «у CCM» - a flat magenta disc with a blue halo in
03-WhyCombine. The shot: Bento k = 8 from three ISO 100 ultrashort frames; their RAW saturates at ~0.90 - 0.95 of the white,
under the worker's 0.98 thresholds (BENTO usClipped 0 / 0 / 0.0003, CLIP FLAGS ultrashort=0, R/G/B 36 - 41 px), and the
flicker tile field (P290: factors 0.79 - 1.08) and the gain against N (P310, x0.5 strength) moved that clip to 6.0 - 7.1 of k:
camera R, G, B of the core within 3 % (6.37, 6.55, 6.39), flags = bit 4 only, ScamRgb hi = off -> levelAt -1 -> no recovery.
White balance (x1.83, x1, x2.39) -> (11.6, 6.5, 15.3), the ISP colour matrix -> (13.9, 4.0, 19.9), the highlight landing
(P159: a coloured light lands on its colour at full brightness) -> display (0.85, 0.49, 1.0).
Scene: that bulb (a cool-white LED, camera 0.46 : 1 : 0.48, 40 x the base white, a soft edge) clipped at the ultrashort
ceiling 8 x 0.92 x a smooth flicker field (0.8 .. 1.0, the channels within +-3.5 %) x 0.95, its unclipped glow and a warm wall,
the shot's white point and colour matrix; inside the Bento area a real lavender LED (camera min / max 0.62) and a pink LED
ring, both measured by the ultrashort frames. Flags: bit 4 over the N clip + 5 px (the shot's), no clip bits.
Checks:
- the bulb's core is neutral after ScamRgb, after the colour matrix (why/low) and on the display (why/combine): OKLab chroma
  < 0.01 / 0.01 / 0.02 (before: ~0.13 / ~0.2 / ~0.2, magenta);
- no magenta / purple anywhere in the core and the rim (display hue 280 - 360 deg with chroma > 0.03);
- the guard alone (usWhiteU 0 = the shader before P368) reproduces the magenta core: the scene is the case;
- bit for bit: every pixel outside the guard's support (camera min / max <= 0.65 or max <= 1.05 x the base white; no clip
  bits here) equals the run without the guard - the glow, the wall, the lavender LED, the pink ring;
- the worker flagged the core (bits 0-2, 5) but its level k is above the moved clip (hi 8 nominal, data at 0.75 k): neutral;
- with --baseline DIR (an older asset shader tree, e.g. before P368): the run without the guard equals it bit for bit, and so
  does the guarded run outside the support.
--shot DIR: also the owner's diagnostic dump (02-after-ivst.pfm of SCAM-20261027-175141): its core goes neutral.
Usage: check_highlight_ceiling.py [--shaders DIR] [--baseline DIR] [--shot DIR] [--report]
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
SHOT = Path(sys.argv[sys.argv.index('--shot') + 1]) if '--shot' in sys.argv else None
REPORT = '--report' in sys.argv

try:
    ctx = moderngl.create_standalone_context(backend='egl', require=430)
except Exception:
    ctx = moderngl.create_standalone_context(require=430)
VS = '#version 430\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.0-1.0,0,1);}'
VS_ES = '#version 300 es\nvoid main(){vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));gl_Position=vec4(p*2.-1.,0,1);}'
_progs = {}


def source(tree, name, defines=()):
    text = (tree / name).read_text(encoding='utf-8').replace('\r\n', '\n')
    out = []
    for line in text.split('\n'):
        if '#import' in line and '//' not in line:
            imp = line.replace('#', '').replace(' ', '_').strip()
            out.append((tree / 'utils' / (imp + '.glsl')).read_text(encoding='utf-8').replace('\r\n', '\n'))
            continue
        for k, v in defines:
            if line.strip().startswith('#define') and (' ' + k + ' ') in line + ' ':
                line = '#define %s %s' % (k, v)
        out.append(line)
    return '\n'.join(out)


def program(tree, name, defines=()):
    key = (str(tree), name, defines)
    if key not in _progs:
        es = name.startswith('why/')   # the WHY programs as check_why_highlights builds them (GLSL ES), the others as 4.30
        _progs[key] = ctx.program(vertex_shader=VS_ES if es else VS,
                                  fragment_shader=('#version 300 es\n' if es else '#version 430\n') + source(tree, name, defines))
    return _progs[key]


def texture(a, dtype='f4', linear=False):
    a = np.ascontiguousarray(a)
    t = ctx.texture((a.shape[1], a.shape[0]), 1 if a.ndim == 2 else a.shape[2], a.tobytes(), dtype=dtype)
    f = moderngl.LINEAR if linear else moderngl.NEAREST
    t.filter = (f, f)
    t.repeat_x = t.repeat_y = False
    return t


def draw(p, size, textures, uniforms, comps=4, dtype='f2', linear=False):
    out = ctx.texture(size, comps, dtype=dtype)
    f = moderngl.LINEAR if linear else moderngl.NEAREST
    out.filter = (f, f)
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


def read(t, comps=3):
    dt = np.float16 if t.dtype == 'f2' else np.float32
    return np.frombuffer(t.read(), dt).reshape(t.height, t.width, t.components)[..., :comps].astype(np.float32)


def recover(tree, raw, flags, wp, lo, hi, us_white):
    """ScamRgb.run, per-channel branch with clip flags and the chroma statistics (scale 1); us_white: usWhiteU (P368)."""
    h, w = flags.shape
    inp = texture(np.dstack([raw, np.ones((h, w, 1), np.float32)]).astype(np.float32))
    gm = texture(np.ones((1, 1, 4), np.float16), 'f2', linear=True)
    ft = texture(flags.astype(np.uint8), 'f1')
    block = 8
    common = {'whitePoint': tuple(map(float, wp)), 'inverseSize': (1.0 / w, 1.0 / h), 'cropOffset': (0, 0),
              'clipLoU': tuple(map(float, lo)), 'clipHiU': tuple(map(float, hi)), 'clipFlagsU': 1, 'usWhiteU': float(us_white)}
    near = ((w + block - 1) // block, (h + block - 1) // block)
    c8 = draw(program(tree, 'hlrecovery/chanprep.glsl'), near, {'InputBuffer': inp, 'GainMap': gm, 'ClipFlags': ft},
              dict(common, blockU=block, pxStepU=1), linear=True)
    wide = ((near[0] + 3) // 4, (near[1] + 3) // 4)
    c32 = draw(program(tree, 'hlrecovery/reduce.glsl'), wide, {'InputBuffer': c8}, {}, linear=True)
    c128 = draw(program(tree, 'hlrecovery/reduce.glsl'), ((wide[0] + 3) // 4, (wide[1] + 3) // 4), {'InputBuffer': c32}, {},
                linear=True)
    scam = draw(program(tree, 'scamhdr/scamrgb.glsl'), (w, h),
                {'InputBuffer': inp, 'GainMap': gm, 'ClipFlags': ft, 'Chroma8': c8, 'Chroma32': c32, 'Chroma128': c128},
                dict(common, hlModeU=1, hlStrengthU=1.0, chromaU=1, blockU=block, chromaLimitU=0.35, defringeU=0.85,
                     signedU=1))
    band = draw(program(tree, 'scamhdr/clipband.glsl'), (w, h), {'InputBuffer': scam, 'ClipFlags': ft},
                {'radiusU': 3, 'zoneU': 2, 'strengthU': 1.0})
    out = read(band)
    for t in (inp, gm, ft, c8, c32, c128, scam, band):
        t.release()
    return out


# ---- the shot's colour chain (Parameters of 17:51:41: white point, sensorToProPhoto = 1 / white point, the ISP transform)
WP = np.array([0.54785156, 1.0, 0.41796875])
CCM = np.array([[200, -62, -10], [-17, 172, -27], [8, -70, 191]], np.float64) / 128.0
CCM /= CCM.sum(1, keepdims=True)            # Parameters.normalize
K = 8.000003
AE, CLIP = 1.2729815, 4.0


def colmat(m):
    return tuple(np.asarray(m, np.float64).reshape(3, 3).T.flatten())   # GLProg.setVar transposes a row-major float[9]


COLOUR = {'sensorToIntermediate': colmat(np.diag(1.0 / WP)), 'intermediateToSRGB': colmat(CCM),
          'neutralPointU': tuple(map(float, WP)), 'inScaleU': 1.0}


def inverse_aces(y, toe=0.05):
    b, e = max(toe * 0.75, 0.005), max(0.14 - (toe - 0.04) * 0.8, 0.03)
    y = np.clip(y, 0.0, 2.51 / 2.43 - 0.01)
    A, B, C = 2.43 * y - 2.51, 0.59 * y - b, e * y
    return np.maximum((-B - np.sqrt(np.maximum(B * B - 4 * A * C, 0))) / (2 * A - 1e-6) / 1.5, 0.0)


def fused_for(target):
    """The fused display luma whose inverse ACES is the target (WhyCombine's gain = 1: the colour reaches the tone as is)."""
    lo, hi = np.zeros_like(target), np.full_like(target, 2.51 / 2.43 - 0.0101)
    for _ in range(50):
        mid = (lo + hi) / 2
        below = inverse_aces(mid) < target
        lo, hi = np.where(below, mid, lo), np.where(below, hi, mid)
    return lo ** (1 / 2.2)


def colour_chain(tree, wb, signed=0):
    """why/low (factor 1: the colour matrix per pixel; factor 2: whyLow) and why/combine with HL_LANDING / HEADROOM_WIDE
    (signed: P370 signedHueU, 0 = off / unset, 1 = the shipped default)."""
    h, w = wb.shape[:2]
    inp = texture(np.dstack([wb, np.ones((h, w, 1), np.float32)]).astype(np.float16), 'f2')
    gm = texture(np.ones((1, 1, 4), np.float16), 'f2', linear=True)
    ccm = read(draw(program(tree, 'why/low.glsl'), (w, h), {'InputBuffer': inp, 'GainMap': gm}, dict(COLOUR, factorU=1)))
    lw, lh = (w + 1) // 2, (h + 1) // 2
    low_t = draw(program(tree, 'why/low.glsl'), (lw, lh), {'InputBuffer': inp, 'GainMap': gm}, dict(COLOUR, factorU=2))
    low = read(low_t)
    y = np.maximum(low @ np.array([0.2126, 0.7152, 0.0722]), low.max(-1) * 0.5) * AE
    fused = texture(np.dstack([fused_for(y.astype(np.float64)).astype(np.float32)] * 4), 'f4')
    hr = float(max(low.max(), CLIP + 0.1))
    hb = draw(program(tree, 'why/hlmask.glsl'), (lw, lh), {'InputBuffer': low_t}, {'invClipU': 1.0 / hr, 'dirU': (0, 0)},
              comps=1, dtype='f2', linear=True)
    sigma = max(1.0, 0.012 * lw)
    ha = draw(program(tree, 'why/hlmask.glsl'), (lw, lh), {'InputBuffer': hb}, {'dirU': (1, 0), 'sigmaU': sigma}, 1, 'f2', True)
    hb = draw(program(tree, 'why/hlmask.glsl'), (lw, lh), {'InputBuffer': ha}, {'dirU': (0, 1), 'sigmaU': sigma}, 1, 'f2', True)
    defs = (('HL_LANDING', '1'), ('HEADROOM_WIDE', '1'))
    out = draw(program(tree, 'why/combine.glsl', defs), (w, h),
               {'InputBuffer': inp, 'GainMap': gm, 'WhyLow': low_t, 'WhyColour': low_t, 'WhyFused': fused,
                'WhyDetailRef': low_t, 'WhyLumaS': low_t, 'WhyHl': hb},
               dict(COLOUR, detailRefU=0, sharpU=0, fullInputU=1, fU=2, colourFU=2, signedColourU=1, signedHueU=signed, aeU=AE, clipU=CLIP,
                    headroomU=1, hlWhiteU=0.15, hlSoftU=0.7, toeU=0.05, gammaInvU=1 / 2.2, macroU=1.1, vibU=(0.0, 0.4, 0.2),
                    vibHlU=1.0, detailGainU=0.0, deltaChromaU=1.0, filmToeU=0.1, agxAU=(2.856, 1.35, 1.6, 1.0),
                    agxBU=(-8.5, 3.5, 0.3, 4.0), ditherU=0, hrU=hr, hrKneeU=0.5, hrRoomU=0.4, hrDetailU=0.0),
               dtype='f4')
    return ccm, read(out)


M1 = np.array([[0.4122214708, 0.5363325363, 0.0514459929], [0.2119034982, 0.6806995451, 0.1073969566],
               [0.0883024619, 0.2817188376, 0.6299787005]])
M2 = np.array([[0.2104542553, 0.7936177850, -0.0040720468], [1.9779984951, -2.4285922050, 0.4505937099],
               [0.0259040371, 0.7827717662, -0.8086757660]])


def lab(lin):
    return np.cbrt(np.maximum(np.asarray(lin, np.float64) @ M1.T, 0)) @ M2.T


def chroma_of(rgb):
    """OKLab chroma of the chromaticity (scaled to max 1): independent of the level (linear values above 1)."""
    x = np.maximum(rgb, 0) / np.maximum(rgb.max(-1, keepdims=True), 1e-9)
    o = lab(x)
    return np.hypot(o[..., 1], o[..., 2])


def display_lab(d):
    o = lab(np.power(np.clip(d, 0, 1), 2.2))
    return np.hypot(o[..., 1], o[..., 2]), np.degrees(np.arctan2(o[..., 2], o[..., 1])) % 360


def scene(H=240, W=320):
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float64)
    r = np.hypot(yy - 120.0, xx - 160.0)
    wall = np.array([0.050, 0.044, 0.034]) * (1 + 0.05 * np.sin(xx / 3.0) * np.sin(yy / 4.0))[..., None]
    q = np.array([0.46, 1.0, 0.48])                             # cool-white LED in camera RGB (white-balanced 0.84 : 1 : 1.15)
    inside = 1.0 / (1.0 + np.exp((r - 40.0) / 1.5))
    level = 40.0 * inside + 0.6 * np.exp(-np.maximum(r - 40.0, 0) / 25.0) * (1 - inside)
    cam = wall * WP + q * level[..., None]
    common = 0.90 + 0.10 * np.sin(xx / 37.0) * np.cos(yy / 29.0)
    field = np.stack([common * (1.0 + 0.035 * np.sin(xx / 23.0 + 2.1 * c) * np.cos(yy / 31.0 + c)) for c in range(3)], -1)
    ceiling = K * 0.92 * field * 0.95
    cam = np.minimum(cam, ceiling)
    cam *= 1 + 0.003 * np.random.default_rng(1).standard_normal(cam.shape)
    lav = np.hypot(yy - 60, xx - 270) < 8
    cam[lav] = np.array([0.62, 1.0, 0.70]) * 4.8
    ring = np.abs(np.hypot(yy - 190, xx - 60) - 18) < 3
    cam[ring] = np.array([0.9, 0.35, 0.55]) * 5.0
    clipN = cam.max(-1) >= 0.98
    bento = clipN.copy()
    for dy in range(-5, 6):
        for dx in range(-5, 6):
            bento |= np.roll(np.roll(clipN, dy, 0), dx, 1)
    flags = np.where(bento, 16, 0).astype(np.uint8)
    return cam.astype(np.float32), flags, r, lav, ring, ceiling


def support(raw, base=1.0, margin=1e-3):
    """Pixels the guard may touch (scamrgb ceilingShare > 0), with a margin for float rounding at the ramps' ends."""
    m = raw.max(-1)
    e = raw.min(-1) / np.maximum(m, 1e-9)
    return (m > 1.05 * base - margin) & (e > 0.65 - margin)


fail = []


def check(ok, msg):
    print(('PASS ' if ok else 'FAIL ') + msg)
    if not ok:
        fail.append(msg)


cam, flags, r, lav, ring, ceiling = scene()
LO, HI = (1.0, 1.0, 1.0), (0.0, 0.0, 0.0)          # the shot: lo nominal, hi off (no plateau, usClipped 0)
new = recover(SH, cam, flags, WP, LO, HI, K)
off = recover(SH, cam, flags, WP, LO, HI, 0.0)     # usWhiteU 0: the guard off = the shader before P368
core, rim = r < 30, r < 46
sup = support(cam)
ccm_new, disp_new = colour_chain(SH, new)
ccm_off, disp_off = colour_chain(SH, off)
c_new, h_new = display_lab(disp_new)
c_off, h_off = display_lab(disp_off)
magenta = lambda c, h: int(((h > 280) & (h < 360) & (c > 0.03) & rim).sum())
print('core: ScamRgb chroma %.3f -> %.3f, colour matrix %.3f -> %.3f, display %.3f -> %.3f (max); magenta / purple px in the '
      'core and rim %d -> %d' % (chroma_of(off[core]).max(), chroma_of(new[core]).max(), chroma_of(ccm_off[core]).max(),
                                 chroma_of(ccm_new[core]).max(), c_off[core].max(), c_new[core].max(), magenta(c_off, h_off),
                                 magenta(c_new, h_new)))
check(chroma_of(off[core]).max() > 0.08 and c_off[core].max() > 0.1,
      'without the guard the core is magenta (ScamRgb chroma %.3f, display %.3f): the scene is the case'
      % (chroma_of(off[core]).max(), c_off[core].max()))
check(chroma_of(new[core]).max() < 0.01, 'core neutral after ScamRgb (chroma %.4f)' % chroma_of(new[core]).max())
check(chroma_of(ccm_new[core]).max() < 0.01, 'core neutral after the colour matrix (why/low, chroma %.4f)' % chroma_of(ccm_new[core]).max())
check(c_new[core].max() < 0.02, 'core neutral on the display (why/combine, chroma %.4f)' % c_new[core].max())
check(magenta(c_new, h_new) == 0, 'no magenta / purple in the core and the rim (%d px)' % magenta(c_new, h_new))
same = np.all(new == off, -1)
check(bool(same[~sup].all()), 'bit for bit outside the guard support: %d of %d pixels differ' % (int((~same[~sup]).sum()), int((~sup).sum())))
for name, mask in (('lavender LED (min / max 0.62)', lav), ('pink LED ring', ring), ('glow (unclipped)', (r > 48) & (r < 120))):
    check(bool(same[mask].all()), '%s unchanged bit for bit (%d px differ)' % (name, int((~same[mask]).sum())))
check(float(new[core].mean()) >= float(off[core].max(-1).mean()) * 0.999,
      'the neutral core is not darker than its brightest white-balanced channel (%.2f vs %.2f)'
      % (new[core].mean(), off[core].max(-1).mean()))

# the worker flagged the core (bits 0-2 and 5) but the level is k, above the moved clip (hi 8 nominal, data at ~0.75 k)
fl2 = flags.copy()
at = cam >= 0.97 * ceiling
fl2[at.any(-1)] |= 32
for c in range(3):
    fl2[at[..., c]] |= np.uint8(1 << c)
hi8 = (K, K, K)
new2 = recover(SH, cam, fl2, WP, LO, hi8, K)
off2 = recover(SH, cam, fl2, WP, LO, hi8, 0.0)
c2n, _ = display_lab(colour_chain(SH, new2)[1])
c2o, _ = display_lab(colour_chain(SH, off2)[1])
check(c2n[core].max() < 0.02, 'flagged core with a level above its clip (hi = k nominal): display chroma %.3f -> %.3f'
      % (c2o[core].max(), c2n[core].max()))

# P370 (review): the same renders with the signed hue on (signedHueU 1, the shipped default): the guarded core stays neutral and
# bit for bit (no channel outside sRGB there), no magenta in the core and the rim, the flagged core neutral
_, disp_sig = colour_chain(SH, new, signed=1)
c_sig, h_sig = display_lab(disp_sig)
eq = np.all(disp_sig == disp_new, -1)
print('signedHueU 1: core chroma %.4f, core bit for bit with signedHueU 0 %s, %d px of the image differ (max %.1f codes)'
      % (c_sig[core].max(), bool(eq[core].all()), int((~eq).sum()), 255 * np.abs(disp_sig - disp_new).max()))
check(c_sig[core].max() < 0.02 and bool(eq[core].all()), 'signedHueU 1: core neutral on the display and as with signedHueU 0 '
      '(chroma %.4f)' % c_sig[core].max())
check(magenta(c_sig, h_sig) == 0, 'signedHueU 1: no magenta / purple in the core and the rim (%d px)' % magenta(c_sig, h_sig))
c2s, _ = display_lab(colour_chain(SH, new2, signed=1)[1])
check(c2s[core].max() < 0.02, 'signedHueU 1: flagged core neutral on the display (chroma %.3f)' % c2s[core].max())

# not covered (reported, not checked): a light that clips G alone at the ultrashort ceiling (R, B measured) and no flag - its
# camera min / max is the light's own above the ceiling's share, nothing on the post side tells that G is a lower bound; the
# worker has to flag it (a measured saturation of the ultrashort frames)
g_only = np.minimum(np.array([0.46, 1.0, 0.48], np.float32) * 10.0, ceiling.astype(np.float32))[core]
print('known limit, G alone at the ultrashort ceiling without a flag (camera min / max %.2f): ScamRgb chroma %.3f, unchanged'
      % (np.median(g_only.min(-1) / g_only.max(-1)), np.median(chroma_of(g_only / WP))))

if BASE is not None:
    base = recover(BASE, cam, flags, WP, LO, HI, 0.0)
    check(np.array_equal(base, off), 'usWhiteU 0 equals %s bit for bit (%d differing values)' % (BASE, int((base != off).sum())))
    check(bool(np.all(base == new, -1)[~sup].all()), 'the guarded run equals %s outside the support' % BASE)
    base2 = recover(BASE, cam, fl2, WP, LO, hi8, 0.0)
    check(np.array_equal(base2, off2), 'flagged scene, usWhiteU 0 equals %s bit for bit' % BASE)

if SHOT is not None:
    def load_pfm(p):
        with open(p, 'rb') as f:
            assert f.readline().strip() == b'PF'
            w, h = map(int, f.readline().split())
            f.readline()
            return np.frombuffer(f.read(), dtype='<f4').reshape(h, w, 3)[::-1].copy()
    iv = load_pfm(SHOT / '02-after-ivst.pfm')
    b = iv.max(-1) >= 0.98
    d = b.copy()
    for dy in range(-2, 3):
        for dx in range(-2, 3):
            d |= np.roll(np.roll(b, dy, 0), dx, 1)
    sf = np.where(d, 16, 0).astype(np.uint8)
    s_new = recover(SH, iv, sf, WP, LO, HI, K)
    s_off = recover(SH, iv, sf, WP, LO, HI, 0.0)
    bulb = (iv.min(-1) > 5.0)
    dn = colour_chain(SH, s_new)[1]
    do = colour_chain(SH, s_off)[1]
    cn, _ = display_lab(dn)
    co, _ = display_lab(do)
    print('shot core (%d px): ScamRgb %s -> %s, display %s -> %s, chroma %.3f -> %.3f'
          % (bulb.sum(), np.round(s_off[bulb].mean(0), 2), np.round(s_new[bulb].mean(0), 2), np.round(do[bulb].mean(0), 3),
             np.round(dn[bulb].mean(0), 3), co[bulb].max(), cn[bulb].max()))
    check(cn[bulb].max() < 0.02, 'the owner\'s bulb (diag 175141) neutral on the display (chroma %.3f)' % cn[bulb].max())
    ringm = bulb.copy()
    for _ in range(3):
        ringm = ringm | np.roll(ringm, 1, 0) | np.roll(ringm, -1, 0) | np.roll(ringm, 1, 1) | np.roll(ringm, -1, 1)
    ringm &= ~bulb
    _, hn = display_lab(dn)
    _, ho = display_lab(do)
    lav_n = int((ringm & (hn > 280) & (cn > 0.02)).sum())
    lav_o = int((ringm & (ho > 280) & (co > 0.02)).sum())
    check(lav_n <= 0.02 * ringm.sum(), 'the owner\'s bulb: its rim (3 px) lavender / magenta (hue 280 - 360, chroma > 0.02) %d -> %d of %d px'
          % (lav_o, lav_n, ringm.sum()))
    check(bool(np.all(s_new == s_off, -1)[~support(iv)].all()), 'the owner\'s shot: bit for bit outside the guard support')

if REPORT:
    try:
        from PIL import Image
        for name, v in (('before', disp_off), ('after', disp_new)):
            Image.fromarray((np.clip(v, 0, 1) * 255 + 0.5).astype(np.uint8)[::-1]).save('check_highlight_ceiling_%s.png' % name)
    except ImportError:
        pass
if fail:
    print('highlight ceiling FAIL: %d check(s)' % len(fail))
    sys.exit(1)
print('highlight ceiling PASS')
