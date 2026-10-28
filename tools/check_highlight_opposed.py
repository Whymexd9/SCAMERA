"""P362: RawTherapee's «Inpaint opposed» highlight reconstruction (rtengine/hilite_recon.cc, highlight_recovery_opposed;
darktable's opposed reconstruction) on the SHIPPED shaders, as ScamRgb.Run wires it: hlrecovery/opposed (compute: the
chrominance correction, group sums added on the CPU as ScamRgb.opposedMeans) -> scamhdr/scamrgb with oppU 1.

Scene: white point 0.5 / 1 / 0.6, base-frame clip 1.0 per channel, 1x grid. A warm lamp (white-balanced R:G:B 1.3 : 1 :
0.7, up to 4x the clip) with a soft glow on a light wall, clipped per channel with the worker's flags (bits 0-2 where that
channel's clipped mean was taken); a second lamp inside a Bento patch (flag bit 4: ultrashort content) that the opposed
reconstruction must leave to the SCAM recovery.
Checks:
- the GPU chrominance equals a numpy reference of the algorithm (dilation |dx|, |dy| <= 3 without the corners, 3x3 means
  in cube-root space, clipdark 0.03 / 0.125 / 0.03, clip 0.987) within 1e-3 (relative 1 %);
- every clipped channel outside the Bento patch is max(value, reference + chrominance) of the reference (2e-3 relative,
  half-float output), the unclipped channels and unclipped pixels white balance only;
- pixels inside the Bento patch and the whole frame with oppU 0 are bit-identical to the SCAM recovery;
- the reconstruction is not darker than the clip and keeps the lamp warm (R > B) where two channels are left;
- never magenta: a clipped G is at least the lower of R and B (owner's rule, as the SCAM recovery);
- a pixel with every channel at its clip is neutral (eased in from 0.85 of the clip in its lowest channel): a blown white
  lamp with a lavender rim stays white (owner's log 2026-10-28, vivo X200 Pro, the base frame alone: a pink lamp); P378:
  with two channels at their clips and the channel left as bright as they are, neutral as well (eased in from 0.85 of the
  second channel's clip) - the reference has the term, the lavender lamp's rim where G clipped and B neared its clip with R
  the brightest goes towards neutral with it.
P378 (owner, 2026-10-28: a pink / orange ring round a sun clipped at the sensor floor, «us skipped (sensor floor)», «везде»):
the sun shot's white point (0.4326 / 1 / 0.5986: after the white balance G clips at 0.99, B at 1.65, R at 2.28) and the
chain to the display as WhyCombine draws it for a shot without Bento (why/low with the shot's colour matrix, why/hlmask,
why/combine HL_LANDING, with HL_OPPOSED and ScamRgb's map - scamhdr/suncore MODE 2's block weights of the pixels «Inpaint
opposed» rebuilds, MODE 1's max-gaussian - after P378), before (the reference without the clip band, HL_OPPOSED 0: the shaders
before P378) against after, with the chrominance measured on the scene and the shot's own (1.019 / -0.027 / 0.060,
SCAMERA-debug_2.txt 11:56:55):
- the map equals numpy's: per block the share of the pixels with a channel at 0.987 of its clip and no ultrashort content
  (flags bits 0-3 without 4 / 5; without flags no Bento level), its weight smoothstep(0.05, 0.15, share), the max-gaussian of
  the weights; none with ultrashort content (bit 4 or 5), with a Bento level and no flags, or for 3 x 3 clipped dots;
- a neutral sun (disc and glow) and one in a warm flare (R/G 1.25, B/G 0.75 just outside the band): the clip band (two
  channels clipped, R measured) was warm after ScamRgb (chroma >= 0.008: the scene is the case; hue / chroma reported) and is
  neutral after it (< 0.005) and on the display (< 0.01); the zone where G alone clipped no more coloured on the display than
  before (+0.003); the flare's warm glow the landing paints near full brightness white (< 0.01) - at the frame's own blocks
  and at the shipped 16 / 32 px, and without clip flags (cpu-single);
- coloured light stays coloured: a red sunset rim (R clipped, G and B far below their clips) and an orange lamp (R and G
  clipped, B under half of R) keep the reconstruction (2e-3) and their display hue (within 3 deg) and chroma (>= 0.95 x), the
  lamp's measured rim too; the lamp's blown core lands on white (chroma < 0.005; before: its rim's orange through the landing's
  neighbourhood) - at the frame's own blocks and at 16 / 32 px;
- lamps 1, 2 and 3 blocks wide at 16 and 32 px blocks through the shot's chain (lamp_sizes): orange ones keep their rims, land
  their cores on white from 32 px wide (smaller: no more coloured than before) and come out the same at either block; white ones
  in a warm glow: the glow painted near full brightness white, nowhere more coloured than before;
- the owner's ring (owner_ring: a 1024 px crop of the 4096 x 3072 frame, ae 1.985, clip 4, the fusion's exposures, luminance
  shading x3, the whole frame's mask sigma, 16 px blocks; the aureole reproducing JPEG 42fa2189's radial profile): before the
  ring the landing paints at full brightness (R 255) at chroma >= 0.05, after it white (median < 0.01, p95 < 0.02), the glow's
  chroma rising monotonically to the sky's (no new ring), the far sky unchanged; an orange lamp and a red sunset band in the same
  frame keep their colour, the lamp's core lands on white. This part fails on the shaders before P378 (p378-ring~1).
Usage: check_highlight_opposed.py [--report]
"""
from pathlib import Path
import sys
import numpy as np
import moderngl

ROOT = Path(__file__).resolve().parents[1]
SH = ROOT / 'app/src/main/assets/shaders'
REPORT = '--report' in sys.argv

try:
    ctx = moderngl.create_standalone_context(backend='egl', require=430)
except Exception:
    ctx = moderngl.create_standalone_context(require=430)
VS = '#version 430\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.0-1.0,0,1);}'


def texture(a, dtype='f4', linear=False):
    a = np.ascontiguousarray(a)
    t = ctx.texture((a.shape[1], a.shape[0]), 1 if a.ndim == 2 else a.shape[2], a.tobytes(), dtype=dtype)
    f = moderngl.LINEAR if linear else moderngl.NEAREST
    t.filter = (f, f)
    t.repeat_x = t.repeat_y = False
    return t


def bind(p, textures, uniforms):
    for unit, (k, t) in enumerate(textures.items()):
        t.use(unit)
        if k in p:
            p[k].value = unit
    for k, v in uniforms.items():
        if k in p:
            p[k].value = v


def draw(name, size, textures, uniforms):
    p = ctx.program(vertex_shader=VS, fragment_shader='#version 430\n' + (SH / name).read_text(encoding='utf-8'))
    out = ctx.texture(size, 4, dtype='f2')
    out.filter = (moderngl.NEAREST, moderngl.NEAREST)
    fbo = ctx.framebuffer([out])
    fbo.use()
    ctx.viewport = (0, 0) + size
    bind(p, textures, uniforms)
    ctx.vertex_array(p, []).render(vertices=3)
    r = np.frombuffer(out.read(), np.float16).reshape(size[1], size[0], 4)[..., :3].astype(np.float32)
    fbo.release()
    out.release()
    return r


def opposed_gpu(textures, uniforms, w, h):
    src = (SH / 'hlrecovery/opposed.glsl').read_text(encoding='utf-8').replace(
        '\nLAYOUT\n', '\nlayout(local_size_x = 16, local_size_y = 16, local_size_z = 1) in;\n')
    cs = ctx.compute_shader('#version 430\n' + src)
    gx, gy = (w + 15) // 16, (h + 15) // 16
    buf = ctx.buffer(reserve=6 * gx * gy * 4)
    buf.bind_to_storage_buffer(1)
    bind(cs, textures, uniforms)
    cs.run(gx, gy, 1)
    v = np.frombuffer(buf.read(), np.uint32).reshape(gx * gy, 6)
    s = v[:, :3].copy().view(np.float32).astype(np.float64).sum(0)
    n = v[:, 3:].astype(np.float64).sum(0)
    return np.where(n > 0, s / np.maximum(n, 1), 0.0).astype(np.float32), n


