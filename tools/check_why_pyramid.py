#!/usr/bin/env python3
"""P333: the WHY fusion's Laplacian pyramid (why/lpyr.glsl, WhyFusion.pyramid) against a numpy Mertens reference and the guided
filter (why/gf.glsl, the post_ab old run).

The programs are built as GLInterface.readProgram builds them (#import with #line, the MODE define) and drawn as GLProg draws them
(units 0, 2, 4, ... in setTexture order, 256-row viewport tiles), in WhyFusion's pass order (level 1 of the lumas / weights from
whyLow, 5-tap reduce, the coarsest blend, the bands from coarse to fine, level 0 with the P310 share and the P291 envelope). Checks:
- an independent numpy Mertens (binomial 1 4 6 4 1 reduce at the even sites, linear expand, coarse i = fine 2i) at tiny, odd and
  tiled sizes: a reduce / expand shifted by a pixel fails here (the flat and one-layer cases cannot see it);
- a flat neutral area gives the guided filter's value (= fusedAt, the viewfinder's FusedCurve), with and without monoU;
- one active exposure gives back that exposure's luma exactly (even and odd sizes);
- P310: violet LED bars 2 / 6 / 16 px at 8 x white in a 0.03 room with the Bento weights keep the guided filter's core (not white);
- the halo of a hard horizon (sky 1.5 over ground 0.03) at the production depth stays at today's height and width (Mertens' halo is
  the method's: this pins it against regressions, see lpyr.glsl).
Usage: check_why_pyramid.py [--report]
"""
from pathlib import Path
import sys
import numpy as np
import moderngl

ROOT = Path(__file__).resolve().parents[1]
SH = ROOT / 'app/src/main/assets/shaders'
REPORT = '--report' in sys.argv
VS = '#version 310 es\nprecision mediump float;\nin vec4 vPosition;\nvoid main(){gl_Position=vPosition;}\n'


def read_program(text, defines):
    out = []
    for n, line in enumerate(text.split('\n'), 1):
        if '#import' in line:
            if '//' not in line:
                name = line.replace('#', '').replace(' ', '_')
                out += ['#line 1', (SH / ('utils/' + name + '.glsl')).read_text(), '#line %d' % (n + 1)]
            continue
        if '#define' in line:
            for k, v in defines:
                if (' ' + k + ' ') in line:
                    line = '#define %s %s' % (k, v)
        out.append(line)
    return '#version 310 es\n\nprecision highp int;\n#line 1\n' + '\n'.join(out) + '\n'


class Gl:
    def __init__(self):
        try:
            self.ctx = moderngl.create_standalone_context(backend='egl', require=310)
        except Exception:
            self.ctx = moderngl.create_standalone_context(require=310)
        self.ctx.gc_mode = 'auto'
        self.progs = {}
        self.vbo = self.ctx.buffer(np.array([-1, -1, 1, -1, -1, 1, 1, 1], 'f4').tobytes())

    def prog(self, name, mode):
        key = (name, mode)
        if key not in self.progs:
            src = read_program((SH / (name + '.glsl')).read_text(), [('MODE', str(mode))])
            self.progs[key] = self.ctx.program(vertex_shader=VS, fragment_shader=src)
        return self.progs[key]

    def tex(self, a, dtype='f2'):
        h, w = a.shape[:2]
        t = self.ctx.texture((w, h), a.shape[2], a.astype(dtype).tobytes(), dtype=dtype)
        t.filter = (moderngl.NEAREST, moderngl.NEAREST)
        return t

    def target(self, w, h, comps, dtype):
        t = self.ctx.texture((w, h), comps, dtype=dtype)
        t.filter = (moderngl.NEAREST, moderngl.NEAREST)
        return t

    def draw(self, prog, out, textures, uniforms):
        fb = self.ctx.framebuffer([out])
        fb.use()
        for i, (k, t) in enumerate(textures):
            t.use(2 * i)
            if k in prog:
                prog[k].value = 2 * i
        for k, v in uniforms.items():
            if k in prog:
                prog[k].value = v
        vao = self.ctx.vertex_array(prog, [(self.vbo, '2f', 'vPosition')])
        y = 0
        while y < out.height:
            hh = min(256, out.height - y)
            self.ctx.viewport = (0, y, out.width, hh)
            vao.render(moderngl.TRIANGLE_STRIP)
            y += hh
        err = self.ctx.error
        if err != 'GL_NO_ERROR':
            raise RuntimeError(err)
        fb.release()

    def read(self, t):
        dt = np.float32 if t.dtype == 'f4' else np.float16
        return np.frombuffer(t.read(), dt).astype(np.float32).reshape(t.height, t.width, t.components)


