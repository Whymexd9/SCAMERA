#!/usr/bin/env python3
"""P333: the WHY fusion's Laplacian pyramid (why/lpyr.glsl, WhyFusion.pyramid) against the guided filter (why/gf.glsl).

The passes run as WhyFusion runs them (level 1 of the lumas / weights from whyLow, 5-tap reduce, the coarsest blend, the
bands from coarse to fine, level 0 with the P291 envelope) on synthetic whyLow images, even and odd sizes. Checks:
- a flat neutral area gives the guided filter's value (= fusedAt, the viewfinder's FusedCurve), with and without monoU;
- one active exposure (the other layer weights 0) gives back that exposure's luma exactly: the pyramid's reduce / expand
  reconstruct the image (no shift, no loss at odd sizes);
- a window (x 64 light) next to a textured interior: every value finite and within [0, 1.05]; the edge's contrast and the
  overshoot beside it, against the guided filter, are reported.
Usage: check_why_pyramid.py [--report]
"""
from pathlib import Path
import sys
import numpy as np
import moderngl

ROOT = Path(__file__).resolve().parents[1]
SH = ROOT / 'app/src/main/assets/shaders'
REPORT = '--report' in sys.argv
VS = '#version 300 es\nvoid main(){vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));gl_Position=vec4(p*2.-1.,0,1);}'


def source(name, mode):
    s = (SH / name).read_text()
    s = s.replace('#import why_fusion', (SH / 'utils/import_why_fusion.glsl').read_text())
    s = s.replace('#define MODE 0', '#define MODE %d' % mode)
    return '#version 300 es\n' + s


class Gl:
    def __init__(self):
        try:
            self.ctx = moderngl.create_standalone_context(backend='egl', require=310)
        except Exception:
            self.ctx = moderngl.create_standalone_context(require=310)
        self.progs = {}

    def prog(self, name, mode):
        key = (name, mode)
        if key not in self.progs:
            self.progs[key] = self.ctx.program(vertex_shader=VS, fragment_shader=source(name, mode))
        return self.progs[key]

    def tex(self, a, comps=4, dtype='f4'):
        h, w = a.shape[:2]
        t = self.ctx.texture((w, h), comps, a.astype(dtype if dtype != 'f2' else 'f2').tobytes(), dtype=dtype)
        t.filter = (moderngl.NEAREST, moderngl.NEAREST)
        return t

    def target(self, w, h, comps, dtype):
        t = self.ctx.texture((w, h), comps, dtype=dtype)
        t.filter = (moderngl.NEAREST, moderngl.NEAREST)
        return t

    def draw(self, prog, out, textures, uniforms):
        fb = self.ctx.framebuffer([out])
        fb.use()
        self.ctx.viewport = (0, 0, out.width, out.height)
        for i, (k, t) in enumerate(textures.items()):
            if k in prog:
                t.use(i)
                prog[k].value = i
        for k, v in uniforms.items():
            if k in prog:
                prog[k].value = v
        self.ctx.vertex_array(prog, []).render(vertices=3)
        fb.release()

    def read(self, t):
        return np.frombuffer(t.read(), np.float32 if t.dtype == 'f4' else np.float16).astype(np.float32).reshape(t.height, t.width, t.components)


def fusion_uniforms(lw=(0.8, 1.0, 0.7, 0.5), mono=0.0):
    return {'expU': (0.35, 1.0, 1.6, 2.6), 'layerU': lw, 'aeU': 1.0, 'centerU': 0.6, 'smoothU': 0.25,
            'gammaInvU': 1 / 2.2, 'toeU': 0.05, 'monoU': mono}