def scene(lavender=False):
    h, w = 96, 128
    wp = np.array([0.5, 1.0, 0.6], np.float32)
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    wall = np.full((h, w, 3), 0.35, np.float32) * np.array([1.05, 1.0, 0.92], np.float32)
    true = wall.copy()
    for cy, cx, r in ((40, 40, 10), (40, 98, 9)):
        d = np.sqrt((yy - cy) ** 2 + (xx - cx) ** 2)
        lvl = 4.0 * np.exp(-np.maximum(d - r * 0.3, 0) ** 2 / (2 * (r * 0.6) ** 2)) + 0.6 * np.exp(-d ** 2 / (2 * (3 * r) ** 2))
        if lavender:  # a white lamp far above the clip, its rim lavender after the white balance (the X200 Pro's ceiling lamp)
            rim = 0.9 * np.exp(-(d - 1.6 * r) ** 2 / (2 * (0.5 * r) ** 2))
            true += (4.0 * (d < 1.4 * r))[..., None] * np.ones(3, np.float32) + rim[..., None] * np.array([1.6, 1.0, 1.4], np.float32)
        else:
            true += lvl[..., None] * np.array([1.3, 1.0, 0.7], np.float32)
    raw_true = true * wp                                   # camera RGB, base-frame white = 1
    raw = np.minimum(raw_true, 1.0)
    flags = np.zeros((h, w), np.uint8)
    for c in range(3):
        flags |= ((raw_true[..., c] >= 1.0).astype(np.uint8) << c)
    clipped_any = flags != 0
    flags[clipped_any] |= 8                               # a clipped sample nearby (the worker marks the border too)
    bento = (xx >= 80)                                    # the second lamp: ultrashort content
    flags[bento & clipped_any] |= 16
    return raw.astype(np.float32), flags, wp, true


def ss(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3 - 2 * t)


def reference(raw, flags, wp, band=True, chroma_in=None):
    """The algorithm in numpy (white-balanced, clip 0.987, darktable's reference at every counted pixel); band False: without
    P378's clip band (the shader before it); chroma_in: the chrominance to apply instead of the measured one."""
    c = np.maximum(raw, 0) / wp
    clipw = 0.987 * 1.0 / wp
    us = (flags & 48) != 0
    lv = ((flags & 15) != 0)                              # levelAt: an unflagged pixel has no level
    clipped = (c >= clipw) & lv[..., None] & ~us[..., None]
    pad = np.pad(c, ((1, 1), (1, 1), (0, 0)), mode='edge')
    m = sum(pad[1 + dy:1 + dy + c.shape[0], 1 + dx:1 + dx + c.shape[1]] for dy in (-1, 0, 1) for dx in (-1, 0, 1)) / 9.0
    m = np.cbrt(np.maximum(m, 0))
    ref = np.stack([0.5 * (m[..., 1] + m[..., 2]), 0.5 * (m[..., 0] + m[..., 2]), 0.5 * (m[..., 0] + m[..., 1])], -1) ** 3
    H, W = flags.shape
    dil = np.zeros_like(clipped)
    pc = np.pad(clipped, ((3, 3), (3, 3), (0, 0)), mode='edge')
    for dy in range(-3, 4):
        for dx in range(-3, 4):
            if abs(dx) == 3 and abs(dy) == 3:
                continue
            dil |= pc[3 + dy:3 + dy + H, 3 + dx:3 + dx + W]
    dark = clipw * np.array([0.03, 0.125, 0.03])
    take = dil & (c > dark) & (c < clipw) & ~us[..., None]
    chroma = np.array([(c[..., k] - ref[..., k])[take[..., k]].mean() if take[..., k].any() else 0.0 for k in range(3)])
    applied = chroma if chroma_in is None else np.asarray(chroma_in, np.float64)
    out = np.where(clipped, np.maximum(c, ref + applied), c)
    g = clipped[..., 1]
    out[..., 1] = np.where(g, np.maximum(out[..., 1], np.minimum(out[..., 0], out[..., 2])), out[..., 1])  # never magenta
    t = c / clipw
    full = ss(0.85, 1.0, np.min(t, -1))                   # every channel at its clip: neutral
    if band:
        # P378: two channels at their clips and the channel left as bright as the clipped ones: neutral (the sun's clip band)
        left = np.take_along_axis(c, np.argmin(t, -1)[..., None], -1)[..., 0]
        full = np.maximum(full, ss(0.85, 1.0, np.sort(t, -1)[..., 1]) * ss(0.80, 0.95, left / np.maximum(c.max(-1), 1e-6)))
    full = full[..., None] * clipped.any(-1)[..., None]
    out = out + (np.max(out, -1, keepdims=True) - out) * full
    return chroma.astype(np.float32), out, clipped, take.sum((0, 1))


def main():
    raw, flags, wp, true = scene()
    h, w = flags.shape
    inp = texture(raw)
    gm = texture(np.ones((1, 1, 4), np.float16), 'f2', linear=True)
    ft = texture(flags, 'f1')
    common = {'whitePoint': tuple(map(float, wp)), 'inverseSize': (1.0 / w, 1.0 / h), 'cropOffset': (0, 0),
              'clipLoU': (1.0, 1.0, 1.0), 'clipHiU': (0.0, 0.0, 0.0), 'clipFlagsU': 1, 'clipHiUnflaggedU': 0}
    gpu_chroma, gpu_n = opposed_gpu({'InputBuffer': inp, 'GainMap': gm, 'ClipFlags': ft}, dict(common, sizeU=(w, h)), w, h)
    ref_chroma, ref_out, clipped, ref_n = reference(raw, flags, wp)
    ok = True
    if not np.allclose(gpu_chroma, ref_chroma, rtol=1e-2, atol=1e-3) or not np.array_equal(gpu_n, ref_n):
        print('chrominance GPU %s (n %s) != reference %s (n %s)' % (gpu_chroma, gpu_n, ref_chroma, ref_n))
        ok = False
    rgb = {'InputBuffer': inp, 'GainMap': gm, 'ClipFlags': ft}
    base = dict(common, hlModeU=1, hlStrengthU=1.0, chromaU=0, blockU=8, chromaLimitU=0.35, defringeU=-1.0, signedU=1)
    on = draw('scamhdr/scamrgb.glsl', (w, h), rgb, dict(base, oppU=1, oppChromaU=tuple(map(float, gpu_chroma))))
    off = draw('scamhdr/scamrgb.glsl', (w, h), rgb, dict(base, oppU=0))
    us = (flags & 48) != 0
    opp = ~us & ((flags & 15) != 0)
    want = ref_out
    err = np.abs(on - want) / np.maximum(np.abs(want), 1e-3)
    if err[opp].max() > 2e-3:
        print('opposed reconstruction off by %.4f (max relative)' % err[opp].max())
        ok = False
    if not np.array_equal(on[us], off[us]):
        print('Bento pixels changed by the opposed path')
        ok = False
    plain = ~opp & ~us
    if np.abs(on[plain] - raw[plain] / wp).max() > 2e-3 * max(1.0, float(np.abs(raw / wp).max())):
        print('unclipped pixels not white balance only')
        ok = False
    clipw = 0.987 / wp
    core = clipped.any(-1) & opp
    if (on[core] < np.minimum(raw[core] / wp, clipw) - 1e-3).any():
        print('reconstruction darker than the clip')
        ok = False
    lav = core & clipped[..., 1]
    if (on[lav][:, 1] < np.minimum(on[lav][:, 0], on[lav][:, 2]) - 2e-3 * on[lav].max()).any():
        print('a clipped G below R and B (lavender)')
        ok = False
    warm = core & (clipped.sum(-1) == 1)
    if warm.any() and not (on[warm][:, 0] > on[warm][:, 2]).all():
        print('a warm lamp with two channels left lost R > B')
        ok = False
    ok = lavender_lamp(common) and ok
    ok = sun_ring() and ok
    ok = owner_ring() and ok
    ok = review_scenes() and ok
    if REPORT:
        t = true[core]
        print('chrominance GPU', gpu_chroma, 'reference', ref_chroma, 'counted', gpu_n)
        print('mean |error| vs truth in the lamp core: opposed %.3f, SCAM %.3f, input %.3f' % (
            np.abs(on[core] - t).mean(), np.abs(off[core] - t).mean(), np.abs(raw[core] / wp - t).mean()))
    for t in (inp, gm, ft):
        t.release()
    print('highlight opposed ' + ('PASS' if ok else 'FAIL'))
    return 0 if ok else 1


def lavender_lamp(common):
    """The blown white lamp with a lavender rim: its core (every channel clipped) neutral, as the numpy reference."""
    raw, flags, wp, _ = scene(lavender=True)
    h, w = flags.shape
    inp, gm, ft = texture(raw), texture(np.ones((1, 1, 4), np.float16), 'f2', linear=True), texture(flags, 'f1')
    gpu_chroma, _ = opposed_gpu({'InputBuffer': inp, 'GainMap': gm, 'ClipFlags': ft}, dict(common, sizeU=(w, h)), w, h)
    _, ref_out, clipped, _ = reference(raw, flags, wp)
    base = dict(common, hlModeU=1, hlStrengthU=1.0, chromaU=0, blockU=8, chromaLimitU=0.35, defringeU=-1.0, signedU=1)
    on = draw('scamhdr/scamrgb.glsl', (w, h), {'InputBuffer': inp, 'GainMap': gm, 'ClipFlags': ft},
              dict(base, oppU=1, oppChromaU=tuple(map(float, gpu_chroma))))
    for t in (inp, gm, ft):
        t.release()
    ok = True
    blown = clipped.all(-1) & ((flags & 48) == 0)
    if not blown.any() or gpu_chroma[0] <= gpu_chroma[1] or gpu_chroma[2] <= gpu_chroma[1]:
        print('lavender lamp: the scene has no blown core or no lavender chrominance (%s)' % gpu_chroma)
        ok = False
    spread = (on[blown].max(-1) - on[blown].min(-1)) / np.maximum(on[blown].max(-1), 1e-6)
    if spread.max() > 2e-3:
        print('lavender lamp: blown core not neutral (max relative spread %.4f)' % spread.max())
        ok = False
    opp = ((flags & 15) != 0) & ((flags & 48) == 0)
    err = np.abs(on - ref_out) / np.maximum(np.abs(ref_out), 1e-3)
    if err[opp].max() > 2e-3:
        print('lavender lamp: reconstruction off the reference by %.4f' % err[opp].max())
        ok = False
    if REPORT:
        print('lavender lamp: chrominance', gpu_chroma, 'blown pixels', int(blown.sum()), 'max spread %.5f' % spread.max())
    return ok


