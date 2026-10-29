#!/usr/bin/env python3
"""P391: the WHY tone's noise bounds on the host GPU - (3) the deep-shadow floor of the bright layer lifted only as far as the frame's
noise allows (import why_fusion deepNoiseU; the RAW viewfinder's FusedCurve.DEEP_NOISE) and (4) the pyramid's excess over the
per-pixel band on the fine levels passed only above the noise (why/lpyr noiseBandU, EDGE_BANDS 1).

The shaders are built as GLInterface.readProgram builds them (check_why_pyramid.Gl); a probe program imports the shipped
import why_fusion and returns fusedAt(I) and prepare's dot(p, L) of neutral pixels; the pyramid is check_why_pyramid_bands.fuse
(WhyFusion.pyramid's passes); FusedCurve.java itself is compiled with javac. Checks:
1. the capped floor: GL = an independent numpy formula within 1e-4 (fusedAt and prepare, two exposure sets, a night and a daylight
   noise model); the curve rises with the light, never under the curve without the floor, never over the floor's; where it caps,
   the fused luma's shadow noise gammaInv F / SNR(I) <= the cap; tau 0 bit-identical to the floor before P391 (WT_OLD_SHADERS:
   against those shaders); FusedCurve (the viewfinder) = the shader within 1e-4 with the cap and bit-identical without it;
2. the band cap (WhyTone's defaults, with the edges' noise shift the photo runs with a noise model): noisy flats under a night
   model at I 0.05 - 0.2 (x AE) keep at most 1.1 x the per-pixel fusion's fine noise (the pyramid: up to 1.75 x), deep shadows
   (I <= 0.02) within 1.03 x of the pyramid's (no grain from the bands' random «edges»), a texture of 8 noise sigmas >= 0.93 x
   the contrast of the bands without the cap; with lo = hi or no level the bands as without the cap, bit for bit.
3. the calibration (--calibrate) recovers a known c within 10 % on synthetic guides with white and spatially correlated noise
   (the review of P391: the earlier two estimators, the 20th percentile of 8 x 8 blocks' MAD and the smallest eigenvalue of weak-
   texture patches, read 27 % and 38 % under a known c on white noise, more on correlated noise; and the worker's k was left out).
--calibrate <02-WhyLow.pfm> <S> <O> <N_eff> [k]: the noise of a real whyLow dump (a diagnostics ZIP's nearest-sampled PFM; the
camera's one-frame slope / offset from its README, the merge's effective frames and the worker's model correction k - «HYBRID
NOISE CHECK ... model xk», 1 when it kept the model - from its native report) against the fusion's model c k (S I + O) /
(4 N_eff^0.8) per light bin: the 10th percentile of 8 x 8 blocks' plane residual variance over that quantile's factor on simulated
noise of the same residual correlation; prints c per bin (a textured bin reads high: the flat, dark bins count) and the median.
Usage: check_why_noise.py [--report] | --calibrate ...
"""
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_why_pyramid as cwp  # noqa: E402
import check_why_pyramid_bands as cwb  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
REPORT = '--report' in sys.argv
JAVA = ROOT / 'app/src/main/java/com/particlesdevs/photoncamera/processing/opengl/postpipeline/FusedCurve.java'
PROBE = '''precision highp float;
precision highp sampler2D;
uniform sampler2D InputBuffer;
#define GAMMA_TYPE 0
#import why_fusion
out vec4 Output;
void main() {
    vec3 rgb = texelFetch(InputBuffer, ivec2(gl_FragCoord.xy), 0).rgb;
    float I;
    vec4 p, L;
    prepare(rgb, I, p, L);
    Output = vec4(fusedAt(I, 0.0), dot(p, L), I, 1.0);
}
'''
# the window burst's exposures with Bento headroom (FusedCurveTest) and the photo's without (check_why_sky FUSION)
SETS = {'window burst (Bento)': {'expU': (0.0626, 1.0, 0.2502, 2.6148), 'layerU': (0.4, 1.0, 0.1, 1.0), 'centerU': 0.75},
        'no Bento': {'expU': (0.125, 1.0, 0.354, 3.03), 'layerU': (0.8, 1.0, 0.1, 1.0), 'centerU': 0.6}}