def levels_for(w, h, coarse=48):
    n = 0
    while min((w + 1) // 2, (h + 1) // 2) >= coarse and n < 16:
        w, h, n = (w + 1) // 2, (h + 1) // 2, n + 1
    return max(1, n)


def pyramid(gl, low, uni, coarse=48):
    h, w = low.shape[:2]
    src = gl.tex(low)
    n = levels_for(w, h, coarse)
    sizes, L, W, F = [(w, h)], [None], [None], [None]
    for l in range(1, n + 1):
        pw, ph = sizes[-1]
        sizes.append(((pw + 1) // 2, (ph + 1) // 2))
        L.append(gl.target(*sizes[l], 4, 'f2'))
        W.append(gl.target(*sizes[l], 4, 'f2'))
        F.append(gl.target(*sizes[l], 1, 'f4'))
    gl.draw(gl.prog('why/lpyr.glsl', 0), L[1], {'InputBuffer': src}, uni)
    gl.draw(gl.prog('why/lpyr.glsl', 1), W[1], {'InputBuffer': src}, uni)
    for l in range(2, n + 1):
        for p in (L, W):
            gl.draw(gl.prog('why/lpyr.glsl', 2), p[l], {'InputBuffer': p[l - 1]}, {})
    gl.draw(gl.prog('why/lpyr.glsl', 3), F[n], {'LevelL': L[n], 'LevelW': W[n]}, {})
    for l in range(n - 1, 0, -1):
        gl.draw(gl.prog('why/lpyr.glsl', 4), F[l], {'LevelL': L[l], 'LevelW': W[l], 'CoarseL': L[l + 1], 'CoarseF': F[l + 1]}, {})
    out = gl.target(w, h, 1, 'f4')
    gl.draw(gl.prog('why/lpyr.glsl', 5), out, {'InputBuffer': src, 'CoarseL': L[1], 'CoarseF': F[1]}, uni)
    return gl.read(out)[:, :, 0], n


def box(gl, t, r):
    for d in ((1, 0), (0, 1)):
        o = gl.target(t.width, t.height, 4, 'f4')
        gl.draw(gl.prog('why/box.glsl', 0), o, {'InputBuffer': t}, {'dirU': d, 'radiusU': r})
        t = o
    return t


def guided(gl, low, uni, radius=32, eps=0.001):
    h, w = low.shape[:2]
    src = gl.tex(low)
    hw, hh = (w + 1) // 2, (h + 1) // 2
    r = radius >> 1
    means = []
    for mode in range(3):
        t = gl.target(hw, hh, 4, 'f4')
        gl.draw(gl.prog('why/gf.glsl', mode), t, {'InputBuffer': src}, uni)
        means.append(box(gl, t, r))
    ab = []
    for mode in range(2):
        t = gl.target(hw, hh, 4, 'f4')
        gl.draw(gl.prog('why/ab.glsl', mode), t, {'MeanII': means[0], 'MeanP': means[1], 'MeanIP': means[2]}, {'epsU': eps})
        ab.append(box(gl, t, r))
    out = gl.target(w, h, 1, 'f4')
    gl.draw(gl.prog('why/gf.glsl', 3), out, {'InputBuffer': src, 'MeanA': ab[0], 'MeanB': ab[1]}, uni)
    return gl.read(out)[:, :, 0]


def main():
    gl = Gl()
    failures = []
    rng = np.random.default_rng(3)
    # 1. flat neutral areas at several levels: the guided filter's value (fusedAt)
    for mono in (0.0, 0.04):
        uni = fusion_uniforms(mono=mono)
        for level in (0.01, 0.08, 0.3, 1.0, 4.0, 24.0):
            low = np.full((96, 128, 4), level, np.float32)
            p, _ = pyramid(gl, low, uni, coarse=8)
            g = guided(gl, low, uni)
            d = float(np.max(np.abs(p - g)))
            if REPORT or d > 2e-3:
                print('flat %.3f mono %.2f: pyramid %.5f guided %.5f max |diff| %.2e' % (level, mono, float(p.mean()), float(g.mean()), d))
            if d > 2e-3:
                failures.append('flat level %.3f mono %.2f differs from the guided filter by %.2e' % (level, mono, d))
    # 2. one active layer: the pyramid gives that layer's luma back (exact reconstruction), even and odd sizes
    for (h, w) in ((192, 256), (191, 255), (97, 131)):
        img = np.zeros((h, w, 4), np.float32)
        yy, xx = np.mgrid[0:h, 0:w]
        base = 0.15 * (1 + 0.6 * np.sin(xx / 3.1) * np.cos(yy / 4.3)) * np.where(xx > w // 2, 6.0, 1.0)
        base *= np.exp(0.2 * rng.standard_normal((h, w)))
        img[:, :, 0] = base; img[:, :, 1] = base; img[:, :, 2] = base; img[:, :, 3] = 1
        one = fusion_uniforms(lw=(0.0, 1.0, 0.0, 0.0))
        p, n = pyramid(gl, img, one, coarse=8)
        # the reference: the guided filter with a huge eps (a = 0, q = the box mean of the ~one-hot weights): fused = L_mid per pixel
        flatref = guided(gl, img, one, radius=2, eps=1e9)
        d = float(np.max(np.abs(p - flatref)))
        if REPORT or d > 3e-3:
            print('one layer %dx%d (%d levels): max |pyramid - L_mid| %.2e' % (w, h, n, d))
        if d > 3e-3:
            failures.append('one active layer %dx%d: the pyramid does not reconstruct the luma (%.2e)' % (w, h, d))
    # 3. a window next to a textured interior
    h, w = 576, 768
    yy, xx = np.mgrid[0:h, 0:w]
    tex = 1 + 0.3 * np.sin(xx / 2.3) * np.sin(yy / 3.7)
    lin = np.where((xx > 450) & (yy > 120) & (yy < 450), 4.0 * tex, 0.06 * tex)
    img = np.stack([lin, lin, lin, np.ones_like(lin)], -1).astype(np.float32)
    uni = fusion_uniforms()
    p, n = pyramid(gl, img, uni)
    g = guided(gl, img, uni)
    if not np.all(np.isfinite(p)) or p.min() < 0 or p.max() > 1.05:
        failures.append('window scene: fused out of [0, 1.05] or not finite (%.4f .. %.4f)' % (np.nanmin(p), np.nanmax(p)))
    def edge(f):
        band = f[200:380, :]
        inside, outside = band[:, 470:560].mean(), band[:, 250:400].mean()
        near_out = band[:, 430:448].mean()
        return inside - outside, near_out - outside
    pe, po = edge(p)
    ge, go = edge(g)
    tex_p = float(np.std(p[200:380, 50:350])); tex_g = float(np.std(g[200:380, 50:350]))
    tex_wp = float(np.std(p[200:380, 480:740])); tex_wg = float(np.std(g[200:380, 480:740]))
    print('window scene (%d levels): edge step pyramid %.4f guided %.4f; dark side next to the edge (halo) pyramid %+.4f guided %+.4f; '
          'texture sd interior pyramid %.4f guided %.4f, window pyramid %.4f guided %.4f'
          % (n, pe, ge, po, go, tex_p, tex_g, tex_wp, tex_wg))
    gl.ctx.release()
    if failures:
        for f in failures:
            print('FAIL: ' + f)
        sys.exit(1)
    print('PASS: the pyramid fusion equals the guided filter on flat areas, reconstructs one layer exactly (even and odd sizes)')


if __name__ == '__main__':
    main()