# ---- P378: the sun ring at the sensor floor, to the display
SUN_WP = np.array([0.4326172, 1.0, 0.5986328], np.float32)          # SCAMERA-debug_2.txt 11:56:55 (OPPO Find X8 Ultra)
SUN_CHROMA = (1.0187187, -0.027409997, 0.060291857)                 # that shot's opposed chrominance
SUN_CCM = np.array([[205, -72, -5], [-26, 186, -32], [-5, -73, 206]], np.float64) / 128.0   # its ISP colour matrix
SUN_CCM /= SUN_CCM.sum(1, keepdims=True)                             # Parameters.normalize
VS_ES = '#version 300 es\nvoid main(){vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));gl_Position=vec4(p*2.-1.,0,1);}'
_progs = {}


def program(name, defines=()):
    """A shipped shader with its #import expanded and its defines set, as GLProg hands it over (the WHY ones as GLSL ES)."""
    key = (name, defines)
    if key not in _progs:
        out = []
        for line in (SH / name).read_text(encoding='utf-8').replace('\r\n', '\n').split('\n'):
            if '#import' in line and '//' not in line:
                imp = line.replace('#', '').replace(' ', '_').strip()
                out.append((SH / 'utils' / (imp + '.glsl')).read_text(encoding='utf-8').replace('\r\n', '\n'))
                continue
            for k, v in defines:
                if line.strip().startswith('#define') and (' ' + k + ' ') in line + ' ':
                    line = '#define %s %s' % (k, v)
            out.append(line)
        es = name.startswith('why/')
        _progs[key] = ctx.program(vertex_shader=VS_ES if es else VS,
                                  fragment_shader=('#version 300 es\n' if es else '#version 430\n') + '\n'.join(out))
    return _progs[key]


def pass_to(p, size, textures, uniforms, comps=4, dtype='f2', linear=False):
    out = ctx.texture(size, comps, dtype=dtype)
    f = moderngl.LINEAR if linear else moderngl.NEAREST
    out.filter = (f, f)
    out.repeat_x = out.repeat_y = False
    fbo = ctx.framebuffer([out])
    fbo.use()
    ctx.viewport = (0, 0) + size
    bind(p, textures, uniforms)
    ctx.vertex_array(p, []).render(vertices=3)
    fbo.release()
    return out


def read_tex(t, comps=3):
    dt = np.float16 if t.dtype == 'f2' else np.float32
    return np.frombuffer(t.read(), dt).reshape(t.height, t.width, t.components)[..., :comps].astype(np.float32)


def inverse_aces(y, toe=0.05):
    b, e = max(toe * 0.75, 0.005), max(0.14 - (toe - 0.04) * 0.8, 0.03)
    y = np.clip(y, 0.0, 2.51 / 2.43 - 0.01)
    A, B, C = 2.43 * y - 2.51, 0.59 * y - b, e * y
    return np.maximum((-B - np.sqrt(np.maximum(B * B - 4 * A * C, 0))) / (2 * A - 1e-6) / 1.5, 0.0)


def fused_for(target):
    """The fused display luma whose inverse ACES is the target (WhyCombine's gain 1: the colour reaches the tone as it is)."""
    lo, hi = np.zeros_like(target), np.full_like(target, 2.51 / 2.43 - 0.0101)
    for _ in range(50):
        mid = (lo + hi) / 2
        below = inverse_aces(mid) < target
        lo, hi = np.where(below, mid, lo), np.where(below, hi, mid)
    return lo ** (1 / 2.2)


def colmat(m):
    return tuple(np.asarray(m, np.float64).reshape(3, 3).T.flatten())   # GLProg.setVar transposes a row-major float[9]


OPPOSED_RADIUS = 0.033                                               # ScamRgb.OPPOSED_RADIUS (x the frame's shorter side)
GLOW_TOP = 0.985    # the glow the landing paints near full brightness on the display (the owner's chain: R 255, owner_ring)
# the sun shot's chain (SCAMERA-debug_2.txt 11:56:55: «WHY combine ae=1.9850819 clip=4.0», «WHY fusion em=0.125,1.0,0.35355338,
# 3.0324287 lw=0.8,1.0,0.1,1.0», «lens shading map 17x13: centre=1.0 corner=5.08 edge-mid=2.12» - x3 at the sun near the frame's
# top edge), the highlight mask's sigma of its whole frame (whyLow 2048 px wide)
SHOT_CHAIN = {'ae': 1.9850819, 'clip': 4.0, 'em': (0.125, 1.0, 0.35355338, 3.0324287), 'lsc': 3.0, 'sigma': 0.012 * 2048}


GATE_LO, GATE_HI = 0.74, 0.80                                        # suncore.glsl MODE 3
TO_RENDER = SUN_CCM @ np.diag(1.0 / SUN_WP.astype(np.float64))        # ScamRgb.toRender: camera RGB -> the render's linear sRGB


