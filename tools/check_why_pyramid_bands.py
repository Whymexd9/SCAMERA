#!/usr/bin/env python3
"""P391: the WHY pyramid's edge-aware bands (why/lpyr.glsl EDGE_BANDS 1, WhyFusion.pyramid with WhyTone.bands) and its continuous
depth on the host GPU, against an independent numpy reference and the shipped shares they replace (P349's guided share above white,
P389's edge share).

The programs are built and drawn as check_why_pyramid's (GLInterface.readProgram, GLProg's units and 256-row tiles), in
WhyFusion's pass order: lpyr MODE 0 / 1, the MODE 2 chains, MODE 7 (level 1 of the guide's log light and the per-pixel fusion) and
its chain REF_DOWN - 1 levels beyond the pyramid's, MODE 9 / 8 (the edge measure D of level 0 and of the levels 1 .. n - 1: the
guide's log light against its Gaussian REF_DOWN levels down, the noise taken out in quadrature), MODE 10 (the edge shares of the
levels 1 .. n: the ramp of the level's cone max of D, at least the finer level's carried 3 x 3), MODE 3 / 4 / 5 with EDGE_BANDS 1
(level 0's share in place). The defaults are read from WhyTone.java. Scenes are a 12 MP whyLow at half scale (1024 x 768, 5 levels
at coarse 24). Checks:
1. bands off: the pyramid exactly (the same passes as check_why_pyramid); WT_OLD_SHADERS=<dir of the shaders before P391>: the
   off path (EDGE_BANDS 0, deepNoiseU unset) bit-identical to them on the photo's uniforms;
2. the numpy reference (the coarsest level moved towards Fpp's Gaussian by its carried share, every band P + (1 - a) E) within
   2e-3 at 5 levels, odd and tiny sizes, and with a fractional lowest band; with the noise cap and the edges' noise shift its
   99.9th percentile within 2e-3 and its maximum within 1e-2 (the cap's ramp, a few noise sigmas of the guide's band wide, is
   moved by the fp16 rounding of the stored guide levels at the pixels on it); WhyTone.EDGE_STEP / EDGE_BETA = the edge
   measure's step response (within 0.01) and white-noise factor (within 3 %) re-measured; flat areas = the pyramid;
3. the halos: a hard horizon (sky 1.5 over ground 0.03) and a window (a 5 EV darker stile and transom in bright glass, Bento
   weights) within 0.003 of fused luma of the guided filter (edge-aware) and no row over 0.005, also with a night model's
   noise shift of the edges; a lamp (8 x white, r 10) without
   a ring of either sign (within +-0.002); twigs against a sky of 0.6 and 1.2, per distance from them (0.5 - 3, 3 - 8, 8 - 12,
   12 - 24 px): no dark fringe (>= -0.0015) and no glow (<= +0.0025), p95 <= 0.0075; a 2 px dark line on 0.6: beside it >=
   -0.002 and no brighter than the pyramid's; P349's dark rungs in a glow above white (no share): p95 <= 0.005 of the guided
   filter; steps at I 0.2: 0.5 EV keeps >= 0.95 of the pyramid's pop, 1 EV no more than the pyramid's (a 1 EV step reads as a
   +-0.5 EV texture), 1.5 EV <= 1.1 x the shipped shares', 2 EV and more <= 0.005; steps in deep shadows (1.5 - 3 EV from
   I 0.004 - 0.03), with and without a night model's noise shift: no side glows more than 5e-4 over the lesser of the pyramid's
   and the shipped shares';
4. E against the shipped shares on their own targets: the window zones, the horizon and the rungs at least as close to the
   guided filter as the pyramid with P349 + P389;
5. P310: the violet LED bars keep the guided filter's core (within 0.01, not white);
6. texture and clouds: high-pass contrast >= 0.97 of the pyramid's (+-0.25 EV, clouds); +-0.5 EV 4 and 8 px blocks under the
   HDR exposures at base 0.05 - 0.6 >= 0.88 of the pyramid's and at most 0.03 under the shipped shares'; strength 0 = the
   pyramid (no noise cap, whole levels) within 1e-6;
7. continuous depth (WhyFusion.depth): f = 0 is one level less within 1e-6, f = 1 the whole levels bit for bit; the depth of a
   4096 x 3072 and a 4000 x 3000 sensor (whyLow 1536 / 1500 rows) within 0.05 levels, not one whole level apart.
Usage: check_why_pyramid_bands.py [--report]
"""
import os
import re
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_why_pyramid as cwp  # noqa: E402
import check_why_pyramid_edge as cwe  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
REPORT = '--report' in sys.argv
H, W = 768, 1024
LUMA = np.array([0.2126, 0.7152, 0.0722])


def shipped():
    """WhyTone's defaults of the bands {lo, hi (EV of a step), the band floor z, strength, reach, fall}, the noise cap {lo, hi,
    levels}, BAND_BETA (the cap's band noise), the edges' noise shift k and EDGE_STEP / EDGE_BETA (the edge measure's step response
    and white-noise factor per level)."""
    src = (ROOT / 'app/src/main/java/com/particlesdevs/photoncamera/processing/opengl/postpipeline/WhyTone.java').read_text(encoding='utf-8')
    v = {k: float(re.search(r'value\("%s", ([\d.]+)f\)' % k, src).group(1))
         for k in ('fusion_bands_lo', 'fusion_bands_hi', 'fusion_bands_strength', 'fusion_bands_reach', 'fusion_bands_fall', 'fusion_noise_lo',
                   'fusion_noise_hi', 'fusion_noise_levels', 'fusion_bands_noise')}
    floor = float(re.search(r'BAND_FLOOR = ([\d.]+)f;', src).group(1))

    def arr(name):
        return tuple(float(x) for x in re.search(r'%s = \{([^}]*)\}' % name, src).group(1).replace('f', '').split(','))
    return ((v['fusion_bands_lo'], v['fusion_bands_hi'], floor, v['fusion_bands_strength'], int(v['fusion_bands_reach']), v['fusion_bands_fall']),
            (v['fusion_noise_lo'], v['fusion_noise_hi'], int(v['fusion_noise_levels'])), arr('BAND_BETA'), v['fusion_bands_noise'],
            arr('EDGE_STEP'), arr('EDGE_BETA'))


BANDS, NOISE, BETA, EDGE_SIGMAS, EDGE_STEP, EDGE_BETA = shipped()
# the edge measure's reference: the guide's Gaussian this many levels down (lpyr RefG, node spacing 2^REF_DOWN)
REF_DOWN = 3


def beta(l):
    """WhyTone.bandBeta: the white-noise factor of level l's band (halving below level 3)."""
    return BETA[l] if l < len(BETA) else BETA[-1] / 2 ** (l - len(BETA) + 1)