BASE = {'aeU': 1.0, 'smoothU': 0.25, 'gammaInvU': 1 / 2.2, 'toeU': 0.05, 'monoU': 0.0, 'satLightU': (0.0, 0.0, 0.0, 0.0),
        'satLevelU': (0.0, 0.0), 'warmU': (0.0, 0.0), 'deepFloorU': 2.0}
# noise of the guide I (x AE): variance S I + O - a night shot (SNR 7 at I 0.01) and daylight (SNR 30)
MODELS = {'night': (2e-4, 1e-7), 'day': (1e-5, 1e-9)}
WHY = ROOT / 'app/src/main/java/com/particlesdevs/photoncamera/processing/opengl/postpipeline/WhyTone.java'


def tau_default():
    return float(re.search(r'DEEP_NOISE = ([\d.]+)f;', WHY.read_text(encoding='utf-8')).group(1))


TAU = tau_default()


def fused_np(I, u, floor, tau=0.0, S=0.0, O=0.0):
    """import why_fusion fusedAt(I, 0) with deepFloorU {floor} and deepNoiseU (tau, S, O): float64."""
    em, lw = np.array(u['expU']), np.array(u['layerU'])
    c, s, gi, toe = u['centerU'], u['smoothU'], u['gammaInvU'], u['toeU']
    L = np.maximum(cwp.acescg(I[..., None] * em, toe), 1e-6) ** gi
    w = np.exp(-0.5 * (L - c) ** 2 / max(s * s, 0.001)) * lw + 1e-6
    if floor > 0:
        wf = np.full(I.shape, np.exp(-0.5 * floor * floor) * lw[3] + 1e-6)
        if tau > 0:
            snr = I / np.sqrt(np.maximum(S * I + O, 1e-30))
            fcap = tau * snr / gi
            s0, n0 = w[..., :3].sum(-1), (w[..., :3] * L[..., :3]).sum(-1)
            capped = np.minimum(wf, np.maximum((fcap * s0 - n0) / np.where(L[..., 3] > fcap, L[..., 3] - fcap, 1.0), 0.0))
            wf = np.where(L[..., 3] > fcap, capped, wf)
        w[..., 3] = np.where(L[..., 3] < c, np.maximum(w[..., 3], wf), w[..., 3])
    return (w * L).sum(-1) / w.sum(-1)


class Probe:
    def __init__(self, gl, sh=None):
        self.gl = gl
        old = cwp.SH
        if sh:
            cwp.SH = Path(sh)
        try:
            src = cwp.read_program(PROBE, [])
        finally:
            cwp.SH = old
        self.prog = gl.ctx.program(vertex_shader=cwp.VS, fragment_shader=src)

    def run(self, I, uni):
        """fusedAt(I) and prepare's dot(p, L) of neutral pixels of guide I (at ae 1; a row, or an image of guides), or of the pixels
        of an RGB image (H x W x 3: fusedAt of their guide x aeU)."""
        if I.ndim == 3:
            img = I[..., :3].astype(np.float32)
            I = img[..., 0]
        else:
            I2 = I.reshape(1, -1) if I.ndim == 1 else I
            img = np.repeat(I2.astype(np.float32)[..., None], 3, -1)
        t = self.gl.tex(np.concatenate([img, np.ones(img.shape[:2] + (1,), np.float32)], -1), 'f4')
        out = self.gl.target(img.shape[1], img.shape[0], 4, 'f4')
        self.gl.draw(self.prog, out, [('InputBuffer', t)], uni)
        r = self.gl.read(out)
        return r[..., 0].astype(np.float64).reshape(I.shape), r[..., 1].astype(np.float64).reshape(I.shape)


