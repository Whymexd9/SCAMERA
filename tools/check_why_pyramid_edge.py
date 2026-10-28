#!/usr/bin/env python3
"""The window-halo guard (why/lpyr edgeMixU, WhyFusion edge share): host GL, the shipped helpers of
tools/check_why_pyramid.py, the shipped shaders (WT_SHADERS: another copy). Checks:
1. a window: a 5 EV darker stile (170 px) and transom bar (22 px) in bright glass (Bento weights, 5 levels): with the edge share
   the glass next to the frame and the bar interior stay within 0.01 of fused luma of the guided filter (the pyramid: ~0.03-0.05);
2. the hard horizon of check_why_pyramid (sky 1.5 over ground 0.03): height < 0.012, rows over 0.005 <= 12 (pyramid: 0.032 / 57);
3. P310: the violet LED bars keep their core with the share on (|edge on - edge off| < 1e-3 at the core);
4. low-contrast texture (+-0.25 EV log-normal field over a 1 EV ramp): the share stays 0, the output equals the pyramid's (< 1e-4);
5. edgeMixU unset: bit-identical to the pyramid without the share (the old output).
"""
import os, sys
from pathlib import Path
import numpy as np
sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_why_pyramid as cwp
cwp.SH = Path(os.environ.get('WT_SHADERS', str(cwp.SH)))
EDGE = (0.3, 1.0, 0.0078125)