def opposed_map(raw, flags, block, radius, clip_lo=(1.0, 1.0, 1.0), clip_hi=(0.0, 0.0, 0.0), with_flags=True, to_render=TO_RENDER):
    """ScamRgb.opposedShare: scamhdr/suncore MODE 2's block weights of the pixels «Inpaint opposed» rebuilds (every channel at
    0.987 of its clip, no ultrashort content: flags bits 0-3 without 4 / 5, or without flags no Bento level) - smoothstep(0.05,
    0.15, share) - with the glow's saturation (unclipped pixels from 0.6 of the clip, weighted smoothstep(0.6, 0.95) of their
    brightest channel, 1 - min / max of their render colour), MODE 3's gate per block (the saturation of its nearest glow) and MODE
    1's max-gaussian of the weights along x and y (radius in blocks) carrying the gate of the tap that gives the maximum, against
    numpy. Returns the spread texture (linear, as WhyCombine samples it: .r the nearness, .g the gate), the weights, the worst
    differences (weights, spread) and the gate per block."""
    h, w = flags.shape
    size = ((w + block - 1) // block, (h + block - 1) // block)
    it = texture(np.dstack([raw, np.ones((h, w, 1), np.float32)]).astype(np.float32))
    ft = texture(flags, 'f1')
    uni = {'blockU': block, 'clipFlagsU': 1 if with_flags else 0, 'clipLoU': tuple(map(float, clip_lo)),
           'clipHiU': tuple(map(float, clip_hi)), 'toRenderU': colmat(to_render)}
    wt = pass_to(program('scamhdr/suncore.glsl', (('MODE', '2'),)), size, {'InputBuffer': it, 'ClipFlags': ft}, uni, dtype='f4')
    got_w = read_tex(wt, 4)
    full = 0.1 * block * block
    st = pass_to(program('scamhdr/suncore.glsl', (('MODE', '3'),)), size, {'InputBuffer': wt},
                 {'dirU': (1, 0), 'radiusU': radius, 'finalU': 0, 'glowFullU': full}, dtype='f4')
    gt = pass_to(program('scamhdr/suncore.glsl', (('MODE', '3'),)), size, {'InputBuffer': st},
                 {'dirU': (0, 1), 'radiusU': radius, 'finalU': 1, 'glowFullU': full}, 2, dtype='f4')
    st.release()
    got_g = read_tex(gt, 2)
    half = pass_to(program('scamhdr/suncore.glsl', (('MODE', '1'),)), size, {'InputBuffer': gt}, {'dirU': (1, 0), 'radiusU': radius}, 2)
    out = pass_to(program('scamhdr/suncore.glsl', (('MODE', '1'),)), size, {'InputBuffer': half}, {'dirU': (0, 1), 'radiusU': radius},
                  2, linear=True)
    got_s = read_tex(out, 2)
    for t in (it, ft, wt, gt, half):
        t.release()
    lc = np.asarray(clip_lo if clip_lo[1] > 0 else clip_hi, np.float64)
    level = lc[1] > 0 and (with_flags or clip_hi[1] <= 0)
    elig = ((flags & 15) != 0) & ((flags & 48) == 0) if with_flags else np.ones(flags.shape, bool)
    lc32 = (0.987 * lc).astype(np.float32)
    rebuilt = elig & (raw >= lc32).all(-1) if level else np.zeros(flags.shape, bool)
    unclipped = ~(raw >= lc32).any(-1) & (((flags & 15) == 0) if with_flags else True)
    gw = ss(0.6, 0.95, (raw / lc).max(-1)) * (unclipped & level)
    col = raw.astype(np.float64) @ to_render.T
    gsat = 1.0 - col.min(-1) / np.maximum(col.max(-1), 1e-6)

    def blocks(v):
        pad = np.zeros((size[1] * block, size[0] * block))
        pad[:h, :w] = v
        return pad.reshape(size[1], block, size[0], block).sum((1, 3))
    share = blocks(rebuilt) / blocks(np.ones(flags.shape))
    weight = ss(0.05, 0.15, share)
    bw, bs = blocks(gw), blocks(gw * gsat)
    sat = np.where(bw > 0, bs / np.maximum(bw, 1e-30), 0.0)

    r = int(np.ceil(2.5 * radius))
    taps = np.exp(-(np.arange(-r, r + 1) / radius) ** 2)

    def spread(v, g, axis):
        n = v.shape[axis]
        pv = np.pad(v, [(r, r) if a == axis else (0, 0) for a in range(2)])
        pg = np.pad(g, [(r, r) if a == axis else (0, 0) for a in range(2)])
        cand = np.array([taps[i] * np.take(pv, np.arange(i, i + n), axis) for i in range(2 * r + 1)])
        gs = np.array([np.take(pg, np.arange(i, i + n), axis) for i in range(2 * r + 1)])
        best = cand.argmax(0)
        m = np.take_along_axis(cand, best[None], 0)[0]
        g_ = np.where(m > 0, np.take_along_axis(gs, best[None], 0)[0], 0.0)
        # where two taps nearly tie the GPU's rounding may take the other one: those gates are not compared
        srt = np.sort(cand, 0)
        tie = (srt[-1] - srt[-2]) <= 1e-4 * np.maximum(srt[-1], 1e-30)
        return m, g_, tie
    # MODE 3: each block's light by the saturation of its nearest glow (presence: the glow's weight against a tenth of a block)
    pm, ps_, tie_a = spread(np.clip(bw / full, 0.0, 1.0), sat, 1)
    pm2, ps2, tie_b = spread(pm, ps_, 0)
    gate = np.where(pm2 > 0.05, 1.0 - ss(GATE_LO, GATE_HI, ps2), 1.0)
    gate_sure = ~tie_b
    m1, g1, tie1 = spread(weight, gate, 1)
    want_s, want_g, tie2 = spread(m1, g1, 0)
    sure = ~tie2 & (want_s > 1e-3)
    err_w = max(float(np.abs(got_w[..., 0] - weight).max()), float(np.abs(got_w[..., 1] - share).max()))
    # the glow's sums, its saturation and the gates in float32 against float64: with the spread, at 2e-3
    err_s = max(float(np.abs(got_s[..., 0] - want_s).max()), float(np.abs(got_s[..., 1] - want_g)[sure].max(initial=0.0)),
                float(np.abs(got_w[..., 3] - bw).max() / max(1.0, float(bw.max()))),
                float(np.abs(got_w[..., 2] - sat)[bw > 0].max(initial=0.0)), float(np.abs(got_g[..., 1] - gate)[gate_sure].max(initial=0.0)))
    return out, got_w[..., 0], err_w, err_s, gate


def acescg(v, toe=0.05):
    x = np.maximum(v, 0) * 1.5
    b, e = max(toe * 0.75, 0.005), max(0.14 - (toe - 0.04) * 0.8, 0.03)
    return (x * (2.51 * x + b)) / (x * (2.43 * x + 0.59) + e)


def fused_flat(i, em, lw=(0.8, 1.0, 0.1, 1.0), mono=0.04, center=0.6, smooth=0.25):
    """FusedCurve.fill: the WHY fusion's display luma on a flat area (why/gf.glsl fusedAt of the exposures em with the layer
    weights, WhyTone.LAYER_WEIGHTS) with its P291 monotonic envelope (36 quarter-EV steps down, monoU 0.04), tabulated at 64
    entries per EV and read linearly in log2 I (as the viewfinder's FusedCurve)."""
    def at(v):
        lum = np.maximum(acescg(np.asarray(v, np.float64)[..., None] * np.array(em)), 1e-6) ** (1 / 2.2)
        wt = np.exp(-0.5 * (lum - center) ** 2 / max(smooth * smooth, 0.001)) * np.array(lw) + 1e-6
        return (wt * lum).sum(-1) / wt.sum(-1)
    grid = np.arange(-20.0, 8.0, 1.0 / 64)
    g = 2.0 ** grid
    f = at(g)
    env = f.copy()
    for j in range(1, 37):
        s = j * 0.25 * np.log(2)
        fj = at(g * np.exp(-s))
        env = np.where(fj >= 0.5, np.maximum(env, fj + mono * s), env)
    i = np.asarray(i, np.float64)
    return np.where(i > 0, np.interp(np.log2(np.maximum(i, 2.0 ** -20)), grid, env), at(np.zeros_like(i)))


def display(wb, share=None, chain=None):
    """why/low (the shot's colour matrix), why/hlmask and why/combine with HL_LANDING (and HL_OPPOSED with the map) as
    WhyCombine draws them for a shot without Bento (no HEADROOM_WIDE; clipped RAW: headroomU 1). chain None: ae 1, the ceiling
    at whyLow's maximum, a fused luma that gives the colour back (gain 1), the mask's sigma of this frame, no shading; chain a
    dict (the sun shot's): ae, clip, em (the fusion's exposures: fused_flat), lsc (the luminance shading gain), sigma."""
    h, w = wb.shape[:2]
    colour = {'sensorToIntermediate': colmat(np.diag(1.0 / SUN_WP)), 'intermediateToSRGB': colmat(SUN_CCM),
              'neutralPointU': tuple(map(float, SUN_WP)), 'inScaleU': 1.0}
    inp = texture(np.dstack([wb, np.ones((h, w, 1), np.float32)]).astype(np.float16), 'f2')
    gm = texture(np.full((1, 1, 4), chain['lsc'] if chain else 1.0, np.float16), 'f2', linear=True)
    lw, lh = (w + 1) // 2, (h + 1) // 2
    low_t = pass_to(program('why/low.glsl'), (lw, lh), {'InputBuffer': inp, 'GainMap': gm}, dict(colour, factorU=2))
    low = read_tex(low_t)
    if chain:
        ae, clip, sigma = chain['ae'], chain['clip'], chain['sigma']
        lin = np.maximum(low, 0) * ae
        fz = fused_flat(np.maximum(lin @ np.array([0.2126, 0.7152, 0.0722]), lin.max(-1) * 0.5), chain['em'])
    else:
        ae, clip, sigma = 1.0, float(low.max()), max(1.0, 0.012 * lw)
        fz = fused_for(np.maximum(low @ np.array([0.2126, 0.7152, 0.0722]), low.max(-1) * 0.5).astype(np.float64))
    fused = texture(np.dstack([fz.astype(np.float32)] * 4), 'f4')
    hb = pass_to(program('why/hlmask.glsl'), (lw, lh), {'InputBuffer': low_t}, {'invClipU': 1.0 / clip, 'dirU': (0, 0)}, 1, 'f2', True)
    ha = pass_to(program('why/hlmask.glsl'), (lw, lh), {'InputBuffer': hb}, {'dirU': (1, 0), 'sigmaU': sigma}, 1, 'f2', True)
    hb2 = pass_to(program('why/hlmask.glsl'), (lw, lh), {'InputBuffer': ha}, {'dirU': (0, 1), 'sigmaU': sigma}, 1, 'f2', True)
    defs = (('HL_LANDING', '1'),) + ((('HL_OPPOSED', '1'),) if share is not None else ())
    tex = {'InputBuffer': inp, 'GainMap': gm, 'WhyLow': low_t, 'WhyColour': low_t, 'WhyFused': fused, 'WhyDetailRef': low_t,
           'WhyLumaS': low_t, 'WhyHl': hb2}
    uni = dict(colour, detailRefU=0, sharpU=0, fullInputU=1, fU=2, colourFU=2, signedColourU=1, aeU=ae, clipU=clip, headroomU=1,
               hlWhiteU=0.15, hlSoftU=0.7, toeU=0.05, gammaInvU=1 / 2.2, macroU=1.1, vibU=(0.0, 0.4, 0.2), vibHlU=1.0,
               detailGainU=0.0, deltaChromaU=1.0, filmToeU=0.1, agxAU=(2.7, 1.35, 1.6, 1.0), agxBU=(-8.5, 3.5, 0.3, 4.0), ditherU=0)
    if share is not None:
        t, block = share
        tex['OppShare'] = t
        uni['oppGridU'] = (w / float(t.width * block), h / float(t.height * block))
    out = pass_to(program('why/combine.glsl', defs), (w, h), tex, uni, dtype='f4')
    res = read_tex(out)
    for t in (inp, gm, low_t, fused, hb, ha, hb2, out):
        t.release()
    return res


M1 = np.array([[0.4122214708, 0.5363325363, 0.0514459929], [0.2119034982, 0.6806995451, 0.1073969566],
               [0.0883024619, 0.2817188376, 0.6299787005]])
M2 = np.array([[0.2104542553, 0.7936177850, -0.0040720468], [1.9779984951, -2.4285922050, 0.4505937099],
               [0.0259040371, 0.7827717662, -0.8086757660]])


def ch_hue(lin):
    """OKLab chroma and hue (deg) of linear colours."""
    o = np.cbrt(np.maximum(np.asarray(lin, np.float64) @ M1.T, 0)) @ M2.T
    return np.hypot(o[..., 1], o[..., 2]), np.degrees(np.arctan2(o[..., 2], o[..., 1])) % 360


def wb_chroma(wb):
    """Chroma / hue of a white-balanced colour's chromaticity in linear sRGB (the shot's colour matrix, scaled to max 1)."""
    lin = np.maximum(np.asarray(wb, np.float64) @ SUN_CCM.T, 0)
    return ch_hue(lin / np.maximum(lin.max(-1, keepdims=True), 1e-9))


def clipped(true):
    """The camera RGB of a white-balanced scene (the sun shot's white point), clipped at the base-frame white, and the worker's
    flags (bits 0-2 where that channel clipped, bit 3 on every clipped pixel)."""
    raw_true = true * SUN_WP
    raw = np.minimum(raw_true, 1.0).astype(np.float32)
    flags = np.zeros(true.shape[:2], np.uint8)
    for c in range(3):
        flags |= ((raw_true[..., c] >= 1.0).astype(np.uint8) << c)
    flags[flags != 0] |= 8
    return raw, flags


def sun_scene(kind, h=384, w=384):
    """kind 'neutral': a neutral sun (disc, glow); 'flare': the same in a warm flare (R/G 1.25, B/G 0.75 from just outside
    the band); 'colour': a red sunset rim (R clipped, G / B at 0.3 / 0.15 of their clips) and an orange lamp (R:G:B 1.6 : 1 :
    0.45)."""
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float64)
    if kind == 'colour':
        true = np.full((h, w, 3), 0.25) * np.array([1.0, 0.95, 0.9])
        rim = np.exp(-(np.hypot(yy - 110, xx - 192) - 70) ** 2 / (2 * 6.0 ** 2))
        true += (3.0 * rim)[..., None] * np.array([1.0, 0.3 * 0.99 / 2.28, 0.15 * 1.65 / 2.28])
        d = np.hypot(yy - 280, xx - 192)
        true += (5.0 * np.exp(-np.maximum(d - 14, 0) / 12.0))[..., None] * np.array([1.6, 1.0, 0.45])
    else:
        d = np.hypot(yy - h / 2, xx - w / 2)
        lvl = 0.35 + 6.0 * np.exp(-np.maximum(d - 30, 0) / 30.0)    # 6.35 x at the disc, e-fold 30 px, sky 0.35
        tint = np.ones((h, w, 3))
        if kind == 'flare':
            tint = 1.0 + (np.array([1.25, 1.0, 0.75]) - 1.0) * np.clip((d - 72) / 14.0, 0.0, 1.0)[..., None]
        true = lvl[..., None] * tint
    return clipped(true)