DRIVER = r'''
package com.particlesdevs.photoncamera.processing.opengl.postpipeline;
/** args: floor tau S O center smooth gammaInv toe em0..3 lw0..3 I...: FusedCurve.fusedAt of each I, one a line (%.9e). */
public class NoiseDrv {
    public static void main(String[] a) {
        FusedCurve.DEEP_FLOOR[0] = Float.parseFloat(a[0]);
        FusedCurve.DEEP_NOISE[0] = Float.parseFloat(a[1]);
        FusedCurve.DEEP_NOISE[1] = Float.parseFloat(a[2]);
        FusedCurve.DEEP_NOISE[2] = Float.parseFloat(a[3]);
        float c = Float.parseFloat(a[4]), s = Float.parseFloat(a[5]), gi = Float.parseFloat(a[6]), t = Float.parseFloat(a[7]);
        float[] em = new float[4], lw = new float[4];
        for (int k = 0; k < 4; k++) { em[k] = Float.parseFloat(a[8 + k]); lw[k] = Float.parseFloat(a[12 + k]); }
        StringBuilder out = new StringBuilder();
        for (int i = 16; i < a.length; i++)
            out.append(String.format(java.util.Locale.ROOT, "%.9e%n", FusedCurve.fusedAt(Double.parseDouble(a[i]), em, lw, c, s, gi, t)));
        System.out.print(out);
    }
}
'''


class Java:
    def __init__(self):
        self.dir = tempfile.mkdtemp(prefix='check_why_noise_')
        pkg = Path(self.dir) / 'src/com/particlesdevs/photoncamera/processing/opengl/postpipeline'
        pkg.mkdir(parents=True)
        (pkg / 'NoiseDrv.java').write_text(DRIVER)
        shutil.copy(JAVA, pkg / 'FusedCurve.java')
        javac = shutil.which('javac')
        if not javac:
            raise SystemExit('javac not found (the viewfinder\'s curve is FusedCurve.java itself)')
        subprocess.run([javac, '-encoding', 'UTF-8', '-d', str(Path(self.dir) / 'classes'), str(pkg / 'NoiseDrv.java'), str(pkg / 'FusedCurve.java')],
                       check=True, capture_output=True)

    def fused(self, I, u, floor, tau, S, O):
        args = [repr(float(floor)), repr(float(tau)), repr(float(S)), repr(float(O)), repr(u['centerU']), repr(u['smoothU']),
                repr(u['gammaInvU']), repr(u['toeU'])] + [repr(float(x)) for x in u['expU']] + [repr(float(x)) for x in u['layerU']]
        r = subprocess.run(['java', '-cp', str(Path(self.dir) / 'classes'), 'com.particlesdevs.photoncamera.processing.opengl.postpipeline.NoiseDrv']
                           + args + ['%.9e' % float(x) for x in np.float32(I)], check=True, capture_output=True, text=True)
        return np.array([float(x) for x in r.stdout.split()])

    def close(self):
        shutil.rmtree(self.dir, ignore_errors=True)


