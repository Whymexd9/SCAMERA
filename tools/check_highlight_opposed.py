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
- never magenta: a clipped G is at least the lower of R and B (owner's rule, as the SCAM recovery).
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


def scene():
    h, w = 96, 128
    wp = np.array([0.5, 1.0, 0.6], np.float32)
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    wall = np.full((h, w, 3), 0.35, np.float32) * np.array([1.05, 1.0, 0.92], np.float32)
    true = wall.copy()
    for cy, cx, r in ((40, 40, 10), (40, 98, 9)):
        d = np.sqrt((yy - cy) ** 2 + (xx - cx) ** 2)
        lvl = 4.0 * np.exp(-np.maximum(d - r * 0.3, 0) ** 2 / (2 * (r * 0.6) ** 2)) + 0.6 * np.exp(-d ** 2 / (2 * (3 * r) ** 2))
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


def reference(raw, flags, wp):
    """The algorithm in numpy (white-balanced, clip 0.987, darktable's reference at every counted pixel)."""
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
    out = np.where(clipped, np.maximum(c, ref + chroma), c)
    g = clipped[..., 1]
    out[..., 1] = np.where(g, np.maximum(out[..., 1], np.minimum(out[..., 0], out[..., 2])), out[..., 1])  # never magenta
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
    if REPORT:
        t = true[core]
        print('chrominance GPU', gpu_chroma, 'reference', ref_chroma, 'counted', gpu_n)
        print('mean |error| vs truth in the lamp core: opposed %.3f, SCAM %.3f, input %.3f' % (
            np.abs(on[core] - t).mean(), np.abs(off[core] - t).mean(), np.abs(raw[core] / wp - t).mean()))
    for t in (inp, gm, ft):
        t.release()
    print('highlight opposed ' + ('PASS' if ok else 'FAIL'))
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main())