def fuse(gl, low, uni, coarse, edge=None, gmix=None):
    """WhyFusion.pyramid with the guided filter for P349 (gmix) and / or the edge share; edge None: the pyramid alone."""
    h, w = low.shape[:2]
    src = gl.tex(low)
    n = cwp.levels_for(w, h, coarse)
    sizes, L, W, F = [(w, h)], [None], [None], [None]
    for l in range(1, n + 1):
        pw, ph = sizes[-1]
        sizes.append(((pw + 1) // 2, (ph + 1) // 2))
        L.append(gl.target(*sizes[l], 4, 'f2')); W.append(gl.target(*sizes[l], 4, 'f2')); F.append(gl.target(*sizes[l], 1, 'f4'))
    gl.draw(gl.prog('why/lpyr', 0), L[1], [('InputBuffer', src)], uni)
    gl.draw(gl.prog('why/lpyr', 1), W[1], [('InputBuffer', src)], uni)
    for l in range(2, n + 1):
        for p in (L, W):
            gl.draw(gl.prog('why/lpyr', 2), p[l], [('InputBuffer', p[l - 1])], {})
    gl.draw(gl.prog('why/lpyr', 3), F[n], [('LevelL', L[n]), ('LevelW', W[n])], {})
    for l in range(n - 1, 0, -1):
        gl.draw(gl.prog('why/lpyr', 4), F[l], [('LevelL', L[l]), ('LevelW', W[l]), ('CoarseL', L[l + 1]), ('CoarseF', F[l + 1])], {})
    tx = [('InputBuffer', src), ('CoarseL', L[1]), ('CoarseF', F[1])]
    u = dict(uni, guidedMixU=(0.0, 0.0), edgeMixU=(0.0, 0.0, 0.0, 0.0))   # moderngl keeps a program's uniforms between draws
    g = None
    if edge is not None or gmix is not None:
        g = gl.tex(cwp.guided(gl, low, uni)[..., None].astype(np.float32), 'f4')
        tx.append(('GuidedF', g))
        u['guidedMixU'] = gmix if gmix is not None else (0.0, 0.0)
    if edge is not None:
        eu = dict(uni, edgeMixU=(edge[0], edge[1], edge[2], float(2 ** n)))
        G = [gl.target(*sizes[1], 1, 'f2')]
        gl.draw(gl.prog('why/lpyr', 6), G[0], [('InputBuffer', src)], eu)
        for l in range(2, n + 1):
            G.append(gl.target(*sizes[l], 1, 'f2'))
            gl.draw(gl.prog('why/lpyr', 2), G[-1], [('InputBuffer', G[-2])], {})
        tx.append(('GuideC', G[-1]))
        u['edgeMixU'] = eu['edgeMixU']
    out = gl.target(w, h, 1, 'f4')
    gl.draw(gl.prog('why/lpyr', 5), out, tx, u)
    return gl.read(out)[:, :, 0], n


def main():
    gl = cwp.Gl()
    fails = []
    B = dict(cwp.BENTO)
    # 5. share unset: the pyramid exactly
    img = cwp.rgba(np.exp(np.random.default_rng(5).uniform(np.log(0.002), np.log(20.0), (191, 255)))[..., None] * np.ones(3))
    p0, n = cwp.pyramid(gl, img, B, 8)        # first, in a fresh context: no uniform left from another draw
    p1, _ = fuse(gl, img, B, 8)
    d = float(np.max(np.abs(p1 - p0)))
    print('share unset: max |fuse - check_why_pyramid.pyramid| %.2e' % d)
    if d > 0:
        fails.append('share unset differs from the pyramid (%.2e)' % d)
    h, w, coarse = 768, 1024, 24          # 5 levels: the depth of a 12 MP whyLow (check_why_pyramid's sizes)
    yy, xx = np.mgrid[0:h, 0:w]
    B = dict(cwp.BENTO)
    # 1. window: glass 0.93 (x AE 1), stile x 300-470 and transom y 380-402 at 0.026
    rgb = np.full((h, w, 3), 0.93)
    rgb[(xx >= 300) & (xx < 470)] = 0.026
    rgb[(yy >= 380) & (yy < 402) & (xx >= 470)] = 0.026
    img = cwp.rgba(rgb)
    p0, n = fuse(gl, img, B, coarse)
    pe, _ = fuse(gl, img, B, coarse, edge=EDGE)
    g = cwp.guided(gl, img, B)
    zones = {'glass 2-40 px right of the stile': (slice(500, 700), slice(472, 510)),
             'glass 2-40 px under the transom': (slice(404, 442), slice(600, 900)),
             'glass 2-40 px over the transom': (slice(340, 378), slice(600, 900)),
             'stile 2-40 px left of the glass': (slice(500, 700), slice(430, 468)),
             'transom interior': (slice(384, 398), slice(600, 900))}
    for name, sl in zones.items():
        dp, de = float(np.max(np.abs(p0[sl] - g[sl]))), float(np.max(np.abs(pe[sl] - g[sl])))
        print('window %-34s |pyramid - gf| max %.4f, with the edge share %.4f' % (name, dp, de))
        if de > 0.01:
            fails.append('window %s: %.4f from the guided filter with the edge share' % (name, de))
    # 2. the horizon
    rgb = np.where((yy < 384)[..., None], 1.5, 0.03) * np.ones((h, w, 3))
    img = cwp.rgba(rgb)
    u = cwp.uniforms()
    pe, n = fuse(gl, img, u, coarse, edge=EDGE)
    p0, _ = fuse(gl, img, u, coarse)
    g = cwp.guided(gl, img, u)
    for tag, p in (('pyramid', p0), ('edge share', pe)):
        col = (p - g)[:, 400:600].mean(1)
        hgt, wid = float(np.max(np.abs(col))), int(np.sum(np.abs(col) > 0.005))
        print('horizon halo (%d levels) %-10s: height %.4f, %d rows over 0.005' % (n, tag, hgt, wid))
    if hgt > 0.012 or wid > 12:
        fails.append('horizon halo with the edge share: height %.4f, %d rows' % (hgt, wid))
    # 3. P310 LED bars
    rgb = np.full((h, w, 3), 0.03)
    bars = ((100, 2), (300, 6), (500, 16))
    for x0, bw in bars:
        rgb[(xx >= x0) & (xx < x0 + bw) & (yy > 150) & (yy < 600)] = 8.0 * np.array((0.55, 0.12, 1.0))
    img = cwp.rgba(rgb)
    p0, _ = fuse(gl, img, B, coarse, gmix=(0.8, 1.6))
    pe, _ = fuse(gl, img, B, coarse, edge=EDGE, gmix=(0.8, 1.6))
    for x0, bw in bars:
        xc = x0 + bw // 2
        d = abs(float(pe[375, xc] - p0[375, xc]))
        print('violet bar %2d px: core %.4f (share off %.4f)' % (bw, pe[375, xc], p0[375, xc]))
        if d > 1e-3:
            fails.append('P310 bar %d px core moved by %.4f with the edge share' % (bw, d))
    # 4. low-contrast texture keeps the pyramid
    rng = np.random.default_rng(3)
    fld = rng.standard_normal((h // 8 + 1, w // 8 + 1))
    fld = np.kron(fld, np.ones((8, 8)))[:h, :w]
    lum = 0.2 * np.exp2(xx / w + 0.25 * np.clip(fld, -1, 1))
    img = cwp.rgba(np.repeat(lum[..., None], 3, -1))
    p0, _ = fuse(gl, img, B, coarse)
    pe, _ = fuse(gl, img, B, coarse, edge=EDGE)
    d = float(np.max(np.abs(pe - p0)))
    print('low-contrast texture: max |edge share - pyramid| %.2e' % d)
    if d > 1e-4:
        fails.append('low-contrast texture left the pyramid (%.2e)' % d)
    gl.ctx.release()
    for f in fails:
        print('FAIL: ' + f)
    if fails:
        sys.exit(1)
    print('PASS: window and horizon halos within 0.01 / 0.012 of the guided filter, P310 cores, low-contrast texture and the unset share unchanged')


if __name__ == '__main__':
    main()