def uniforms(lw=(0.8, 1.0, 0.7, 0.5), mono=0.0):
    return {'expU': (0.35, 1.0, 1.6, 2.6), 'layerU': lw, 'aeU': 1.0, 'centerU': 0.6, 'smoothU': 0.25, 'gammaInvU': 1 / 2.2,
            'toeU': 0.05, 'monoU': mono, 'satLightU': (0.0, 0.0, 0.0, 0.0), 'satLevelU': (0.0, 0.0)}


# the Bento headroom weights of WhyFusion with P310's saturated-light ramp (WhyTone defaults)
BENTO = {'expU': (0.038, 1.0, 2.0, 4.0), 'layerU': (0.4, 1.0, 0.7, 0.5), 'aeU': 1.0, 'centerU': 0.75, 'smoothU': 0.25,
         'gammaInvU': 1 / 2.2, 'toeU': 0.05, 'monoU': 0.04, 'satLightU': (0.6, 0.8, 0.15, 0.95), 'satLevelU': (0.25, 0.6)}


def levels_for(w, h, coarse=48):
    c = max(2, int(round(coarse)))
    n = 0
    while min((w + 1) // 2, (h + 1) // 2) >= c and n < 16:
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
    gl.draw(gl.prog('why/lpyr', 0), L[1], [('InputBuffer', src)], uni)
    gl.draw(gl.prog('why/lpyr', 1), W[1], [('InputBuffer', src)], uni)
    for l in range(2, n + 1):
        for p in (L, W):
            gl.draw(gl.prog('why/lpyr', 2), p[l], [('InputBuffer', p[l - 1])], {})
    gl.draw(gl.prog('why/lpyr', 3), F[n], [('LevelL', L[n]), ('LevelW', W[n])], {})
    for l in range(n - 1, 0, -1):
        gl.draw(gl.prog('why/lpyr', 4), F[l], [('LevelL', L[l]), ('LevelW', W[l]), ('CoarseL', L[l + 1]), ('CoarseF', F[l + 1])], {})
    out = gl.target(w, h, 1, 'f4')
    gl.draw(gl.prog('why/lpyr', 5), out, [('InputBuffer', src), ('CoarseL', L[1]), ('CoarseF', F[1])], uni)
    return gl.read(out)[:, :, 0], n


def guided(gl, low, uni, radius=32, eps=0.001):
    h, w = low.shape[:2]
    src = gl.tex(low)
    hw, hh, r = (w + 1) // 2, (h + 1) // 2, radius >> 1

    def box(t):
        for d in ((1, 0), (0, 1)):
            o = gl.target(t.width, t.height, 4, 'f4')
            gl.draw(gl.prog('why/box', 0), o, [('InputBuffer', t)], {'dirU': d, 'radiusU': r})
            t = o
        return t
    means = []
    for mode in range(3):
        t = gl.target(hw, hh, 4, 'f4')
        gl.draw(gl.prog('why/gf', mode), t, [('InputBuffer', src)], uni)
        means.append(box(t))
    ab = []
    for mode in range(2):
        t = gl.target(hw, hh, 4, 'f4')
        gl.draw(gl.prog('why/ab', mode), t, [('MeanII', means[0]), ('MeanP', means[1]), ('MeanIP', means[2])], {'epsU': eps})
        ab.append(box(t))
    out = gl.target(w, h, 1, 'f4')
    gl.draw(gl.prog('why/gf', 3), out, [('InputBuffer', src), ('MeanA', ab[0]), ('MeanB', ab[1])], uni)
    return gl.read(out)[:, :, 0]


# ---- numpy Mertens reference (independent of the shaders; no P310 share, no envelope)
def acescg(v, toe):
    x = np.maximum(v, 0) * 1.5
    b, e = max(toe * 0.75, 0.005), max(0.14 - (toe - 0.04) * 0.8, 0.03)
    return (x * (2.51 * x + b)) / (x * (2.43 * x + 0.59) + e)


def prepare_np(rgb, u):
    em, lw = np.array(u['expU']), np.array(u['layerU'])
    lin = np.maximum(rgb, 0) * u['aeU']
    r, g, b = lin[..., 0:1] * em, lin[..., 1:2] * em, lin[..., 2:3] * em
    meter = np.maximum(r * 0.2126 + g * 0.7152 + b * 0.0722, np.maximum(np.maximum(r, g), b) * 0.5)
    Lk = np.maximum(acescg(meter, u['toeU']), 1e-6) ** u['gammaInvU']
    d = Lk - u['centerU']
    wk = np.exp(-0.5 * d * d / max(u['smoothU'] ** 2, 0.001)) * lw + 1e-6
    return Lk, wk / wk.sum(-1, keepdims=True)


K = np.array([1, 4, 6, 4, 1], np.float64) / 16


def reduce_np(a):
    h, w = a.shape[:2]
    ix = np.clip(2 * np.arange((w + 1) // 2)[:, None] + np.arange(-2, 3)[None, :], 0, w - 1)
    t = np.einsum('hxkc,k->hxc', a[:, ix], K)
    iy = np.clip(2 * np.arange((h + 1) // 2)[:, None] + np.arange(-2, 3)[None, :], 0, h - 1)
    return np.einsum('ykxc,k->yxc', t[iy], K)


def expand_np(c, h, w):
    def axis(n, m):
        x = np.arange(n)
        i0 = np.minimum(x >> 1, m - 1)
        return i0, np.minimum(i0 + (x & 1), m - 1)
    y0, y1 = axis(h, c.shape[0])
    x0, x1 = axis(w, c.shape[1])
    return 0.25 * (c[y0][:, x0] + c[y0][:, x1] + c[y1][:, x0] + c[y1][:, x1])


def ref_pyramid(rgb, u, n):
    Lk, p = prepare_np(rgb.astype(np.float64), u)
    Ls, Ws = [Lk], [p]
    for _ in range(n):
        Ls.append(reduce_np(Ls[-1]))
        Ws.append(reduce_np(Ws[-1]))
    F = (Ws[n] * Ls[n]).sum(-1) / Ws[n].sum(-1)
    for l in range(n - 1, -1, -1):
        h, w = Ls[l].shape[:2]
        F = expand_np(F[..., None], h, w)[..., 0] + (Ws[l] * (Ls[l] - expand_np(Ls[l + 1], h, w))).sum(-1) / Ws[l].sum(-1)
    return F


def rgba(rgb):
    return np.concatenate([rgb, np.ones(rgb.shape[:2] + (1,))], -1).astype(np.float16).astype(np.float32)


def main():
    gl = Gl()
    failures = []
    rng = np.random.default_rng(7)
    # 1. numpy Mertens at tiny, odd and tiled sizes
    worst = 0.0
    for (h, w, coarse) in ((1, 1, 48), (2, 3, 48), (3, 2, 2), (5, 7, 2), (17, 9, 2), (97, 131, 8), (191, 255, 8), (192, 256, 48),
                           (301, 157, 4), (64, 1, 2)):
        xx = np.mgrid[0:h, 0:w][1]
        lum = np.exp(rng.uniform(np.log(0.002), np.log(20.0), (h, w))) * np.where(xx > w * 0.6, 8.0, 1.0)
        img = rgba(lum[..., None] * np.exp(0.3 * rng.standard_normal((h, w, 3))))
        out, n = pyramid(gl, img, uniforms(), coarse)
        d = float(np.max(np.abs(out - ref_pyramid(img[..., :3], uniforms(), n))))
        worst = max(worst, d)
        if REPORT:
            print('reference %4dx%-4d %d levels: max |GL - numpy Mertens| %.2e' % (w, h, n, d))
    if worst > 1e-3:
        failures.append('the pyramid differs from the numpy Mertens reference by %.2e' % worst)
    # 2. flat neutral areas: the guided filter's value (fusedAt)
    for mono in (0.0, 0.04):
        for level in (0.01, 0.08, 0.3, 1.0, 4.0, 24.0):
            low = rgba(np.full((96, 128, 3), level))
            p, _ = pyramid(gl, low, uniforms(mono=mono), coarse=8)
            g = guided(gl, low, uniforms(mono=mono))
            d = float(np.max(np.abs(p - g)))
            if REPORT:
                print('flat %.3f mono %.2f: pyramid %.5f guided %.5f max |diff| %.2e' % (level, mono, float(p.mean()), float(g.mean()), d))
            if d > 2e-3:
                failures.append('flat level %.3f mono %.2f differs from the guided filter by %.2e' % (level, mono, d))
    # 3. one active layer: that layer's luma back exactly
    for (h, w) in ((192, 256), (191, 255), (97, 131)):
        yy, xx = np.mgrid[0:h, 0:w]
        base = 0.15 * (1 + 0.6 * np.sin(xx / 3.1) * np.cos(yy / 4.3)) * np.where(xx > w // 2, 6.0, 1.0)
        img = rgba(np.repeat((base * np.exp(0.2 * rng.standard_normal((h, w))))[..., None], 3, -1))
        one = uniforms(lw=(0.0, 1.0, 0.0, 0.0))
        p, n = pyramid(gl, img, one, coarse=8)
        d = float(np.max(np.abs(p - guided(gl, img, one, radius=2, eps=1e9))))  # eps huge: q = the ~one-hot weights
        if REPORT:
            print('one layer %dx%d (%d levels): max |pyramid - L_mid| %.2e' % (w, h, n, d))
        if d > 3e-3:
            failures.append('one active layer %dx%d: the pyramid does not reconstruct the luma (%.2e)' % (w, h, d))
    # 4. P310: narrow saturated lights keep the guided filter's core (5 levels: the depth of a 12 MP whyLow)
    h, w, coarse = 768, 1024, 24
    yy, xx = np.mgrid[0:h, 0:w]
    rgb = np.full((h, w, 3), 0.03)
    bars = ((100, 2), (300, 6), (500, 16))
    for x0, bw in bars:
        rgb[(xx >= x0) & (xx < x0 + bw) & (yy > 150) & (yy < 600)] = 8.0 * np.array((0.55, 0.12, 1.0))
    img = rgba(rgb)
    p, n = pyramid(gl, img, BENTO, coarse)
    g = guided(gl, img, BENTO)
    for x0, bw in bars:
        xc = x0 + bw // 2
        if REPORT:
            print('violet bar %2d px (%d levels): core pyramid %.4f guided %.4f' % (bw, n, p[375, xc], g[375, xc]))
        if abs(p[375, xc] - g[375, xc]) > 0.01 or p[375, xc] > 0.85:
            failures.append('P310: the %d px violet bar core is %.4f (guided filter %.4f): bleached' % (bw, p[375, xc], g[375, xc]))
    # 5. the horizon's halo (sky 1.5 over ground 0.03, 5 levels): height and width against the guided filter, pinned
    rgb = np.where((yy < 384)[..., None], 1.5, 0.03) * np.ones((h, w, 3))
    img = rgba(rgb)
    p, n = pyramid(gl, img, uniforms(), coarse)
    g = guided(gl, img, uniforms())
    col = (p - g)[:, 400:600].mean(1)
    height = float(np.max(np.abs(col)))
    width = int(np.sum(np.abs(col) > 0.005))
    print('horizon halo (%d levels): height %.4f of fused luma, %d rows over 0.005 (guided filter as the reference)' % (n, height, width))
    # P333 at ef_coarse 48 on a 12 MP whyLow (here 1024 x 768 at coarse 24: the same 5 levels): height 0.032, 57 rows
    if height > 0.04 or width > 70:
        failures.append('the horizon halo grew: height %.4f (pinned 0.04), %d rows (pinned 70)' % (height, width))
    gl.ctx.release()
    if failures:
        for f in failures:
            print('FAIL: ' + f)
        sys.exit(1)
    print('PASS: the pyramid fusion matches the numpy Mertens reference (%.1e), the guided filter on flat areas, one layer exactly; '
          'P310 lights keep their core; the halo stays pinned' % worst)


if __name__ == '__main__':
    main()