def floor_part(gl, fails, say):
    probe = Probe(gl)
    old = os.environ.get('WT_OLD_SHADERS')
    probe_old = Probe(gl, old) if old else None
    java = Java()
    I = np.exp2(np.linspace(-14, 3, 1021)).astype(np.float32).astype(np.float64)
    try:
        worst, worst_j = 0.0, 0.0
        for sname, s in SETS.items():
            u = dict(BASE, **s)
            nofloor = fused_np(I, u, 0.0)
            floor = fused_np(I, u, 2.0)
            for mname, (S, O) in MODELS.items():
                for tau in (TAU, 0.004, 0.016):
                    want = fused_np(I, u, 2.0, tau, S, O)
                    f_gl, pl_gl = probe.run(I, dict(u, deepNoiseU=(tau, S, O, 0.0)))
                    d = max(float(np.max(np.abs(f_gl - want))), float(np.max(np.abs(pl_gl - want))))
                    worst = max(worst, d)
                    dj = float(np.max(np.abs(java.fused(I, u, 2.0, tau, S, O) - f_gl)))
                    worst_j = max(worst_j, dj)
                    rises = float(np.min(np.diff(want)))
                    below = float(np.min(want - nofloor))
                    above = float(np.max(want - floor))
                    capped = want > nofloor + 1e-6
                    noise = (u['gammaInvU'] * want / (I / np.sqrt(S * I + O)))[capped & (want < floor - 1e-6)]
                    excess = float(np.max(noise - tau)) if noise.size else 0.0
                    if REPORT:
                        say('   %-22s %-5s tau %.3f: |GL - numpy| %.1e, |FusedCurve - GL| %.1e, rises (min step %+.1e), under the floor\'s '
                            'curve by <= %.1e, over the no-floor curve by >= %+.1e, capped noise over tau <= %+.1e'
                            % (sname, mname, tau, d, dj, rises, above, below, excess))
                    if rises < -1e-7 or below < -1e-7 or above > 1e-7 or excess > 1e-6:
                        fails.append('the capped floor (%s, %s, tau %.3f): min step %+.1e, under the no-floor curve %+.1e, over the floor %+.1e, '
                                     'noise over tau %+.1e' % (sname, mname, tau, rises, below, above, excess))
            # tau 0: the floor as before, the viewfinder's curve as before (bit for bit)
            f0, p0 = probe.run(I, dict(u, deepNoiseU=(0.0, 2e-4, 1e-7, 0.0)))
            fu, pu = probe.run(I, dict(u, deepNoiseU=(0.0, 0.0, 0.0, 0.0)))
            d0 = max(float(np.max(np.abs(f0 - fu))), float(np.max(np.abs(p0 - pu))))
            if probe_old is not None:
                fo, po = probe_old.run(I, u)
                d0 = max(d0, float(np.max(np.abs(f0 - fo))), float(np.max(np.abs(p0 - po))))
            j0 = float(np.max(np.abs(java.fused(I, u, 2.0, 0.0, 2e-4, 1e-7) - java.fused(I, u, 2.0, 0.0, 0.0, 0.0))))
            say('   %-22s tau 0: max |GL - the floor before P391| %.1e%s, FusedCurve %.1e' % (sname, d0, ' (against WT_OLD_SHADERS)' if probe_old else '', j0))
            if d0 != 0.0 or j0 != 0.0:
                fails.append('tau 0 is not the floor before P391 (%s: GL %.1e, FusedCurve %.1e)' % (sname, d0, j0))
            # the effect: the deep shadows' fused luma without the floor, with it, with the cap (night / day)
            if REPORT:
                for x in (0.002, 0.005, 0.01, 0.02, 0.05):
                    xi = np.array([x])
                    say('      I %.3f: no floor %.4f, floor %.4f, capped night %.4f, day %.4f' % (
                        x, fused_np(xi, u, 0.0)[0], fused_np(xi, u, 2.0)[0], fused_np(xi, u, 2.0, TAU, *MODELS['night'])[0],
                        fused_np(xi, u, 2.0, TAU, *MODELS['day'])[0]))
        say('1. the noise-bound floor: max |GL - numpy| %.1e, |FusedCurve - GL| %.1e' % (worst, worst_j))
        if worst > 1e-4:
            fails.append('the capped floor differs from the numpy formula by %.1e' % worst)
        if worst_j > 1e-4:
            fails.append('FusedCurve (the viewfinder) differs from the shader by %.1e' % worst_j)
    finally:
        java.close()


def box(a, r):
    return cwb.box(a, r)


def hp(F, r=4):
    return float((F - box(F, r))[16:-16, 16:-16].std())


