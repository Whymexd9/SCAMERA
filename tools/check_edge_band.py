#!/usr/bin/env python3
"""P416: the frame edge's colour band of the denoise (scamdn/cbf edgeBandU), on the SHIPPED shader.

The merge leaves its outer ~6 px with colour errors (OPPO Find X8 Ultra replays: saturated colour blots 3 - 6 px deep and
1.4 - 2.4 x the colour noise along every edge: a coloured strip along the photo's edge). In the last colour pass (modes 2 and 3)
a pixel closer than b = edgeBandU to an edge takes the U, V of the nearest pixel of the band's inner border (p clamped to
[b, size - 1 - b]), averaged over 2 EDGE_R + 1 = 7 pixels along the edge; Y is kept.
Checks on noisy YUV with colour blots in the band (no filtering, so the pass is the reconstruction alone):
- edgeBandU 0 (unset) is the output without the band bit for bit, in mode 2 and mode 3;
- with b = 6: every pixel at least b from every edge is the same bit for bit; Y is the same everywhere;
- the band's U, V equal numpy's mean of the 7 inner-border pixels (within 1e-6), the corners included;
- the band's colour noise along the edge is below the interior's and the blots are gone (max |U|, |V| in the band at most the
  inner border's).
Usage: check_edge_band.py [--shaders DIR] [--report]
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
KY = np.array([0.2126, 0.7152, 0.0721996])
KU = np.array([-0.162450244, -0.546494309, 0.708944715])
KV = np.array([0.999996748, -0.908302439, -0.091693333])
TO_RGB = np.array([[0.999999632, -0.000000001, 0.787402639], [1.00000065, -0.132114184, -0.234062881],
                   [1.000000187, 1.308706208, -0.000000397]])
W, H, B, R = 96, 72, 6, 3


def source(name):
    out = ['#version 430']
    for line in (SH / name).read_text(encoding='utf-8').splitlines():
        m = re.match(r'\s*#import\s+(\S+)', line)
        if m:
            out.append((SH / 'utils' / ('import_' + m.group(1) + '.glsl')).read_text(encoding='utf-8'))
            continue
        out.append(line)
    return '\n'.join(out)


def texture(a):
    a = np.ascontiguousarray(a, np.float32)
    t = ctx.texture((a.shape[1], a.shape[0]), a.shape[2], a.tobytes(), dtype='f4')
    t.filter = (moderngl.NEAREST, moderngl.NEAREST)
    t.repeat_x = t.repeat_y = False
    return t


def draw(textures, uniforms):
    p = ctx.program(vertex_shader=VS, fragment_shader=source('scamdn/cbf.glsl'))
    out = ctx.texture((W, H), 4, dtype='f4')
    fbo = ctx.framebuffer([out])
    fbo.use()
    ctx.viewport = (0, 0, W, H)
    for unit, (k, t) in enumerate(textures.items()):
        t.use(unit)
        if k in p:
            p[k].value = unit
    for k, v in uniforms.items():
        if k in p:
            p[k].value = v
    ctx.vertex_array(p, []).render(vertices=3)
    ctx.finish()
    data = np.frombuffer(out.read(), np.float32).astype(np.float64).reshape(H, W, 4)[..., :3]
    fbo.release(); out.release(); p.release()
    return data


def expected_uv(yuv):
    yy, xx = np.mgrid[0:H, 0:W]
    qy, qx = np.clip(yy, B, H - 1 - B), np.clip(xx, B, W - 1 - B)
    band = (qy != yy) | (qx != xx)
    along_x = np.minimum(yy, H - 1 - yy) <= np.minimum(xx, W - 1 - xx)
    a = np.zeros((H, W, 2))
    for i in range(-R, R + 1):
        a += yuv[np.clip(qy + np.where(along_x, 0, i), 0, H - 1), np.clip(qx + np.where(along_x, i, 0), 0, W - 1), 1:]
    return band, a / (2 * R + 1)


def main():
    rng = np.random.default_rng(416)
    rgb = 0.06 + 0.004 * rng.standard_normal((H, W, 3))      # mode 2 clamps RGB at 0: stay above it
    rgb[:, :, 0] += np.linspace(0, 0.01, W)[None, :]           # a slow colour gradient
    yy, xx = np.mgrid[0:H, 0:W]
    edge = np.minimum(np.minimum(yy, H - 1 - yy), np.minimum(xx, W - 1 - xx))
    blots = (edge < 4) & (rng.random((H, W)) < 0.15)
    rgb[blots] += rng.choice([-1, 1], (int(blots.sum()), 3)) * 0.03   # saturated colour blots in the band
    yuv = np.stack([rgb @ KY, rgb @ KU, rgb @ KV], -1)
    t = texture(yuv)
    orig = texture(rgb)
    fails = []
    for mode in (2, 3):
        base = {'strideU': 1, 'filterU': 0, 'useDeltaU': 0, 'useMapU': 0, 'modeU': mode}
        tex = {'InputBuffer': t, 'DeltaUV': t, 'Orig': orig if mode == 3 else t, 'StrMap': t, 'Grain': t}
        off = draw(tex, base)
        zero = draw(tex, dict(base, edgeBandU=0))
        on = draw(tex, dict(base, edgeBandU=B))
        if not np.array_equal(off, zero):
            fails.append('mode %d: edgeBandU 0 changes the output' % mode)
        inner = edge >= B
        if not np.array_equal(on[inner], off[inner]):
            fails.append('mode %d: pixels at least %d px from the edges changed' % (mode, B))
        band, uv = expected_uv(yuv)
        if mode == 2:
            got = np.stack([on @ KY, on @ KU, on @ KV], -1)
            ref_y = yuv[..., 0]
        else:
            # mode 3 writes (Y - Y(Orig), UV - 0 x UV(Orig)) (keepU unset 0)
            got = np.concatenate([on[..., :1] + (rgb @ KY)[..., None], on[..., 1:]], -1)
            ref_y = yuv[..., 0]
        if np.abs(got[..., 0] - ref_y).max() > 1e-6:
            fails.append('mode %d: Y changed (max %.2e)' % (mode, np.abs(got[..., 0] - ref_y).max()))
        err = np.abs(got[band][:, 1:] - uv[band]).max()
        if err > 1e-6:
            fails.append('mode %d: band U, V not the mean of the inner border (max error %.2e)' % (mode, err))
        if mode == 2:
            hp = lambda a: np.abs(np.diff(a, axis=1)).mean()
            band_noise = hp(got[1:B, B:W - B, 1:])
            in_noise = hp(got[B + 4:H - B - 4, B:W - B, 1:])
            if not band_noise < in_noise:
                fails.append('band colour noise %.2e not below the interior %.2e' % (band_noise, in_noise))
            inner_border = np.abs(yuv[B, :, 1:]).max()
            if np.abs(got[band][:, 1:]).max() > inner_border + 1e-6:
                fails.append('colour blots left in the band (%.4f > %.4f)' % (np.abs(got[band][:, 1:]).max(), inner_border))
            if REPORT:
                print('edge band: band colour noise %.2e vs interior %.2e, max band |UV| %.4f (input %.4f)'
                      % (band_noise, in_noise, np.abs(got[band][:, 1:]).max(), np.abs(yuv[band][:, 1:]).max()))
    t.release(); orig.release()
    if fails:
        print('FAIL\n' + '\n'.join(fails))
        sys.exit(1)
    print('check_edge_band: OK')


main()