def edge_beta(l):
    """WhyTone.edgeBeta: the white-noise factor of the edge measure D of level l (halving below the table)."""
    return EDGE_BETA[l] if l < len(EDGE_BETA) else EDGE_BETA[-1] / 2 ** (l - len(EDGE_BETA) + 1)


def edge_ramp(bands, l):
    """WhyTone.edgeRamp: level l's ramp of D and its fall per level px - {lo, hi} EV of a step through the level's step response
    (D of a step of S EV peaks near S / 2 x EDGE_STEP[l]), the fall a share of hi."""
    s = EDGE_STEP[min(l, len(EDGE_STEP) - 1)]
    lo, hi = 0.5 * bands[0] * s, 0.5 * bands[1] * s
    return lo, hi, bands[5] * hi


class Gl(cwp.Gl):
    """check_why_pyramid.Gl with programs of any defines and of another shader directory."""

    def progd(self, name, defines, sh=None):
        sh = Path(sh) if sh else cwp.SH
        key = (name, tuple(sorted(defines.items())), str(sh))
        if key not in self.progs:
            old = cwp.SH
            cwp.SH = sh
            try:
                src = cwp.read_program((sh / (name + '.glsl')).read_text(), list(defines.items()))
            finally:
                cwp.SH = old
            self.progs[key] = self.ctx.program(vertex_shader=cwp.VS, fragment_shader=src)
        return self.progs[key]


def depth(w, h, coarse):
    """WhyFusion.depth: {levels, f}."""
    c = max(2, int(round(coarse)))
    d = np.log2(min(w, h) / float(c))
    if not d > 1.0:
        return 1, 1.0
    levels = int(np.ceil(d - 1e-6))
    f = d - (levels - 1)
    return levels, (1.0 if f >= 1.0 - 1e-6 else max(0.0, float(f)))


def band_noise(shift, level):
    """WhyFusion.bandNoise: shift None or (k, S_I, O_I) - an edge counts beyond k sigmas of the edge measure's noise."""
    return {'bandNoiseU': (float(shift[0] * edge_beta(level)), float(shift[1]), float(shift[2])) if shift else (0.0, 0.0, 0.0)}


def band_uniforms(bands, noise, level, low_cut, shift=None):
    """WhyFusion.bandUniforms of a MODE 4 / 5 draw: bands (lo, hi, floor, strength, reach, fall); noise None or (k1, k2, levels,
    S_I, O_I)."""
    lo, hi, fall = edge_ramp(bands, level)
    u = {'bandEdgeU': (float(lo), float(hi), float(bands[2]), float(bands[3])), 'bandReachU': (int(bands[4]), 0),
         'coneU': (float(fall), 0.0, 0.0, 0.0), 'levelU': int(level), 'lowCutU': float(low_cut), 'refScaleU': float(2 ** REF_DOWN)}
    u.update(band_noise(shift, level))
    if noise is not None:
        u['noiseBandU'] = (float(noise[0]), float(noise[1]), float(noise[3]), float(noise[4]))
        u['noiseBetaU'] = tuple(float(BETA[l]) if l < min(4, noise[2]) else 0.0 for l in range(4))
    else:
        u['noiseBandU'] = (0.0, 0.0, 0.0, 0.0)
        u['noiseBetaU'] = (0.0, 0.0, 0.0, 0.0)
    return u


def share_uniforms(bands, level, coarsest):
    """WhyFusion.shareUniforms of a MODE 10 draw (the edge share of level >= 1): its own ramp and cone (none on the coarsest level),
    the carry from level 0's D (level 1: level 0's ramp and cone, the window one wider) or the finer level's share (3 x 3)."""
    lo, hi, fall = edge_ramp(bands, level)
    lo0, hi0, fall0 = edge_ramp(bands, 0) if level == 1 else (0.0, 0.0, 0.0)
    return {'bandEdgeU': (float(lo), float(hi), float(bands[2]), float(bands[3])),
            'bandReachU': (-1 if coarsest else int(bands[4]), int(bands[4]) if level == 1 else 1),
            'coneU': (float(fall), float(lo0), float(hi0), float(fall0))}