def lamp_scene(block):
    """P378, the map's block size: on a light wall a row of orange lamps (R:G:B 1.6 : 1 : 0.45: a blown core, R and G clipped round
    it, a measured rim) and a row of white lamps in a warm glow (R/G 1.25, B/G 0.75 from their clipped disc out: the sun ring's
    light), each row 1, 2 and 3 blocks wide (the clipped disc: G clips first); the rows and the lamp of each: masks."""
    h, w = 13 * block + 96, 17 * block + 96
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float64)
    true = np.full((h, w, 3), 0.25) * np.array([1.0, 0.95, 0.9])
    lamps = []
    for row, kind in ((0, 'orange'), (1, 'white')):
        for k in (1, 2, 3):
            # off the block grid by a third of a block (a light a block wide then falls on four blocks)
            cy, cx = (3 + 7 * row) * block + 48 + 0.37 * block, (2.5 + 6 * (k - 1)) * block + 48 + 0.29 * block
            d = np.hypot(yy - cy, xx - cx)
            half = 0.5 * k * block                            # the G-clipped disc's radius
            if kind == 'orange':
                s = 0.7 * half / np.log(5.0)                  # level 5 at 0.3 of the radius, 1 (G's clip) at the radius
                true += (5.0 * np.exp(-np.maximum(d - 0.3 * half, 0) / s))[..., None] * np.array([1.6, 1.0, 0.45])
            else:
                s = 0.7 * half / np.log(10.0)                 # the frame's brightest (the ceiling at whyLow's maximum)
                lvl = 10.0 * np.exp(-np.maximum(d - 0.3 * half, 0) / s)
                tint = 1.0 + (np.array([1.25, 1.0, 0.75]) - 1.0) * np.clip((d - 0.8 * half) / (0.3 * half), 0.0, 1.0)[..., None]
                true += lvl[..., None] * tint
            lamps.append((kind, k, d < 1.6 * half + 8.0))
    raw, flags = clipped(true)
    return raw, flags, lamps


def guard(label, m, cb, hb, ca, ha):
    """A coloured light keeps its colour on the display (hue within 3 deg, chroma >= 0.95 x before, its 10th percentile >= 0.9 x) -
    None, or the failure."""
    col = m & (cb > 0.03)
    if not col.any():
        return '%s: nothing coloured to keep' % label
    hd = np.median(np.abs((ha[col] - hb[col] + 180) % 360 - 180))
    ratio = ca[col] / np.maximum(cb[col], 1e-6)
    keep, keep10 = np.median(ratio), np.percentile(ratio, 10)
    # P378 review: the 10th percentile too - a ring or stripe over a part of the light hides under a median
    if hd > 3.0 or keep < 0.95 or keep10 < 0.9:
        return '%s lost its colour (hue %.1f deg, chroma x%.2f, p10 x%.2f)' % (label, hd, keep, keep10)
    return None


def render_pair(raw, flags, chroma, block, radius, chain=None, with_flags=True):
    """The shaders before P378 (the reference without the band, HL_OPPOSED 0) against ScamRgb's and WhyCombine's now: the
    display chroma / hue before and after, scamrgb's output, the map's weights and its errors against numpy."""
    h, w = flags.shape
    inp, gm, ft = texture(raw), texture(np.ones((1, 1, 4), np.float16), 'f2', linear=True), texture(flags, 'f1')
    common = {'whitePoint': tuple(map(float, SUN_WP)), 'cropOffset': (0, 0), 'clipLoU': (1.0, 1.0, 1.0),
              'clipHiU': (0.0, 0.0, 0.0), 'clipFlagsU': 1 if with_flags else 0, 'clipHiUnflaggedU': 0, 'inverseSize': (1.0 / w, 1.0 / h)}
    base = dict(common, hlModeU=1, hlStrengthU=1.0, chromaU=0, blockU=8, chromaLimitU=0.35, defringeU=-1.0, signedU=1, oppU=1,
                oppChromaU=tuple(map(float, chroma)))
    after = draw('scamhdr/scamrgb.glsl', (w, h), {'InputBuffer': inp, 'GainMap': gm, 'ClipFlags': ft}, base)
    for t in (inp, gm, ft):
        t.release()
    _, before, _, _ = reference(raw, flags, SUN_WP, band=False, chroma_in=chroma)
    share_t, weights, err_w, err_s, gate = opposed_map(raw, flags, block, radius, with_flags=with_flags)
    d_before, d_after = display(before.astype(np.float32), chain=chain), display(after, (share_t, block), chain=chain)
    share_t.release()
    cb, hb = ch_hue(np.clip(d_before, 0, 1) ** 2.2)
    ca, ha = ch_hue(np.clip(d_after, 0, 1) ** 2.2)
    return dict(before=before, after=after, d_before=d_before, d_after=d_after, cb=cb, hb=hb, ca=ca, ha=ha, weights=weights,
                err_w=err_w, err_s=err_s, gate=gate)