def band_part(gl, fails, say):
    k1, k2, levels = cwb.NOISE
    h, w = 384, 512
    rng = np.random.default_rng(3)
    z = rng.standard_normal((h, w))
    tex = np.kron(rng.standard_normal((h // 4 + 1, w // 4 + 1)), np.ones((4, 4)))[:h, :w]
    u = dict(cwb.FUSION, deepFloorU=2.0)
    probe = Probe(gl)
    S, O = MODELS['night']
    shift = (cwb.EDGE_SIGMAS, S, O)

    def runs(img):
        im = cwb.grey(img)
        pyr, n = cwb.fuse(gl, im, u, 24)
        e0, _ = cwb.fuse(gl, im, u, 24, cwb.BANDS)
        # the bands with a noise model, as the photo runs them: the edges' noise shift (WhyTone.bandsNoise), then the cap too
        e, _ = cwb.fuse(gl, im, u, 24, cwb.BANDS, shift=shift)
        c, _ = cwb.fuse(gl, im, u, 24, cwb.BANDS, (k1, k2, levels, S, O), shift=shift)
        _, pp = probe.run(img, u)
        return pyr, e0, e, c, pp
    say('2. the band cap (%.0f .. %.0f sigma on %d levels; edges beyond %.0f sigma), a night model: fine noise (std of F - box 4) of '
        'noisy flats' % (k1, k2, levels, cwb.EDGE_SIGMAS))
    for I in (0.005, 0.02, 0.05, 0.1, 0.2):
        s = float(np.sqrt(S * I + O))
        r = [hp(x) for x in runs(np.maximum(I + s * z, 0))]
        say('   I %.3f (SNR %4.1f): pyramid %.4f, bands without a noise model %.4f, with it %.4f, + the cap %.4f, per-pixel %.4f' % (I, I / s, *r))
        if I >= 0.05 and r[3] > 1.1 * r[4]:
            fails.append('the band cap: noise at I %.2f %.4f, over 1.1 x the per-pixel fusion\'s %.4f' % (I, r[3], r[4]))
        if I <= 0.02 and r[3] > 1.03 * r[0]:
            fails.append('the band cap: deep-shadow noise at I %.3f %.4f, over 1.03 x the pyramid\'s %.4f' % (I, r[3], r[0]))
    for I in (0.05, 0.1):
        s = float(np.sqrt(S * I + O))
        for amp in (2, 4, 8):
            pyr, e0, e, c, pp = runs(np.maximum(I + amp * s * tex, 0))
            ratio = hp(c) / hp(e)
            say('   texture of %d sigma at I %.2f: the cap keeps x%.3f of the bands\' contrast (of the pyramid\'s: bands x%.3f, + the cap '
                'x%.3f, per-pixel x%.3f)' % (amp, I, ratio, hp(e) / hp(pyr), hp(c) / hp(pyr), hp(pp) / hp(pyr)))
            if amp == 8 and ratio < 0.93:
                fails.append('the band cap flattens an 8 sigma texture at I %.2f to x%.3f of the bands without it' % (I, ratio))
    # off: lo = hi or no level - the bands as without the cap, bit for bit
    img = cwb.grey(np.maximum(0.05 + 0.01 * z, 0))
    e, _ = cwb.fuse(gl, img, u, 24, cwb.BANDS, shift=shift)
    for noise in ((k1, k1, levels, S, O), (k1, k2, 0, S, O)):
        c, _ = cwb.fuse(gl, img, u, 24, cwb.BANDS, noise, shift=shift)
        d = float(np.max(np.abs(c - e)))
        say('   the cap off (%s): max |diff| from the bands without it %.1e' % ('lo = hi' if noise[0] == noise[1] else 'no level', d))
        if d != 0.0:
            fails.append('the band cap off is not the bands without it (%.1e)' % d)


# ---- calibration on a real dump
def read_pfm(p):
    with open(p, 'rb') as f:
        kind = f.readline().strip()
        w, h = map(int, f.readline().split())
        scale = float(f.readline())
        a = np.frombuffer(f.read(), '<f4' if scale < 0 else '>f4')
    return a.reshape(h, w, 3 if kind == b'PF' else 1)[::-1].astype(np.float64)


# 8 x 8 blocks, each less its least-squares plane: the residual's variance is the block's noise variance (texture adds to it); the
# low quantile of a light bin's blocks keeps the flat ones, and the quantile's own bias (it sits under the noise variance, the more
# so the fewer independent samples a block holds: correlated noise) is measured on simulated noise of the same residual lag-1
# correlation (Gaussian-filtered white noise) through the same estimator
CAL_B = 8
CAL_Q = 10


def _cal_blocks(I):
    b = CAL_B
    yy, xx = np.mgrid[0:b, 0:b]
    X = np.stack([np.ones(b * b), yy.ravel() - (b - 1) / 2, xx.ravel() - (b - 1) / 2], 1)
    proj = np.eye(b * b) - X @ np.linalg.pinv(X)
    h, w = I.shape[0] // b * b, I.shape[1] // b * b
    blk = I[:h, :w].reshape(h // b, b, w // b, b).transpose(0, 2, 1, 3).reshape(-1, b * b)
    R = blk @ proj.T
    return blk.mean(-1), R, (R ** 2).sum(-1) / (b * b - 3)


def _cal_rho1(R):
    """The lag-1 correlation of block residuals (horizontal and vertical pooled)."""
    b = CAL_B
    r = R.reshape(-1, b, b)
    if not len(r):
        return 0.0
    v = (r * r).sum() / (r.shape[0] * b * b)
    c = ((r[:, :, 1:] * r[:, :, :-1]).sum() + (r[:, 1:, :] * r[:, :-1, :]).sum()) / (2 * r.shape[0] * b * (b - 1))
    return float(c / max(v, 1e-30))


_CAL_SIM = {}


def _blur(a, k):
    """Separable convolution of {a} with the 1-D kernel {k} (odd length), edges wrapped (numpy only: CI has no scipy)."""
    r = len(k) // 2
    for axis in (0, 1):
        a = sum(w * np.roll(a, o - r, axis) for o, w in enumerate(k))
    return a


def _gauss(a, sf):
    r = max(1, int(np.ceil(4 * sf)))
    k = np.exp(-0.5 * (np.arange(-r, r + 1) / sf) ** 2)
    return _blur(a, k / k.sum())


def _cal_sim(sf, q):
    """(the residual lag-1 correlation of the flat blocks, the q-quantile of s^2 / sigma^2) of unit noise filtered by a Gaussian sf."""
    key = (round(sf, 4), q)
    if key not in _CAL_SIM:
        z = np.random.default_rng(5).standard_normal((80 * CAL_B, 80 * CAL_B))
        if sf > 0:
            z = _gauss(z, sf)
        z = z / z.std()
        _, R, s2 = _cal_blocks(z)
        thr = float(np.percentile(s2, q))
        _CAL_SIM[key] = (_cal_rho1(R[s2 <= thr]), thr)
    return _CAL_SIM[key]


def _cal_qfactor(rho, q):
    """The q-quantile factor of noise whose flat blocks' residual lag-1 correlation is rho (bisection over the Gaussian's sigma)."""
    if rho <= _cal_sim(0.0, q)[0] + 0.005:
        return _cal_sim(0.0, q)[1]
    lo, hi = 0.0, 4.0
    for _ in range(16):
        mid = 0.5 * (lo + hi)
        if _cal_sim(mid, q)[0] < rho:
            lo = mid
        else:
            hi = mid
    return _cal_sim(0.5 * (lo + hi), q)[1]


def calibrate_image(I, S, O, N, k=1.0, q=CAL_Q, lines=None):
    """c of the fusion's model c k (S I + O) / (4 N^0.8) (WhyFusion.guideNoise with the worker's correction k, ae = inScale = 1) on
    the guide image I: per half-EV light bin of 8 x 8 blocks the q-quantile of the plane residual's variance over its quantile
    factor, against the model; returns (the median over the bins of c, [(log2 I, blocks, c)])."""
    m, R, s2 = _cal_blocks(I)
    lg = np.log2(np.maximum(m, 1e-12))
    out = []
    for b0 in np.arange(-12, 3, 0.5):
        sel = (lg >= b0) & (lg < b0 + 0.5)
        if sel.sum() < 60:
            continue
        thr = float(np.percentile(s2[sel], q))
        rho = _cal_rho1(R[sel & (s2 <= thr)])
        model = (S * 2 ** (b0 + 0.25) + O) / (4 * N ** 0.8)
        out.append((b0 + 0.25, int(sel.sum()), thr / _cal_qfactor(rho, q) / model / k, rho))
        if lines is not None:
            lines.append('  log2 I %6.2f: blocks %5d residual lag-1 correlation %+.3f c %8.3f' % (out[-1][0], out[-1][1], out[-1][3], out[-1][2]))
    return (float(np.median([o[2] for o in out])) if out else float('nan')), out


def calibrate(path, S, O, N, k=1.0):
    rgb = read_pfm(path)
    lin = np.maximum(rgb, 0)
    I = np.maximum(lin @ cwb.LUMA, lin.max(-1) * 0.5)
    print('%s: %d x %d, I p1 / p50 / p99 %.4f / %.4f / %.4f; model c k (S I + O) / (4 N^0.8), S %.4g, O %.4g, N_eff %.2f, k %.3f'
          % (path, I.shape[1], I.shape[0], *np.percentile(I, [1, 50, 99]), S, O, N, k))
    lines = []
    c, _ = calibrate_image(I, S, O, N, k, lines=lines)
    print('\n'.join(lines))
    print('c (the app\'s noise_scale, the worker\'s k taken out): median over the bins %.3f (a textured bin reads high: take the flat, '
          'dark bins)' % c)


def calibration_part(fails, say):
    """The calibration on synthetic guides of a known c and k: white and spatially correlated noise (the denoise's), within 10 %."""
    h, w = 768, 1024
    yy, xx = np.mgrid[0:h, 0:w]
    S, O, N, k, c = 3.5e-4, 5.4e-7, 16.4, 0.625, 0.8
    x = np.exp2(-9 + 9 * xx / w) * (1 + 0.1 * np.sin(yy / 40.0))
    sd = np.sqrt(c * k * (S * x + O) / (4 * N ** 0.8))
    z = np.random.default_rng(1).standard_normal((h, w))
    worst = 0.0
    for name, f in (('white', None), ('3 x 3 binomial', lambda a: _blur(a, np.array([0.25, 0.5, 0.25]))),
                    ('Gaussian 1 px', lambda a: _gauss(a, 1.0)), ('Gaussian 1.5 px', lambda a: _gauss(a, 1.5))):
        n = z if f is None else f(z)
        est, _ = calibrate_image(x + sd * n / n.std(), S, O, N, k)
        worst = max(worst, abs(est / c - 1))
        say('   calibration on a synthetic guide (c %.2f, k %.3f), %s noise: c %.3f' % (c, k, name, est))
    say('3. the calibration (--calibrate) recovers a known c within %.1f %%' % (100 * worst))
    if worst > 0.1:
        fails.append('the calibration misses a known c by %.1f %%' % (100 * worst))


def main():
    if '--calibrate' in sys.argv:
        i = sys.argv.index('--calibrate')
        a = sys.argv[i + 1:]
        calibrate(a[0], float(a[1]), float(a[2]), float(a[3]), float(a[4]) if len(a) > 4 else 1.0)
        return
    gl = cwb.Gl()
    fails = []

    def say(msg):
        print(msg, flush=True)
    floor_part(gl, fails, say)
    band_part(gl, fails, say)
    calibration_part(fails, say)
    gl.ctx.release()
    for f in fails:
        print('FAIL: ' + f)
    if fails:
        sys.exit(1)
    print('PASS: the deep floor stops where the shadow noise reaches the cap (GL = numpy = the viewfinder\'s FusedCurve, rising, between '
          'the curves without and with the floor; tau 0 as before); the fine bands\' gain stays at the curve\'s in noise, textures keep it; '
          'the calibration recovers a known c')


if __name__ == '__main__':
    main()