def fuse(gl, low, uni, coarse=24, bands=None, noise=None, low_band=1.0, levels=None, gmix=None, edge=None, sh=None, shift=None):
    """WhyFusion.pyramid on whyLow {low} (H x W x 4): bands None = EDGE_BANDS 0 (the pyramid; gmix / edge: P349's guided share,
    P389's edge share, as shipped before P391), else EDGE_BANDS 1 with the noise cap {noise}, the lowest band's weight {low_band}
    and the edges' noise shift {shift} (k, S_I, O_I). -> (fused, levels)."""
    h, w = low.shape[:2]
    src = gl.tex(low)
    n = levels or cwp.levels_for(w, h, coarse)
    eb = '1' if bands is not None else '0'
    P = lambda mode, eb_=eb: gl.progd('why/lpyr', {'MODE': str(mode), 'EDGE_BANDS': eb_}, sh)
    deep = n + REF_DOWN - 1 if bands is not None else n
    sizes, L, Wt, F, GF, D, A = [(w, h)], [None], [None], [None], [None], [None], [None]
    for l in range(1, deep + 1):
        pw, ph = sizes[-1]
        sizes.append(((pw + 1) // 2, (ph + 1) // 2))
        if bands is not None:
            GF.append(gl.target(*sizes[l], 2, 'f2'))
        if l <= n:
            L.append(gl.target(*sizes[l], 4, 'f2'))
            Wt.append(gl.target(*sizes[l], 4, 'f2'))
            F.append(gl.target(*sizes[l], 1, 'f4'))
            D.append(gl.target(*sizes[l], 1, 'f2') if bands is not None and l < n else None)
            A.append(gl.target(*sizes[l], 2, 'f2') if bands is not None else None)
    # moderngl keeps a program's uniforms between draws: every draw sets all of its own
    u = dict(uni, guidedMixU=(0.0, 0.0), edgeMixU=(0.0, 0.0, 0.0, 0.0))
    gl.draw(P(0, '0'), L[1], [('InputBuffer', src)], u)
    gl.draw(P(1, '0'), Wt[1], [('InputBuffer', src)], u)
    for l in range(2, n + 1):
        for p in (L, Wt):
            gl.draw(P(2, '0'), p[l], [('InputBuffer', p[l - 1])], {})
    if bands is not None:
        z = (0.0, 0.0, float(bands[2]), float(bands[3]))
        gl.draw(P(7, '0'), GF[1], [('InputBuffer', src)], dict(u, bandEdgeU=z))
        for l in range(2, deep + 1):
            gl.draw(P(2, '0'), GF[l], [('InputBuffer', GF[l - 1])], {})
        # the edge measure D of level 0 (whyLow's size) and of the levels 1 .. n - 1, against the guide REF_DOWN levels down
        D0 = gl.target(w, h, 1, 'f2')
        ref = {'refScaleU': float(2 ** REF_DOWN), 'bandEdgeU': z}
        gl.draw(P(9, '0'), D0, [('InputBuffer', src), ('RefG', GF[REF_DOWN])], dict(u, **ref, **band_noise(shift, 0)))
        for l in range(1, n):
            gl.draw(P(8, '0'), D[l], [('LevelGF', GF[l]), ('RefG', GF[l + REF_DOWN])], dict(ref, **band_noise(shift, l)))
        # the shares of the levels 1 .. n: the ramp of the level's cone max of D, at least the finer level's carried 3 x 3
        for l in range(1, n + 1):
            tx = [('FineA', D0 if l == 1 else A[l - 1])]
            if l < n:
                tx.append(('LevelA', D[l]))
            gl.draw(P(10, '0'), A[l], tx, share_uniforms(bands, l, l == n))
        gl.draw(P(3), F[n], [('LevelL', L[n]), ('LevelW', Wt[n]), ('LevelGF', GF[n]), ('LevelA', A[n])], {'bandEdgeU': z})
    else:
        gl.draw(P(3), F[n], [('LevelL', L[n]), ('LevelW', Wt[n])], {})
    for l in range(n - 1, 0, -1):
        tx = [('LevelL', L[l]), ('LevelW', Wt[l]), ('CoarseL', L[l + 1]), ('CoarseF', F[l + 1])]
        un = {}
        if bands is not None:
            tx += [('LevelGF', GF[l]), ('CoarseGF', GF[l + 1]), ('LevelA', A[l])]
            un = band_uniforms(bands, noise, l, 1.0 - low_band if l == n - 1 else 0.0, shift)
        gl.draw(P(4), F[l], tx, un)
    tx = [('InputBuffer', src), ('CoarseL', L[1]), ('CoarseF', F[1])]
    u5 = dict(u)
    if bands is not None:
        tx += [('CoarseGF', GF[1]), ('LevelA', D0)]
        u5.update(band_uniforms(bands, noise, 0, 1.0 - low_band if n == 1 else 0.0, shift))
    if edge is not None or gmix is not None:
        g = gl.tex(cwp.guided(gl, low, uni)[..., None].astype(np.float32), 'f4')
        tx.append(('GuidedF', g))
        u5['guidedMixU'] = gmix if gmix is not None else (0.0, 0.0)
    if edge is not None:
        eu = dict(uni, edgeMixU=(edge[0], edge[1], edge[2], float(2 ** n)))
        G = [gl.target(*sizes[1], 1, 'f2')]
        gl.draw(P(6, '0'), G[0], [('InputBuffer', src)], eu)
        for l in range(2, n + 1):
            G.append(gl.target(*sizes[l], 1, 'f2'))
            gl.draw(P(2, '0'), G[-1], [('InputBuffer', G[-2])], {})
        tx.append(('GuideC', G[-1]))
        u5['edgeMixU'] = eu['edgeMixU']
    out = gl.target(w, h, 1, 'f4')
    gl.draw(P(5), out, tx, u5)
    res = gl.read(out)[:, :, 0]
    for t in [src, out] + [x for x in L + Wt + F + GF + D + A if x is not None] + ([D0] if bands is not None else []):
        t.release()
    return res, n


# ---- numpy reference (independent of the shaders: no P291 envelope, no P310 share, no deep floor)
def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / max(e1 - e0, 1e-12), 0, 1)
    return t * t * (3 - 2 * t)


def dilate(a, r):
    if r <= 0:
        return a
    p = np.pad(a, r, mode='edge')
    h, w = a.shape
    out = a.copy()
    for dy in range(2 * r + 1):
        for dx in range(2 * r + 1):
            out = np.maximum(out, p[dy:dy + h, dx:dx + w])
    return out


def cone(x, r, fall):
    """The largest x(q) - fall x Chebyshev |q - p| over |q - p| <= r (edges clamped)."""
    if r <= 0:
        return x
    p = np.pad(x, r, mode='edge')
    h, w = x.shape
    out = x.copy()
    for dy in range(-r, r + 1):
        for dx in range(-r, r + 1):
            out = np.maximum(out, p[r + dy:r + dy + h, r + dx:r + dx + w] - fall * max(abs(dy), abs(dx)))
    return out


def up_nodes(c, m, h, w):
    """lpyr refAt: a level m down, linear between its nodes (node i on fine i 2^m), clamped."""
    ch, cw = c.shape
    sy, sx = np.arange(h) / float(2 ** m), np.arange(w) / float(2 ** m)
    y0, x0 = np.minimum(np.floor(sy).astype(int), ch - 1), np.minimum(np.floor(sx).astype(int), cw - 1)
    y1, x1 = np.minimum(y0 + 1, ch - 1), np.minimum(x0 + 1, cw - 1)
    fy, fx = np.clip(sy - y0, 0, 1)[:, None], np.clip(sx - x0, 0, 1)[None, :]
    return (c[y0][:, x0] * (1 - fx) + c[y0][:, x1] * fx) * (1 - fy) + (c[y1][:, x0] * (1 - fx) + c[y1][:, x1] * fx) * fy


def guide_np(rgb, u):
    lin = np.maximum(rgb, 0) * u['aeU']
    return np.maximum(lin @ LUMA, lin.max(-1) * 0.5)


def ref_shares(Gs, n, bands, shift=None):
    """The edge shares of levels 0 .. n (numpy): D_l = |G_l - G_l+3 at the nodes| less k noise sigmas in quadrature, the ramp of
    its cone max; a level >= 1 at least the 3 x 3 max of the finer one's, the coarsest the carry alone. -> (A, carry)."""
    lo, hi, z, strength, reach, fall = bands
    A, C = [], [None]
    for l in range(n + 1):
        if l < n:
            g = Gs[l]
            h, w = g.shape
            d = np.abs(g - up_nodes(Gs[l + REF_DOWN], REF_DOWN, h, w))
            if shift:
                Il = np.maximum(np.exp2(g) - z, 0)
                s = shift[0] * edge_beta(l) * np.sqrt(np.maximum(shift[1] * Il + shift[2], 0)) / ((Il + z) * np.log(2))
                d = np.sqrt(np.maximum(d * d - s * s, 0))
            elo, ehi, efall = edge_ramp(bands, l)
            own = smoothstep(elo, ehi, cone(d, reach, efall))
        if l == 0:
            A.append(own)
            continue
        h, w = Gs[l].shape
        carry = dilate(A[l - 1], 1)[::2, ::2][:h, :w]
        C.append(carry)
        A.append(np.maximum(own, carry) if l < n else carry)
    return [a * strength for a in A], [None] + [c * strength for c in C[1:]]


def ref_bands(rgb, u, n, bands, noise=None, low_band=1.0, shift=None):
    z = bands[2]
    rgb = rgb.astype(np.float64)
    Lk, p = cwp.prepare_np(rgb, u)
    I = guide_np(rgb, u)
    Ls, Ws, Fs = [Lk], [p], [(p * Lk).sum(-1)[..., None]]
    Gs = [np.log2(I + z)[..., None]]
    for _ in range(n):
        for s in (Ls, Ws, Fs):
            s.append(cwp.reduce_np(s[-1]))
    for _ in range(n + REF_DOWN - 1):
        Gs.append(cwp.reduce_np(Gs[-1]))
    Gs = [g[..., 0] for g in Gs]
    A, C = ref_shares(Gs, n, bands, shift)
    pyr = [(Ws[l] * Ls[l]).sum(-1) / Ws[l].sum(-1) for l in range(n + 1)]
    Fpp = [f[..., 0] for f in Fs]
    F = pyr[n] + A[n] * (Fpp[n] - pyr[n])
    for l in range(n - 1, -1, -1):
        h, w = Ls[l].shape[:2]
        M = (Ws[l] * (Ls[l] - cwp.expand_np(Ls[l + 1], h, w))).sum(-1) / Ws[l].sum(-1)
        Pb = Fpp[l] - cwp.expand_np(Fs[l + 1], h, w)[..., 0]
        g = Gs[l]
        E = M - Pb
        if noise is not None and l < min(4, noise[2]):
            bG = np.abs(g - cwp.expand_np(Gs[l + 1][..., None], h, w)[..., 0])
            Il = np.maximum(np.exp2(g) - z, 0)
            sG = BETA[l] * np.sqrt(np.maximum(noise[3] * Il + noise[4], 0)) / ((Il + z) * np.log(2))
            E = np.where(E * Pb > 0, E * smoothstep(noise[0], noise[1], bG / np.maximum(sG, 1e-30)), E)
        v = cwp.expand_np(F[..., None], h, w)[..., 0] + Pb + (1 - A[l]) * E
        if l == n - 1 and low_band < 1.0:
            # the fractional depth: towards the base of one level less (no level: the per-pixel fusion)
            base = Fpp[l] if l == 0 else pyr[l] + C[l] * (Fpp[l] - pyr[l])
            v = base + low_band * (v - base)
        F = v
    return F


def per_pixel(rgb, u):
    Lk, p = cwp.prepare_np(rgb.astype(np.float64), u)
    return (p * Lk).sum(-1)


def plain(u):
    """The fusion's uniforms without P291's envelope, P310's share and the deep floor (the numpy reference's)."""
    return dict(u, monoU=0.0, satLightU=(0.0, 0.0, 0.0, 0.0), satLevelU=(0.0, 0.0), warmU=(0.0, 0.0), deepFloorU=0.0)


# P348 / P378 defaults without Bento (check_why_sky FUSION) and the pre-P348 set of check_why_pyramid.uniforms
FUSION = plain({'expU': (0.125, 1.0, 0.354, 3.03), 'layerU': (0.8, 1.0, 0.1, 1.0), 'aeU': 1.0, 'centerU': 0.6, 'smoothU': 0.25,
                'gammaInvU': 1 / 2.2, 'toeU': 0.05})
OLD = plain(cwp.uniforms())
BENTO_PLAIN = plain(cwp.BENTO)
BENTO = dict(cwp.BENTO, deepFloorU=2.0)            # the photo's: P291, P310, the deep floor
EDGE = (0.3, 1.0, 0.0078125)
GMIX = (0.8, 1.6)


def grey(lum):
    return cwp.rgba(np.repeat(lum[..., None], 3, -1))


def halo(F, ref, cols=slice(400, 600), thr=0.005):
    col = (F - ref)[:, cols].mean(1)
    return float(np.max(np.abs(col))), int(np.sum(np.abs(col) > thr))


def box(a, r):
    pad = np.pad(a, r, mode='edge')
    c = np.cumsum(np.cumsum(np.pad(pad, ((1, 0), (1, 0))), 0), 1)
    k = 2 * r + 1
    return (c[k:, k:] - c[:-k, k:] - c[k:, :-k] + c[:-k, :-k]) / (k * k)


def hp_std(F, r=16):
    return float((F - box(F, r))[32:-32, 32:-32].std())


def twigs(seed=4, sky=1.2, twig=0.03, count=60):
    """Thin dark branches (half width 0.6 - 1.6 px) against a bright sky, anti-aliased; their distance field."""
    rng = np.random.default_rng(seed)
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float64)
    dist = np.full((H, W), 1e9)
    for _ in range(count):
        x0, y0 = rng.uniform(100, W - 100), rng.uniform(100, H - 100)
        ang = rng.uniform(0, np.pi)
        ln = rng.uniform(40, 160)
        x1, y1 = x0 + ln * np.cos(ang), y0 + ln * np.sin(ang)
        wdt = rng.uniform(0.6, 1.6)
        dx, dy = x1 - x0, y1 - y0
        t = np.clip(((xx - x0) * dx + (yy - y0) * dy) / (dx * dx + dy * dy), 0, 1)
        dist = np.minimum(dist, np.hypot(xx - (x0 + t * dx), yy - (y0 + t * dy)) - wdt)
    cover = np.clip(0.5 - dist, 0, 1)
    return sky * (1 - cover) + twig * cover, dist