def sun_ring():
    """P378: the clip band neutral after ScamRgb and on the display; the warm glow round a light clipped without ultrashort data
    white on the display; coloured light kept - at the frame's own map block and at the shipped 16 / 32 px."""
    ok = True
    common = {'whitePoint': tuple(map(float, SUN_WP)), 'cropOffset': (0, 0), 'clipLoU': (1.0, 1.0, 1.0),
              'clipHiU': (0.0, 0.0, 0.0), 'clipFlagsU': 1, 'clipHiUnflaggedU': 0}
    for kind in ('neutral', 'flare', 'colour'):
        raw, flags = sun_scene(kind)
        h, w = flags.shape
        inp, gm, ft = texture(raw), texture(np.ones((1, 1, 4), np.float16), 'f2', linear=True), texture(flags, 'f1')
        measured, _ = opposed_gpu({'InputBuffer': inp, 'GainMap': gm, 'ClipFlags': ft},
                                  dict(common, inverseSize=(1.0 / w, 1.0 / h), sizeU=(w, h)), w, h)
        for t in (inp, gm, ft):
            t.release()
        n = flags & 7
        two = (n == 3) | (n == 5) | (n == 6)                 # two channels clipped, one measured: the band
        g_only = n == 2
        lit = n != 0
        own_block = max(1, int(round(min(w, h) / 192.0)))
        blocks = (own_block, 16, 32) if kind != 'neutral' else (own_block,)
        for label, chroma in (('own chrominance', measured), ('the shot\'s chrominance', np.array(SUN_CHROMA, np.float32))):
            for block in blocks:
                if label == 'own chrominance' and block != own_block:
                    continue
                # the radius in blocks of a frame ~192 blocks on its shorter side (ScamRgb: OPPOSED_RADIUS x shorter / block); P378
                # review: at the frame's own block the scene is a crop of a 3072 px frame as at 16 / 32 px (its sun's disc ~60 px;
                # a 384 px frame's own radius, 12.7 px, is a fifth of the disc: no glow beyond the blown core reaches it)
                rd = render_pair(raw, flags, chroma, block, OPPOSED_RADIUS * 3072 / own_block if block == own_block
                                 else OPPOSED_RADIUS * 192)
                after, before, cb, hb, ca, ha = rd['after'], rd['before'], rd['cb'], rd['hb'], rd['ca'], rd['ha']
                tag = '%s, %s, %d px blocks' % (kind, label, block)
                _, want, _, _ = reference(raw, flags, SUN_WP, chroma_in=chroma)
                e = (np.abs(after - want) / np.maximum(np.abs(want), 1e-3))[lit].max()
                if e > 2e-3:
                    print('%s: the reconstruction off the reference by %.4f' % (tag, e))
                    ok = False
                if rd['err_w'] > 1e-6 or rd['err_s'] > 2e-3:
                    print('%s: the opposed map (suncore MODE 2, 1) off numpy\'s: weights %.2e, spread %.2e' % (tag, rd['err_w'], rd['err_s']))
                    ok = False
                if kind != 'colour':
                    sb, shb = wb_chroma(before[two])
                    sa, _ = wb_chroma(after[two])
                    print('%s sun, %s: the clip band (%d px) after ScamRgb chroma %.3f hue %3.0f -> %.4f (max %.4f); on the display '
                          'chroma %.4f hue %3.0f -> %.4f (max %.4f); the zone where G alone clipped (%d px) on the display %.4f -> '
                          '%.4f' % (kind, label + ('' if block == own_block else ', %d px blocks' % block), int(two.sum()),
                                    np.median(sb), np.median(shb), np.median(sa), sa.max(), np.median(cb[two]), np.median(hb[two]),
                                    np.median(ca[two]), ca[two].max(), int(g_only.sum()), np.median(cb[g_only]), np.median(ca[g_only])))
                    if np.median(sb) < 0.008:
                        print('%s: the scene is not the case (the band before P378: chroma %.4f)' % (tag, np.median(sb)))
                        ok = False
                    if sa.max() > 0.005 or ca[two].max() > 0.01:
                        print('%s: the clip band not neutral (ScamRgb %.4f, display %.4f)' % (tag, sa.max(), ca[two].max()))
                        ok = False
                    if np.median(ca[g_only]) > np.median(cb[g_only]) + 0.003:
                        print('%s: the zone where G alone clipped more coloured than before (%.4f > %.4f)'
                              % (tag, np.median(ca[g_only]), np.median(cb[g_only])))
                        ok = False
                    if kind == 'flare':
                        # the warm glow the landing painted near full brightness (this chain: R up to 0.995): white
                        ring = (n < 3) & (rd['d_before'].max(-1) >= GLOW_TOP) & (cb >= 0.03)
                        if ring.sum() < 50 or np.median(ca[ring]) > 0.01:
                            print('%s: the warm glow round the sun not white on the display (%d px, chroma %.4f -> %.4f)'
                                  % (tag, int(ring.sum()), np.median(cb[ring]) if ring.any() else -1,
                                     np.median(ca[ring]) if ring.any() else -1))
                            ok = False
                        print('   the warm glow the landing painted (%d px): chroma %.4f -> %.4f (p95 %.4f)'
                              % (int(ring.sum()), np.median(cb[ring]), np.median(ca[ring]), np.percentile(ca[ring], 95)))
                else:
                    same = np.abs(after - before)[lit].max() / max(float(np.abs(before[lit]).max()), 1.0)
                    rows = np.arange(h)[:, None]
                    rim, lamp, core = lit & (rows < 200), lit & (rows >= 200) & (n != 7), lit & (n == 7)
                    measured_rim = ~lit & (rows >= 200)
                    print('coloured light, %s: the reconstruction against the one before max |diff| %.1e; display: red rim chroma '
                          '%.3f hue %3.0f -> %.3f hue %3.0f, orange lamp (1 - 2 channels clipped) chroma %.3f hue %3.0f -> %.3f hue '
                          '%3.0f, its blown core (%d px) %.3f -> %.3f'
                          % (label + ('' if block == own_block else ', %d px blocks' % block), same, np.median(cb[rim]),
                             np.median(hb[rim]), np.median(ca[rim]), np.median(ha[rim]), np.median(cb[lamp]), np.median(hb[lamp]),
                             np.median(ca[lamp]), np.median(ha[lamp]), int(core.sum()), np.median(cb[core]), np.median(ca[core])))
                    if same > 2e-3:
                        print('%s: the reconstruction changed (%.4f)' % (tag, same))
                        ok = False
                    for name, m in (('red rim', rim), ('orange lamp', lamp), ('orange lamp\'s measured rim', measured_rim)):
                        bad = guard('%s: the %s' % (tag, name), m, cb, hb, ca, ha)
                        if bad:
                            print(bad)
                            ok = False
                    # the lamp's blown core (every channel clipped, P376's neutral) lands on white, not on its orange rim's colour
                    if not core.any() or np.median(ca[core]) > 0.005:
                        print('%s: the orange lamp\'s blown core does not land on white (%d px, chroma %.4f)'
                              % (tag, int(core.sum()), np.median(ca[core]) if core.any() else -1))
                        ok = False
    ok = lamp_sizes() and ok
    ok = map_paths() and ok
    return ok