def clouds(seed=11, base=1.0, amp=0.35, octaves=(4, 8, 16, 32, 64)):
    """Cloud-like smooth texture in the highlights (P378's clear sky): bilinear noise octaves."""
    rng = np.random.default_rng(seed)
    acc = np.zeros((H, W))
    for o in octaves:
        g = rng.standard_normal((H // o + 2, W // o + 2))
        yy, xx = np.mgrid[0:H, 0:W] / o
        y0, x0 = yy.astype(int), xx.astype(int)
        fy, fx = yy - y0, xx - x0
        acc += (g[y0, x0] * (1 - fx) * (1 - fy) + g[y0, x0 + 1] * fx * (1 - fy) + g[y0 + 1, x0] * (1 - fx) * fy
                + g[y0 + 1, x0 + 1] * fx * fy) * (o / 64.0) ** 0.5
    return base * np.exp2(amp * acc / acc.std())


def window():
    yy, xx = np.mgrid[0:H, 0:W]
    lum = np.full((H, W), 0.93)
    lum[(xx >= 300) & (xx < 470)] = 0.026
    lum[(yy >= 380) & (yy < 402) & (xx >= 470)] = 0.026
    return lum


ZONES = {'glass 2-40 px right of the stile': (slice(500, 700), slice(472, 510)),
         'glass 2-40 px under the transom': (slice(404, 442), slice(600, 900)),
         'glass 2-40 px over the transom': (slice(340, 378), slice(600, 900)),
         'stile 2-40 px left of the glass': (slice(500, 700), slice(430, 468)),
         'transom interior': (slice(384, 398), slice(600, 900))}


def rungs():
    """P349: dark rungs (6 px) in an orange sun glow above white (1.2 .. 8 x white); the glass-side bands round them."""
    yy, xx = np.mgrid[0:H, 0:W]
    glow = 1.2 + 6.8 * np.exp(-np.hypot(yy - 100, xx - 512) / 180.0)
    lum = glow.copy()
    rung = np.zeros((H, W), bool)
    near = np.zeros((H, W), bool)
    for y0 in range(160, 700, 60):
        rung |= (yy >= y0) & (yy < y0 + 6) & (xx > 300) & (xx < 724)
        near |= (((yy >= y0 - 24) & (yy < y0 - 2)) | ((yy >= y0 + 8) & (yy < y0 + 30))) & (xx > 340) & (xx < 684)
    lum[rung] = 0.12
    return cwp.rgba(np.stack([lum, lum * 0.62, lum * 0.33], -1)), near


def main():
    gl = Gl()
    fails = []

    def gate(ok, msg):
        if not ok:
            fails.append(msg)

    def say(msg):
        print(msg, flush=True)

    yy, xx = np.mgrid[0:H, 0:W]
    say('bands %s, noise cap %s sigma on %d levels, beta %s (WhyTone)' % (BANDS, NOISE[:2], NOISE[2], BETA))
    # 1. off = the pyramid (the same passes); against the shaders before P391 when given
    img = cwp.rgba(np.exp(np.random.default_rng(5).uniform(np.log(0.002), np.log(20.0), (191, 255)))[..., None] * np.ones(3))
    p0, _ = cwp.pyramid(gl, img, BENTO, 8)
    p1, _ = fuse(gl, img, BENTO, 8)
    d = float(np.max(np.abs(p1 - p0)))
    say('1. bands off: max |fuse - check_why_pyramid.pyramid| %.2e' % d)
    gate(d == 0.0, 'bands off differs from the pyramid (%.2e)' % d)
    old = os.environ.get('WT_OLD_SHADERS')
    if old:
        for name, u, gm, ed in (('the photo\'s uniforms, P349 + P389', BENTO, GMIX, EDGE), ('the pyramid alone', OLD, None, None)):
            a, _ = fuse(gl, img, u, 8, gmix=gm, edge=ed)
            b, _ = fuse(gl, img, u, 8, gmix=gm, edge=ed, sh=old)
            d = float(np.max(np.abs(a - b)))
            say('   off path against the shaders before P391 (%s): max |diff| %.2e' % (name, d))
            gate(d == 0.0, 'the off path (%s) differs from the shaders before P391 by %.2e' % (name, d))
    # 2. the numpy reference
    worst, noisy_max, noisy_p999 = 0.0, 0.0, 0.0
    cases = [('horizon', grey(np.where(yy < 384, 1.5, 0.03)), OLD), ('window', grey(window()), BENTO_PLAIN),
             ('twigs', grey(twigs()[0]), FUSION), ('clouds', grey(clouds()), FUSION)]
    for name, im, u in cases:
        for noise, lb in ((None, 1.0), ((NOISE[0], NOISE[1], NOISE[2], 2e-4, 1e-6), 1.0), (None, 0.37)):
            shift = (EDGE_SIGMAS, 2e-4, 1e-6) if noise else None
            g, n = fuse(gl, im, u, 24, BANDS, noise, lb, shift=shift)
            r = ref_bands(im[..., :3], u, n, BANDS, noise, lb, shift)
            dd = float(np.max(np.abs(g - r)))
            if noise is None:
                worst = max(worst, dd)
            else:
                # the cap's ramp is a few noise sigmas of the guide's band wide: the fp16 rounding of the stored guide levels (RG16F,
                # ~5e-4 EV) moves it at the pixels on the ramp, so the cap is held to its 99.9th percentile and a looser maximum
                noisy_max = max(noisy_max, dd)
                noisy_p999 = max(noisy_p999, float(np.percentile(np.abs(g - r), 99.9)))
            if REPORT:
                say('   %-8s %d levels noise cap %s, lowest band x%.2f: max |GL - numpy| %.2e, p99.9 %.2e'
                    % (name, n, 'on' if noise else 'off', lb, dd, float(np.percentile(np.abs(g - r), 99.9))))
    rng = np.random.default_rng(7)
    for (h, w, coarse) in ((1, 1, 48), (3, 2, 2), (17, 9, 2), (97, 131, 8), (191, 255, 8), (301, 157, 4)):
        lum = np.exp(rng.uniform(np.log(0.002), np.log(20.0), (h, w))) * np.where(np.mgrid[0:h, 0:w][1] > w * 0.6, 8.0, 1.0)
        im = cwp.rgba(lum[..., None] * np.exp(0.3 * rng.standard_normal((h, w, 3))))
        g, n = fuse(gl, im, OLD, coarse, BANDS)
        dd = float(np.max(np.abs(g - ref_bands(im[..., :3], OLD, n, BANDS))))
        worst = max(worst, dd)
        if REPORT:
            say('   %4dx%-4d %d levels: max |GL - numpy| %.2e' % (w, h, n, dd))
    say('2. the numpy reference of the bands: max |GL - numpy| %.2e; with the noise cap p99.9 %.2e, max %.2e' % (worst, noisy_p999, noisy_max))
    gate(worst <= 2e-3, 'the bands differ from the numpy reference by %.2e' % worst)
    gate(noisy_p999 <= 2e-3 and noisy_max <= 1e-2, 'the noise cap differs from the numpy reference: p99.9 %.2e, max %.2e' % (noisy_p999, noisy_max))
    # the edge measure's constants: a step's largest D over half its EV (the mean over 16 phases on the grid) and D's white-noise
    # factor, re-measured against WhyTone.EDGE_STEP / EDGE_BETA
    nl = len(EDGE_STEP)
    resp = np.zeros((16, nl))
    for ph in range(16):
        g = [np.log2(np.where(yy < 384 + ph, 0.2 * 2.0, 0.2 / 2.0) + BANDS[2])]
        for _ in range(nl + REF_DOWN):
            g.append(cwp.reduce_np(g[-1][..., None])[..., 0])
        for l in range(nl):
            resp[ph, l] = np.abs(g[l] - up_nodes(g[l + REF_DOWN], REF_DOWN, *g[l].shape)).max() / 1.0
    wn = [np.random.default_rng(0).standard_normal((H, W))]
    for _ in range(len(EDGE_BETA) + REF_DOWN):
        wn.append(cwp.reduce_np(wn[-1][..., None])[..., 0])
    nb = []
    for l in range(len(EDGE_BETA)):
        dn = wn[l] - up_nodes(wn[l + REF_DOWN], REF_DOWN, *wn[l].shape)
        m = 8 >> min(l, 3)
        nb.append(float(dn[m:-m, m:-m].std()))
    say('   the edge measure: step response %s (WhyTone %s), white-noise factor %s (WhyTone %s)'
        % (' '.join('%.3f' % v for v in resp.mean(0)), EDGE_STEP, ' '.join('%.3f' % v for v in nb), EDGE_BETA))
    gate(np.all(np.abs(resp.mean(0) - np.array(EDGE_STEP)) <= 0.01), 'WhyTone.EDGE_STEP is not the measured step response %s' % np.round(resp.mean(0), 3))
    gate(np.all(np.abs(np.array(nb) / np.array(EDGE_BETA) - 1) <= 0.03), 'WhyTone.EDGE_BETA is not the measured noise factor %s' % np.round(nb, 3))
    flat = 0.0
    for level in (0.004, 0.03, 0.3, 1.0, 4.0, 24.0):
        im = grey(np.full((96, 128), level))
        a, _ = fuse(gl, im, BENTO, 8, BANDS)
        b, _ = fuse(gl, im, BENTO, 8)
        flat = max(flat, float(np.max(np.abs(a - b))))
    say('   flat areas (the photo\'s uniforms): max |bands - pyramid| %.2e' % flat)
    gate(flat <= 1e-3, 'flat areas move with the bands (%.2e)' % flat)
    # 3. the halos
    im = grey(np.where(yy < 384, 1.5, 0.03))
    gref = cwp.guided(gl, im, OLD)
    # the edges' noise shift of a night model as the photo runs it (review of P391: the halo scenes ran without it)
    night = (EDGE_SIGMAS, 2e-4, 1e-7)
    pe, n = fuse(gl, im, OLD, 24, BANDS)
    pn, _ = fuse(gl, im, OLD, 24, BANDS, shift=night)
    p0, _ = fuse(gl, im, OLD, 24)
    ps, _ = fuse(gl, im, OLD, 24, gmix=GMIX, edge=EDGE)
    hz = {k: halo(v, gref) for k, v in (('pyramid', p0), ('shipped P349 + P389', ps), ('bands', pe), ('bands with a night shift', pn))}
    say('3. horizon (%d levels) against the guided filter: %s' % (n, ', '.join('%s %.4f / %d rows' % (k, v[0], v[1]) for k, v in hz.items())))
    for k in ('bands', 'bands with a night shift'):
        gate(hz[k][0] <= 0.003 and hz[k][1] == 0, 'the horizon\'s halo with the %s: %.4f / %d rows' % (k, *hz[k]))
    im = grey(window())
    gref = cwp.guided(gl, im, BENTO)
    pe, _ = fuse(gl, im, BENTO, 24, BANDS)
    pn, _ = fuse(gl, im, BENTO, 24, BANDS, shift=night)
    p0, _ = fuse(gl, im, BENTO, 24)
    ps, _ = fuse(gl, im, BENTO, 24, gmix=GMIX, edge=EDGE)
    win = {'pyramid': 0.0, 'shipped': 0.0, 'bands': 0.0, 'night': 0.0}
    for name, sl in ZONES.items():
        z = tuple(float(np.max(np.abs(x[sl] - gref[sl]))) for x in (p0, ps, pe, pn))
        win = {k: max(win[k], v) for k, v in zip(win, z)}
        if REPORT:
            say('   window %-34s |x - guided| pyramid %.4f shipped %.4f bands %.4f (night shift %.4f)' % (name, *z))
    say('   window (the photo\'s Bento uniforms) worst zone against the guided filter: pyramid %.4f, shipped %.4f, bands %.4f, with a '
        'night shift %.4f' % (win['pyramid'], win['shipped'], win['bands'], win['night']))
    gate(max(win['bands'], win['night']) <= 0.003, 'the window frame\'s halo with the bands: %.4f (night shift %.4f)' % (win['bands'], win['night']))
    gate(win['bands'] <= win['shipped'] + 1e-4, 'the bands leave more window halo than P389 (%.4f against %.4f)' % (win['bands'], win['shipped']))
    gate(hz['bands'][0] <= hz['shipped P349 + P389'][0] + 1e-4, 'the bands leave more horizon halo than the shipped shares')
    d = np.hypot(yy - 384, xx - 512)
    im = grey(np.where(d < 10, 8.0, 0.03))
    ring = (d > 12) & (d < 60)
    pp = per_pixel(im[..., :3], BENTO_PLAIN)
    lamp = {}
    for tag, b in (('pyramid', None), ('bands', BANDS)):
        F, _ = fuse(gl, im, BENTO_PLAIN, 24, b)
        lamp[tag] = (float((F - pp)[ring].min()), float((F - pp)[ring].max()))
    say('   lamp (8 x white, r 10) ring against the per-pixel fusion, darkest / brightest: pyramid %+.4f / %+.4f, bands %+.4f / %+.4f'
        % (*lamp['pyramid'], *lamp['bands']))
    # two-sided (review of P391: a one-sided gate let a band of either sign through)
    gate(lamp['bands'][0] >= -0.002 and lamp['bands'][1] <= 0.002, 'a ring round a lamp with the bands (%+.4f / %+.4f)' % lamp['bands'])
    bins = ((0.5, 3), (3, 8), (8, 12), (12, 24))
    for sky in (0.6, 1.2):
        lum, dist = twigs(sky=sky)
        im = grey(lum)
        band = (dist > 2) & (dist < 24)
        pp = per_pixel(im[..., :3], FUSION)
        out = {}
        for tag, b in (('pyramid', None), ('bands', BANDS)):
            F, _ = fuse(gl, im, FUSION, 24, b)
            dd = F - pp
            out[tag] = (tuple(float(dd[(dist > lo_) & (dist <= hi_)].mean()) for lo_, hi_ in bins), float(dd[band].mean()),
                        float(np.percentile(np.abs(dd[band]), 95)))
        say('   twigs against a sky of %.1f, beside them at 0.5-3 / 3-8 / 8-12 / 12-24 px: pyramid %s (p95 %.4f), bands %s (p95 %.4f)'
            % (sky, ' '.join('%+.4f' % v for v in out['pyramid'][0]), out['pyramid'][2], ' '.join('%+.4f' % v for v in out['bands'][0]),
               out['bands'][2]))
        # two-sided per distance (review of P391: E's dark fringe of -0.0076 at 0.5 - 1.5 px passed the signed mean of 2 - 24 px)
        gate(min(out['bands'][0]) >= -0.0015 and max(out['bands'][0]) <= 0.0025 and out['bands'][2] <= 0.0075,
             'twigs at sky %.1f with the bands: %s / p95 %.4f (a fringe or a glow beside them)'
             % (sky, ' '.join('%+.4f' % v for v in out['bands'][0]), out['bands'][2]))
    # a 2 px dark line on a 0.6 field (the review's: E drew a -0.013 band over 1 - 6 px beside it)
    lum = np.full((H, W), 0.6)
    for y0 in range(100, 700, 80):
        lum[y0:y0 + 2] = 0.03
    im = grey(lum)
    pp = per_pixel(im[..., :3], FUSION)
    line = {}
    for tag, b in (('pyramid', None), ('bands', BANDS)):
        dd = fuse(gl, im, FUSION, 24, b)[0] - pp
        prof = np.mean([dd[y0 - 30:y0 + 32, 200:824].mean(1) for y0 in range(100, 700, 80)], 0)
        side = np.delete(prof, [29, 30, 31, 32])
        line[tag] = (float(side.min()), float(side.max()), float(prof[30]))
    say('   a 2 px dark line on 0.6, beside it (1 - 30 px) darkest / brightest against the per-pixel fusion: pyramid %+.4f / %+.4f '
        '(its core %+.4f), bands %+.4f / %+.4f (core %+.4f)' % (*line['pyramid'], *line['bands']))
    gate(line['bands'][0] >= -0.002 and line['bands'][1] <= line['pyramid'][1] + 1e-4,
         'a thin dark line with the bands: %+.4f / %+.4f beside it' % line['bands'][:2])
    im, near = rungs()
    gref = cwp.guided(gl, im, BENTO)
    rr = {}
    for tag, kw in (('pyramid', {}), ('shipped P349 share', {'gmix': GMIX}), ('bands', {'bands': BANDS})):
        F, _ = fuse(gl, im, BENTO, 24, **kw)
        dd = (F - gref)[near]
        rr[tag] = (float(dd.mean()), float(np.percentile(np.abs(dd), 95)))
    say('   P349 rungs in a glow above white, the glass-side bands against the guided filter: %s'
        % ', '.join('%s %+.4f / p95 %.4f' % (k, *v) for k, v in rr.items()))
    gate(rr['bands'][1] <= 0.005, 'P349 rungs with the bands: p95 %.4f' % rr['bands'][1])
    gate(rr['bands'][1] <= rr['shipped P349 share'][1] + 1e-4, 'the bands leave more halo round the rungs than P349 (%.4f against %.4f)'
         % (rr['bands'][1], rr['shipped P349 share'][1]))
    steps = {}
    for c in (0.5, 1.0, 1.5, 2.0, 3.0):
        hi_, lo_ = 0.2 * 2 ** (c / 2), 0.2 * 2 ** (-c / 2)
        im = grey(np.where(yy < 384, hi_, lo_))
        pp = per_pixel(im[..., :3], FUSION)
        steps[c] = (halo(fuse(gl, im, FUSION, 24)[0], pp), halo(fuse(gl, im, FUSION, 24, gmix=GMIX, edge=EDGE)[0], pp),
                    halo(fuse(gl, im, FUSION, 24, BANDS)[0], pp))
        say('   a %.1f EV step at I 0.2: its halo against the per-pixel fusion, pyramid %.4f / %d rows, shipped %.4f / %d rows, bands %.4f / %d rows'
            % (c, *steps[c][0], *steps[c][1], *steps[c][2]))
    gate(steps[0.5][2][0] >= 0.95 * steps[0.5][0][0], 'a 0.5 EV step loses its pop with the bands (%.4f of %.4f)' % (steps[0.5][2][0], steps[0.5][0][0]))
    # a 1 EV step reads as a +-0.5 EV texture (its edge measure is the texture's): the pyramid's pop by design; 1.5 EV about the shipped
    gate(steps[1.0][2][0] <= steps[1.0][0][0] + 1e-4, 'a 1 EV step gets more halo than the pyramid\'s with the bands (%.4f)' % steps[1.0][2][0])
    gate(steps[1.5][2][0] <= 1.1 * steps[1.5][1][0], 'a 1.5 EV step keeps a halo of %.4f with the bands (shipped %.4f)' % (steps[1.5][2][0], steps[1.5][1][0]))
    for c in (2.0, 3.0):
        gate(steps[c][2][0] <= 0.005, 'a %.0f EV step keeps a halo of %.4f with the bands' % (c, steps[c][2][0]))
    # steps in deep shadows, both sides, without and with the edges' noise shift of a night model (review of P391: at the band floor
    # 2^-7 and with the shift subtracted, a 0.004 -> 2 EV step glowed 0.0157 on its dark side against the pyramid's 0.0119)
    worst_dark = []
    for I0, ev in ((0.004, 1.5), (0.004, 2.0), (0.004, 3.0), (0.01, 1.5), (0.01, 2.0), (0.03, 1.5), (0.03, 2.0)):
        im = grey(np.where(xx < 512, I0, I0 * 2 ** ev).astype(np.float64))

        def sides(F):
            col = F[64:-64].mean(0)
            pd, pb = col[200:400].mean(), col[640:840].mean()
            return float(np.max(np.abs(col[392:510] - pd))), float(np.max(np.abs(col[514:632] - pb)))
        pyr_, ship_ = sides(fuse(gl, im, FUSION, 24)[0]), sides(fuse(gl, im, FUSION, 24, gmix=GMIX, edge=EDGE)[0])
        e_, en_ = sides(fuse(gl, im, FUSION, 24, BANDS)[0]), sides(fuse(gl, im, FUSION, 24, BANDS, shift=night)[0])
        say('   a %.1f EV step from I %.3f, glow dark / bright side: pyramid %.4f / %.4f, shipped %.4f / %.4f, bands %.4f / %.4f, '
            'with a night model\'s noise shift %.4f / %.4f' % (ev, I0, *pyr_, *ship_, *e_, *en_))
        for k_ in (0, 1):
            ref_ = min(pyr_[k_], ship_[k_]) + 5e-4
            for tag, v in (('bands', e_), ('bands with the night shift', en_)):
                if v[k_] > ref_:
                    worst_dark.append('%s, %.1f EV from %.3f, %s side %.4f (pyramid %.4f, shipped %.4f)'
                                      % (tag, ev, I0, ('dark', 'bright')[k_], v[k_], pyr_[k_], ship_[k_]))
    gate(not worst_dark, 'a deep-shadow step glows more than the pyramid or the shipped shares: ' + '; '.join(worst_dark))
    # 5. P310 LED bars
    rgb = np.full((H, W, 3), 0.03)
    bars = ((100, 2), (300, 6), (500, 16))
    for x0, bw in bars:
        rgb[(xx >= x0) & (xx < x0 + bw) & (yy > 150) & (yy < 600)] = 8.0 * np.array((0.55, 0.12, 1.0))
    im = cwp.rgba(rgb)
    pe, _ = fuse(gl, im, BENTO, 24, BANDS)
    gref = cwp.guided(gl, im, BENTO)
    cores = []
    for x0, bw in bars:
        xc = x0 + bw // 2
        cores.append((bw, float(pe[375, xc]), float(gref[375, xc])))
        gate(abs(pe[375, xc] - gref[375, xc]) <= 0.01 and pe[375, xc] <= 0.85, 'P310: the %d px bar\'s core %.4f with the bands (guided %.4f)'
             % (bw, pe[375, xc], gref[375, xc]))
    say('5. P310 violet bars, core with the bands / guided: ' + ', '.join('%d px %.4f / %.4f' % c for c in cores))
    # 6. texture and clouds
    rng = np.random.default_rng(3)
    fld = np.kron(rng.standard_normal((H // 8 + 1, W // 8 + 1)), np.ones((8, 8)))[:H, :W]
    tex = 0.2 * np.exp2(xx / W + 0.25 * np.clip(fld, -1, 1))
    for name, lum, u in (('low-contrast texture (+-0.25 EV, Bento)', tex, BENTO), ('clouds above white', clouds(), FUSION),
                         ('clouds at mid grey', clouds(base=0.18), FUSION)):
        im = grey(lum)
        pe, _ = fuse(gl, im, u, 24, BANDS)
        p0, _ = fuse(gl, im, u, 24)
        pp = per_pixel(im[..., :3], plain(u))
        r = hp_std(pe) / hp_std(p0)
        say('6. %-40s high-pass contrast: bands x%.3f of the pyramid (pyramid x%.3f of the per-pixel fusion)' % (name, r, hp_std(p0) / hp_std(pp)))
        gate(r >= 0.97, '%s: the bands keep x%.3f of the pyramid\'s local contrast' % (name, r))
    # +-0.5 EV block textures under the HDR exposures (review of P391: the band's own contrast as the edge measure kept x0.34 - 0.57):
    # at least 0.88 of the pyramid's local contrast and at most 0.03 under the shipped shares'
    rng = np.random.default_rng(2)
    for blk in (4, 8):
        t = np.kron(rng.choice([-1.0, 1.0], (H // blk + 1, W // blk + 1)), np.ones((blk, blk)))[:H, :W]
        row = []
        for base in (0.05, 0.18, 0.6):
            im = grey(base * np.exp2(0.5 * t))
            p0 = hp_std(fuse(gl, im, FUSION, 24)[0], blk)
            ps = hp_std(fuse(gl, im, FUSION, 24, gmix=GMIX, edge=EDGE)[0], blk) / p0
            pe = hp_std(fuse(gl, im, FUSION, 24, BANDS)[0], blk) / p0
            row.append('%.2f: bands x%.3f shipped x%.3f' % (base, pe, ps))
            gate(pe >= 0.88 and pe >= ps - 0.03, 'a +-0.5 EV %d px texture at %.2f keeps x%.3f of the pyramid\'s contrast (shipped x%.3f)'
                 % (blk, base, pe, ps))
        say('   +-0.5 EV %d px blocks, the HDR exposures, of the pyramid\'s contrast at base %s' % (blk, ', '.join(row)))
    # strength 0 (no noise cap, whole levels): the pyramid
    im = grey(np.where(yy < 384, 1.5, 0.03) * clouds(base=1.0))
    zero = tuple(BANDS[:3]) + (0.0,) + tuple(BANDS[4:])
    d0 = float(np.max(np.abs(fuse(gl, im, FUSION, 24, zero)[0] - fuse(gl, im, FUSION, 24)[0])))
    say('   strength 0 against the pyramid: max |diff| %.1e' % d0)
    gate(d0 <= 1e-6, 'the bands at strength 0 are not the pyramid (%.1e)' % d0)
    # 7. continuous depth
    im = grey(clouds(base=0.4) * np.where(yy < 300, 4.0, 1.0))
    n = cwp.levels_for(W, H, 24)
    full, _ = fuse(gl, im, FUSION, 24, BANDS, None, 1.0, levels=n)
    zero, _ = fuse(gl, im, FUSION, 24, BANDS, None, 0.0, levels=n)
    less, _ = fuse(gl, im, FUSION, 24, BANDS, None, 1.0, levels=n - 1)
    half, _ = fuse(gl, im, FUSION, 24, BANDS, None, 0.5, levels=n)
    d0 = float(np.max(np.abs(zero - less)))
    mid = float(np.max(np.abs(half - 0.5 * (zero + full))))
    step = float(np.max(np.abs(full - less)))
    say('7. depth: f 0 against %d levels max |diff| %.2e (the whole level\'s own step %.4f), f 0.5 against the mean of f 0 and 1 %.2e'
        % (n - 1, d0, step, mid))
    gate(d0 <= 1e-6, 'the fractional depth at f 0 is not one level less (%.2e)' % d0)
    gate(mid <= 1e-5, 'the lowest band is not weighted linearly by f (%.2e)' % mid)
    a, b = depth(2048, 1536, 48), depth(2000, 1500, 48)
    da, db = a[0] - 1 + a[1], b[0] - 1 + b[1]
    say('   WhyFusion.depth at ef_coarse 48: whyLow 2048 x 1536 %d levels f %.3f, 2000 x 1500 %d levels f %.3f (whole levels: %d and %d)'
        % (a[0], a[1], b[0], b[1], cwp.levels_for(2048, 1536, 48), cwp.levels_for(2000, 1500, 48)))
    gate(abs(da - db) <= 0.05, 'the depth of a 4096 x 3072 and a 4000 x 3000 sensor differs by %.2f levels' % abs(da - db))
    gl.ctx.release()
    for f in fails:
        print('FAIL: ' + f)
    if fails:
        sys.exit(1)
    print('PASS: the edge-aware bands match their numpy reference; no halo at a horizon, a window frame, a lamp, twigs or rungs in a glow '
          '(at least as clean as the shipped P349 + P389 shares), no fringe beside thin dark objects, no deep-shadow step glowing beyond '
          'the pyramid or the shares, mild steps keep their pop, P310 cores, texture (+-0.5 EV too) and clouds kept; the continuous '
          'depth is exact at f 0 and 1')


if __name__ == '__main__':
    main()