def lamp_sizes():
    """P378: lights 1, 2 and 3 blocks wide at the shipped blocks (16 px: a 3072 px frame's, 32 px: 50 MP's) through the sun shot's
    chain: the orange lamps keep their rims' colour (clipped and measured) at every size and block, their blown cores land on white
    from a lamp 32 px wide (a core of ~16 px; a lamp 16 px wide, its core 8 px, which the colour source's B-spline mixes with the
    rim: as before, no more coloured), the white lamps' warm glow the landing paints near full brightness goes white and is
    nowhere more coloured than before - and a lamp 32 px wide comes out the same on 16 px blocks (2 wide) as on 32 px (1 wide):
    the map's weight is 1 from 15 % of a block filled, its radius ~6.3 blocks."""
    ok = True
    same_px = {}
    for block in (16, 32):
        raw, flags, lamps = lamp_scene(block)
        h, w = flags.shape
        # the sun shot's chain (the landing paints the glow at full brightness), its mask's sigma this frame's (the lights' sizes
        # against the blocks are the point, the landing's reach in blocks the frame's own)
        chain = dict(SHOT_CHAIN, sigma=max(1.0, 0.012 * ((w + 1) // 2)))
        rd = render_pair(raw, flags, np.array(SUN_CHROMA, np.float32), block, OPPOSED_RADIUS * 192, chain=chain)
        cb, hb, ca, ha = rd['cb'], rd['hb'], rd['ca'], rd['ha']
        n = flags & 7
        if rd['err_w'] > 1e-6 or rd['err_s'] > 2e-3:
            print('lamps, %d px blocks: the opposed map off numpy\'s: weights %.2e, spread %.2e' % (block, rd['err_w'], rd['err_s']))
            ok = False
        out = []
        for kind, k, m in lamps:
            tag = 'lamps, %d px blocks: the %s lamp %d block%s wide' % (block, kind, k, 's' if k > 1 else '')
            if kind == 'orange':
                core = m & (n == 7)
                for name, part in (('clipped rim', m & (n != 0) & (n != 7)), ('measured rim', m & (n == 0))):
                    bad = guard('%s: its %s' % (tag, name), part, cb, hb, ca, ha)
                    if bad:
                        print(bad)
                        ok = False
                rim = m & (n != 7) & (cb > 0.03)
                ratio = float(np.median(ca[rim] / np.maximum(cb[rim], 1e-6)))
                out.append('orange %d: rim chroma x%.2f, core %d px %.3f -> %.3f' % (
                    k, ratio, int(core.sum()), np.median(cb[core]), np.median(ca[core])))
                if not core.any() or np.median(ca[core]) > (0.005 if k * block >= 32 else np.median(cb[core]) + 0.003):
                    print('%s: its blown core does not land on white (%d px, chroma %.4f -> %.4f)'
                          % (tag, int(core.sum()), np.median(cb[core]) if core.any() else -1, np.median(ca[core]) if core.any() else -1))
                    ok = False
                if k * block == 32:
                    same_px[block] = (ratio, float(np.median(ca[core])))
            else:
                glow = m & (n < 3) & (cb >= 0.03)
                ring = glow & (rd['d_before'].max(-1) >= GLOW_TOP)
                out.append('white %d: glow %d px %.3f -> %.3f, painted near full brightness %d px %.3f -> %.4f' % (
                    k, int(glow.sum()), np.median(cb[glow]), np.median(ca[glow]), int(ring.sum()),
                    np.median(cb[ring]) if ring.any() else -1, np.median(ca[ring]) if ring.any() else -1))
                if np.median(ca[glow]) > np.median(cb[glow]) + 0.002 or (ring.sum() >= 50 and np.median(ca[ring]) > 0.01):
                    print('%s: its warm glow more coloured than before or not white where painted (%.4f -> %.4f; %.4f)'
                          % (tag, np.median(cb[glow]), np.median(ca[glow]), np.median(ca[ring]) if ring.any() else -1))
                    ok = False
        print('lamps, %d px blocks (%d of %d blocks filled): %s' % (block, int((rd['weights'] > 0).sum()), rd['weights'].size,
                                                                   '; '.join(out)))
    (r16, c16), (r32, c32) = same_px[16], same_px[32]
    print('lamps: the orange lamp 32 px wide on 16 px blocks rim x%.2f core %.4f, on 32 px blocks rim x%.2f core %.4f' % (r16, c16, r32, c32))
    if abs(r16 - r32) > 0.03 or abs(c16 - c32) > 0.005:
        print('lamps: the same lamp differs with the block size')
        ok = False
    return ok


def map_paths():
    """P378: the map without clip flags (cpu-single: the values alone, no Bento level), none with a Bento level and no flags or
    with every pixel's ultrashort content (bits 4 / 5), none for a few clipped dots."""
    ok = True
    raw, flags = sun_scene('flare')
    h, w = flags.shape
    rd = render_pair(raw, flags, np.array(SUN_CHROMA, np.float32), 16, OPPOSED_RADIUS * 192, with_flags=False)
    n = flags & 7
    ring = (n < 3) & (rd['d_before'].max(-1) >= GLOW_TOP) & (rd['cb'] >= 0.03)
    print('flare sun without clip flags (cpu-single), 16 px blocks: %d blocks filled, the warm glow (%d px) %.4f -> %.4f; map vs '
          'numpy %.1e / %.1e' % (int((rd['weights'] > 0).sum()), int(ring.sum()), np.median(rd['cb'][ring]), np.median(rd['ca'][ring]),
                                 rd['err_w'], rd['err_s']))
    if rd['err_w'] > 1e-6 or rd['err_s'] > 2e-3 or not (rd['weights'] > 0).any() or np.median(rd['ca'][ring]) > 0.01:
        print('flare sun without clip flags: the map or the white glow missing')
        ok = False
    for label, kw, fl in (('a Bento level, no flags', dict(clip_hi=(8.0, 8.0, 8.0), with_flags=False), flags),
                          ('ultrashort content (bit 5)', {}, np.where(flags != 0, flags | 32, 0).astype(np.uint8)),
                          ('inside the Bento mask (bit 4)', {}, np.where(flags != 0, flags | 16, 0).astype(np.uint8))):
        t, wts, ew, es, _ = opposed_map(raw, fl, 16, OPPOSED_RADIUS * 192, **kw)
        t.release()
        if wts.max() != 0.0 or ew > 1e-6:
            print('opposed map with %s: weights %.3f (numpy %.1e)' % (label, wts.max(), ew))
            ok = False
    dots = np.zeros((96, 96, 3), np.float32) + 0.3
    dfl = np.zeros((96, 96), np.uint8)
    for y, x in ((30, 30), (40, 52), (55, 35), (60, 64), (47, 47)):
        dots[y - 1:y + 2, x - 1:x + 2] = 1.0
        dfl[y - 1:y + 2, x - 1:x + 2] = 15
    t, wts, ew, es, _ = opposed_map(dots, dfl, 16, OPPOSED_RADIUS * 192)
    t.release()
    print('opposed map: Bento level without flags, ultrashort content (bits 4, 5) and 3 x 3 clipped dots on 16 px blocks: '
          'no block filled (dots: %.3f)' % wts.max())
    if wts.max() != 0.0 or ew > 1e-6:
        print('opposed map: clipped dots fill a block (%.3f)' % wts.max())
        ok = False
    return ok


def owner_ring():
    """P378, the owner's ring (SCAMERA-debug_2.txt 11:56:55, the sun at the sensor floor; JPEG 42fa2189: r 160 - 220 px from the
    sun (255, 210 - 213, 175 - 180), chroma 0.063 - 0.069, r 260 - 300 0.033 - 0.038) on a 1024 px crop of the 4096 x 3072 frame
    through the shot's chain: ae 1.985, the ceiling 4.0, the fusion's exposures 0.125 / 1 / 0.354 / 3.03, the luminance shading
    x3 at the sun (the map 2.1 at the edge's middle, 5.1 in the corner), the mask's sigma of the whole frame (0.012 x 2048), the
    map's 16 px blocks and radius (0.033 x 3072); the aureole warm (R/G 1.25, B/G 0.75) out to 200 px from the centre, R/G 1.1,
    B/G 0.9 from 280 px. Before (the shaders before P378): the ring the landing paints at full brightness (R 255, chroma >= 0.04)
    is the case (median >= 0.05); after: white (median < 0.01, p95 < 0.02), the glow's chroma rising monotonically out of the
    core to the sky's (no new ring), the far sky unchanged; and in the same frame an orange lamp (1.6 : 1 : 0.45, its core
    blown) and a red sunset band (R clipped) keep their colour, the lamp's core lands on white."""
    ok = True
    raw, flags, yy, xx, d, dl, band = owner_frame(1.25)
    rd = render_pair(raw, flags, np.array(SUN_CHROMA, np.float32), 16, OPPOSED_RADIUS * 3072 / 16, chain=SHOT_CHAIN)
    cb, hb, ca, ha = rd['cb'], rd['hb'], rd['ca'], rd['ha']
    n = flags & 7
    sun = (d < 420) & (yy < 850) & (dl > 200)
    ring = sun & (n < 3) & (rd['d_before'].max(-1) >= 254.0 / 255) & (cb >= 0.04)
    edge = d[sun & (n != 0)].max()
    prof = []
    for r in range(int(edge) // 10 * 10, 400, 20):
        m = sun & (n == 0) & (d >= r) & (d < r + 20)
        prof.append((r, float(np.median(cb[m])), float(np.median(ca[m]))))
    far = sun & (d >= 360)
    print('the owner\'s ring (ae 1.985, clip 4, LSC x3, 16 px blocks): the ring the landing painted (%d px, R 255) chroma %.4f -> '
          '%.4f (p95 %.4f); by distance (clip edge r %.0f) %s; far sky %.4f -> %.4f'
          % (int(ring.sum()), np.median(cb[ring]), np.median(ca[ring]), np.percentile(ca[ring], 95), edge,
             ' '.join('r%d %.3f->%.3f' % p for p in prof), np.median(cb[far]), np.median(ca[far])))
    if ring.sum() < 1000 or np.median(cb[ring]) < 0.05:
        print('the owner\'s ring: the scene is not the case (%d px, chroma %.4f before)' % (int(ring.sum()), np.median(cb[ring])))
        ok = False
    if np.median(ca[ring]) > 0.01 or np.percentile(ca[ring], 95) > 0.02:
        print('the owner\'s ring: not white on the display (median %.4f, p95 %.4f)' % (np.median(ca[ring]), np.percentile(ca[ring], 95)))
        ok = False
    after = [p[2] for p in prof]
    if any(after[i + 1] < after[i] - 0.002 for i in range(len(after) - 1)) or max(after) > np.median(ca[far]) + 0.005:
        print('the owner\'s ring: the glow after it not rising monotonically to the sky\'s chroma (a new ring): %s'
              % ' '.join('%.3f' % v for v in after))
        ok = False
    if abs(np.median(ca[far]) - np.median(cb[far])) > 0.002:
        print('the owner\'s ring: the far sky changed (%.4f -> %.4f)' % (np.median(cb[far]), np.median(ca[far])))
        ok = False
    lamp, lcore = (dl < 140) & (n != 0) & (n != 7), (dl < 140) & (n == 7)
    lrim = (dl < 100) & (n == 0)                         # its glow where the lamp outshines the sky (G clips at 88 px)
    red = (yy > 850) & (n != 0)
    for name, m in (('orange lamp (1 - 2 channels clipped)', lamp), ('orange lamp\'s measured rim', lrim), ('red sunset band', red)):
        bad = guard('the owner\'s chain: the ' + name, m, cb, hb, ca, ha)
        if bad:
            print(bad)
            ok = False
    print('   same frame: orange lamp chroma %.3f -> %.3f, its blown core (%d px) %.3f -> %.4f; red sunset band chroma %.3f -> %.3f'
          % (np.median(cb[lamp]), np.median(ca[lamp]), int(lcore.sum()), np.median(cb[lcore]), np.median(ca[lcore]),
             np.median(cb[red]), np.median(ca[red])))
    if not lcore.any() or np.median(ca[lcore]) > 0.005:
        print('the owner\'s chain: the orange lamp\'s blown core does not land on white (%.4f)' % np.median(ca[lcore]))
        ok = False
    # P378 review: the red band's unclipped upper edge (a white stripe there escaped the clipped pixels' guard) and the orange
    # lamp's glow out to the landing's reach, ring by ring (a white ring at 130 - 160 px escaped the rim's dl < 100)
    edge = (yy > 850) & (yy < 985) & (n == 0) & (band > 0.2)
    bad = guard('the owner\'s chain: the red sunset band\'s unclipped edge', edge, cb, hb, ca, ha)
    if bad:
        print(bad)
        ok = False
    lprof = []
    for r in range(90, 230, 10):
        m = (dl >= r) & (dl < r + 10) & (n == 0) & (xx < 300) & (yy < 300)
        if m.sum() > 20:
            lprof.append((r, float(np.median(cb[m])), float(np.median(ca[m]))))
    print('   the orange lamp\'s glow by distance: %s' % ' '.join('r%d %.3f->%.3f' % q for q in lprof))
    if any(q[2] < q[1] - 0.01 for q in lprof):
        print('the owner\'s chain: the orange lamp\'s glow paler than before somewhere (a ring): %s'
              % ' '.join('r%d %.3f->%.3f' % q for q in lprof))
        ok = False
    return ok


def owner_frame(warm, lamp=(1.6, 1.0, 0.45), sun=True):
    """owner_ring's frame (1024 px of the 4096 x 3072 shot): the sun with its aureole R/G {warm}, B/G 2 - {warm} out to 200 px
    (R/G 1.1, B/G 0.9 from 280 px), an orange lamp at (150, 150) of the tint {lamp}, a red sunset band at row 985."""
    n_ = 1024
    yy, xx = np.mgrid[0:n_, 0:n_].astype(np.float64)
    d = np.hypot(yy - n_ / 2, xx - n_ / 2)
    lvl = 0.3 + (6.0 * np.exp(-np.maximum(d - 100.0, 0) / 41.6) if sun else 0.0)
    a, b = np.array([warm, 1.0, 2.0 - warm]), np.array([1.1, 1.0, 0.9])
    true = lvl[..., None] * (a + (b - a) * np.clip((d - 200.0) / 80.0, 0, 1)[..., None])
    dl = np.hypot(yy - 150, xx - 150)
    true += (5.0 * np.exp(-np.maximum(dl - 40.0, 0) / 30.0))[..., None] * np.array(lamp)
    band = np.exp(-(yy - 985.0) ** 2 / (2 * 40.0 ** 2)) * (np.abs(xx - 512) < 420)
    true += (3.0 * band)[..., None] * np.array([1.0, 0.3 * 0.99 / 2.28, 0.15 * 1.65 / 2.28])
    raw, flags = clipped(true)
    return raw, flags, yy, xx, d, dl, band


def dips(prof):
    """Bins whose chroma after lies more than 0.01 under both neighbours' (a ring or a contour drawn into a glow)."""
    a = [q[2] for q in prof]
    return [prof[i][0] for i in range(1, len(a) - 1) if a[i] < min(a[i - 1], a[i + 1]) - 0.01]


def review_scenes():
    """P378 review (the per-pixel cut-off drew white rings round coloured lights and a pale sky went white): a sky with G alone
    clipped and no light (nothing filled, nothing changed); the owner's sun with warmer aureoles (R/G 1.3 - 1.4: white up to 1.35,
    1.4 nearly as before, never a white rim in a coloured glow); a warm lamp (1.45 : 1 : 0.6) alone in the
    frame centre (no contour in its glow)."""
    ok = True
    h, w = 384, 384
    yy = np.mgrid[0:h, 0:w][0].astype(np.float64)
    lvl = 1.35 - 0.9 * yy / h                                  # G clipped in the top rows, R and B not
    raw, flags = clipped(lvl[..., None] * np.array([0.8, 1.0, 1.2]))   # white-balanced: raw R 0.34, G 1, B 0.72 x the level
    rd = render_pair(raw, flags, np.array(SUN_CHROMA, np.float32), 16, OPPOSED_RADIUS * 192)
    diff = float(np.abs(rd['ca'] - rd['cb']).max())
    print('a blue sky with G alone clipped (%d px), no light: %d blocks filled, display chroma max |change| %.4f'
          % (int(((flags & 7) == 2).sum()), int((rd['weights'] > 0).sum()), diff))
    if (rd['weights'] > 0).any() or diff > 0.002:
        print('a blue sky with G alone clipped: whitened (%d blocks, %.4f)' % (int((rd['weights'] > 0).sum()), diff))
        ok = False
    for warm in (1.3, 1.35, 1.4):
        raw, flags, yy, xx, d, dl, band = owner_frame(warm)
        rd = render_pair(raw, flags, np.array(SUN_CHROMA, np.float32), 16, OPPOSED_RADIUS * 3072 / 16, chain=SHOT_CHAIN)
        cb, ca, n = rd['cb'], rd['ca'], flags & 7
        sun = (d < 420) & (yy < 850) & (dl > 200)
        edge = d[sun & (n != 0)].max()
        prof = []
        for r in range(int(edge) // 10 * 10, 400, 10):
            m = sun & (n == 0) & (d >= r) & (d < r + 10)
            if m.sum() > 20:
                prof.append((r, float(np.median(cb[m])), float(np.median(ca[m]))))
        ring = sun & (n < 3) & (rd['d_before'].max(-1) >= 254.0 / 255) & (cb >= 0.04)
        print('the owner\'s sun with a warmer aureole (R/G %.2f): the ring (%d px) %.4f -> %.4f; by distance %s'
              % (warm, int(ring.sum()), np.median(cb[ring]), np.median(ca[ring]), ' '.join('r%d %.3f->%.3f' % q for q in prof[::2])))
        bad = dips(prof)
        if bad:
            print('the owner\'s sun, R/G %.2f: a contour in its glow at r %s' % (warm, bad))
            ok = False
        if warm <= 1.35 and np.median(ca[ring]) > 0.01:
            print('the owner\'s sun, R/G %.2f: the ring not white (%.4f)' % (warm, np.median(ca[ring])))
            ok = False
    # a warm lamp alone (no sun, the ceiling at whyLow's maximum)
    n_ = 512
    yy, xx = np.mgrid[0:n_, 0:n_].astype(np.float64)
    dl = np.hypot(yy - n_ / 2, xx - n_ / 2)
    true = np.full((n_, n_, 3), 0.25) * np.array([1.0, 0.95, 0.9])
    true += (5.0 * np.exp(-np.maximum(dl - 40.0, 0) / 30.0))[..., None] * np.array([1.45, 1.0, 0.6])
    raw, flags = clipped(true)
    rd = render_pair(raw, flags, np.array(SUN_CHROMA, np.float32), 16, OPPOSED_RADIUS * 3072 / 16)
    cb, ca, n = rd['cb'], rd['ca'], flags & 7
    prof = []
    for r in range(40, 250, 10):
        m = (dl >= r) & (dl < r + 10) & (n != 7)
        if m.sum() > 20:
            prof.append((r, float(np.median(cb[m])), float(np.median(ca[m]))))
    print('a warm lamp (1.45 : 1 : 0.6) alone: by distance %s' % ' '.join('r%d %.3f->%.3f' % q for q in prof[::2]))
    bad = dips(prof)
    if bad:
        print('a warm lamp alone: a contour in its glow at r %s' % bad)
        ok = False
    return ok


if __name__ == '__main__':
    sys.exit(main())
