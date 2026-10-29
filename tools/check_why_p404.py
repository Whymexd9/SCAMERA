#!/usr/bin/env python3
"""P404 (owner, 2026-10-29: the pyramid denoise's motion boost, the reference vibrance / skin / matrix gains, the curve modifiers, the
gamma standard and its contrast boost, the Sobel edge boost, edge thinning and RL damping). Checks:
- off (the defaults): why/lpyr (every MODE, SKY, EDGE_BANDS, WINDOW), why/gf (every MODE) and why/msdn (every MODE) preprocess to the
  token streams before P404 (their SHA-256; GAMMA_TYPE 0, MOTION 0); why/combine's own pin is check_colour_output.py --shader;
- on: why/combine with GAMMA_TYPE 1 / 2, ARK_VIB 1 and their mix with the tone operators, P3, the colour look and the sun glow,
  why/lpyr and why/gf with GAMMA_TYPE 1 / 2, why/msdn with MOTION 1, why/sharp_compose and why/sharp_rl compile (glslc, when found);
- the gamma standards (combine's encG / decG, mirrored): sRGB and Rec.709 each other's inverse, continuous at the knee, 0 -> 0, 1 -> 1;
- the motion boost (msdn MOTION, mirrored): full at 1 frame, none at 6 and more, monotone;
- why/sharp_compose's Sobel edge boost and edge thinning, why/sharp_rl's TV damping on the GPU (moderngl) against numpy ports of the
  reference app's luma_compose_usm and rl_mult; with them at 0 the compose leaves the image as it is.
Usage: check_why_p404.py
"""
from pathlib import Path
import hashlib
import itertools
import os
import re
import subprocess
import sys
import tempfile

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools'))
import check_colour_output as cco  # noqa: E402

SH = ROOT / 'app/src/main/assets/shaders'
FAIL = []
# the token streams before P404 (all their variants in order, one SHA-256)
PINS = {'why/lpyr': 'e9258c980020c1d189d80fefa57b76952b9392cdd8226f6de674b5b16d00e025', 'why/gf': '1b9a072422033b29c57d8d9933b01d85195114e46bcdbcf7678c868a6dc50909', 'why/msdn': '9b270ea4ee5ab053485e45c2a1a09bdd193fe68519119d671bd6dfee4a435650'}
VARIANTS = {'why/lpyr': {'MODE': range(11), 'SKY': (0, 1), 'EDGE_BANDS': (0, 1), 'WINDOW': (0, 1)},
            'why/gf': {'MODE': (0, 1, 2, 3)}, 'why/msdn': {'MODE': range(5)}}


def check(cond, msg):
    print(('PASS ' if cond else 'FAIL ') + msg)
    if not cond:
        FAIL.append(msg)


def cpp_tokens(src):
    """The C preprocessor's output (GLSL's subset: #define, #if / #elif / #else / #endif) as one token stream."""
    src = '\n'.join(l for l in src.split('\n') if not l.strip().startswith(('#version', '#extension')))
    r = subprocess.run(['cpp', '-P', '-undef', '-nostdinc', '-w'], input=src, capture_output=True, text=True)
    if r.returncode != 0:
        raise SystemExit(r.stderr)
    return ' '.join(cco.tokens(r.stdout))


def stream(name, src):
    h = hashlib.sha256()
    v = VARIANTS[name]
    for combo in itertools.product(*v.values()):
        s = src
        for k, val in zip(v.keys(), combo):
            s = cco.set_define(s, k, str(val))
        h.update(cpp_tokens(cco.expand_imports(s)).encode())
    return h.hexdigest()


def compile_variants():
    glslc = cco.find_glslc()
    if not glslc:
        print('  glslc not found: compile skipped')
        return
    jobs = [('why/combine', d) for d in ({'GAMMA_TYPE': '1'}, {'GAMMA_TYPE': '2', 'P3_OUT': '1'}, {'TONE_OP': '1', 'GAMMA_TYPE': '1'},
                                         {'ARK_VIB': '1'}, {'ARK_VIB': '1', 'COLOUR_LOOK': '1', 'TONE_OP': '1', 'GAMMA_TYPE': '2',
                                                            'SUN_BLOOM': '1', 'P3_OUT': '1'})]
    jobs += [('why/lpyr', {'GAMMA_TYPE': '1'}), ('why/lpyr', {'GAMMA_TYPE': '2', 'MODE': '3'}), ('why/gf', {'GAMMA_TYPE': '1', 'MODE': '3'}),
             ('why/gf', {'GAMMA_TYPE': '2'}), ('why/sharp_compose', {}), ('why/sharp_rl', {}), ('why/msdn', {'MODE': '3', 'MOTION': '1'}),
             ('why/msdn', {'MODE': '4', 'MOTION': '1'})]
    for name, defs in jobs:
        src = (SH / (name + '.glsl')).read_text(encoding='utf-8')
        for k, v in defs.items():
            src = cco.set_define(src, k, v)
        src = cco.expand_imports(src)
        if not src.lstrip().startswith('#version'):
            src = '#version 320 es\n' + src
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / 'a.frag'
            f.write_text(src, encoding='utf-8')
            r = subprocess.run([glslc, '--target-env=opengl', '-fshader-stage=fragment', '-fauto-map-locations', '-fauto-bind-uniforms',
                                '-o', os.devnull, str(f)], capture_output=True, text=True)
        check(r.returncode == 0, 'glslc %s %s%s' % (name, defs, '' if r.returncode == 0 else ':\n' + r.stderr[:1500]))


def enc(v, t):
    if t == 1:
        return np.where(v >= 0.0031308, 1.055 * np.power(np.maximum(v, 0.0031308), 1 / 2.4) - 0.055, 12.92 * v)
    return np.where(v >= 0.018, 1.099 * np.power(np.maximum(v, 0.018), 0.45) - 0.099, 4.5 * v)


def dec(v, t):
    if t == 1:
        return np.where(v >= 0.04045, np.power((np.maximum(v, 0.04045) + 0.055) / 1.055, 2.4), v / 12.92)
    return np.where(v >= 0.081, np.power((np.maximum(v, 0.081) + 0.099) / 1.099, 1 / 0.45), v / 4.5)


def pad_get(a, dy, dx):
    h, w = a.shape
    yy = np.clip(np.arange(h)[:, None] + dy, 0, h - 1)
    xx = np.clip(np.arange(w)[None, :] + dx, 0, w - 1)
    return a[yy, xx]


def compose_ref(y, amount, thresh, thin):
    """luma_compose_usm's Sobel / thinning terms on inner pixels, then sharp_compose's edge damping (halo control 0)."""
    n = {(dy, dx): pad_get(y, dy, dx) for dy in (-1, 0, 1) for dx in (-1, 0, 1)}
    c = y
    s8 = sum(v for k, v in n.items() if k != (0, 0))
    lmin = np.minimum.reduce(list(n.values()))
    lmax = np.maximum.reduce(list(n.values()))
    tot = np.zeros_like(y)
    if abs(amount) > 0.001:
        gx = -n[-1, -1] - 2 * n[0, -1] - n[1, -1] + n[-1, 1] + 2 * n[0, 1] + n[1, 1]
        gy = -n[-1, -1] - 2 * n[-1, 0] - n[-1, 1] + n[1, -1] + 2 * n[1, 0] + n[1, 1]
        tot += np.where(np.sqrt(gx * gx + gy * gy) > thresh, (c - s8 / 8) * amount, 0)
    if abs(thin) > 0.001:
        tot += np.where(lmax - lmin > 0.02, (np.where(s8 - 8 * c < 0, lmax, lmin) - c) * thin, 0)
    inner = np.zeros_like(y, bool)
    inner[1:-1, 1:-1] = True
    tot = np.where(inner, tot, 0)
    lc = lmax - lmin

    def sm(a, b, x):
        t = np.clip((x - a) / (b - a), 0, 1)
        return t * t * (3 - 2 * t)
    ef = sm(0.15, 0.40, lc)
    damp = 1 + (0.30 - 1) * ef
    damp = np.where(lc > 0.40, damp + (0.15 - damp) * sm(0.40, 0.70, lc), damp)
    tot = tot * damp
    return np.where(np.abs(tot) > 0.0001, np.maximum(0, c + tot), c)


def tv_ref(est, upd, damp):
    """rl_mult's TV damping: the update / max(0.1, 1 - damping x 0.002 x div)."""
    c0, r, l, d, u = est, pad_get(est, 0, 1), pad_get(est, 0, -1), pad_get(est, 1, 0), pad_get(est, -1, 0)
    dl, ul, ur = pad_get(est, 1, -1), pad_get(est, -1, -1), pad_get(est, -1, 1)
    ux = (r - c0) / np.sqrt((r - c0) ** 2 + 0.25 * (d - u) ** 2 + 1e-6)
    uy = (d - c0) / np.sqrt(0.25 * (r - l) ** 2 + (d - c0) ** 2 + 1e-6)
    uxl = (c0 - l) / np.sqrt((c0 - l) ** 2 + 0.25 * (dl - ul) ** 2 + 1e-6)
    uyu = (c0 - u) / np.sqrt(0.25 * (ur - ul) ** 2 + (c0 - u) ** 2 + 1e-6)
    return upd / np.maximum(0.1, 1 - damp * 0.002 * ((ux - uxl) + (uy - uyu)))


def gpu_part():
    try:
        import check_why_sharpen as G
    except Exception as e:  # no GL context
        print('  GL context unavailable (%s): GPU part skipped' % str(e)[:200])
        return
    y = G.synth(96, 128).astype(np.float16).astype(np.float64)
    H, W = y.shape
    base = dict(downU=1, gfLcU=0.0, usmSigmaU=0.0, usmAmountU=0.0, usmThreshU=0.0, bilAmountU=0.0, bilRadiusU=1, bilSigmaSU=0.5,
                bilSigmaLU=0.1, nbU=1, haloU=0.0, protectShadowsU=0.0, protectHighlightsU=0.0, grainU=0.0)
    for amount, thresh, thin in ((0.0, 0.0, 0.0), (1.5, 0.05, 0.0), (2.0, 0.0, 0.0), (0.0, 0.0, 0.6), (0.0, 0.0, -0.5), (1.0, 0.1, 0.4)):
        out = G.run('why/sharp_compose', G.empty(W, H, 1), {'InputBuffer': G.tex(y), 'GfAB': G.empty(1, 1, 2)},
                    sobelU=(amount, thresh), thinU=thin, **base)
        got = G.read(out)[..., 0]
        ref = compose_ref(y, amount, thresh, thin)
        err = np.abs(got - ref) / np.maximum(1.0, np.abs(ref))
        check(err.max() < 2e-3, 'compose: Sobel %.1f / %.2f, thinning %.1f as the reference (max %.5f, changed %.0f %%)'
              % (amount, thresh, thin, err.max(), 100 * np.mean(np.abs(ref - y) > 1e-4)))
        if amount == 0 and thin == 0:
            check(np.array_equal(got, y), 'compose: all off - the image as it is')
    half, q = G.psf(0, 0.8)
    common = dict(psfHalfU=half, psfU=tuple(float(v) for v in q), amountU=1.0, haloU=0.0, marginU=0.0, macroU=0.0, protectShadowsU=0.0,
                  protectHighlightsU=0.0, nbU=1)
    obs = G.tex(y)
    ratio = G.run('why/sharp_rl', G.empty(W, H, 1), {'Observed': obs, 'Estimate': obs}, modeU=0, dampU=0.0, **common)
    plain = G.read(G.run('why/sharp_rl', G.empty(W, H, 1), {'Observed': obs, 'Estimate': obs, 'Ratio': ratio}, modeU=1, dampU=0.0,
                         **common))[..., 0]
    for damp in (0.5, 1.0):
        got = G.read(G.run('why/sharp_rl', G.empty(W, H, 1), {'Observed': obs, 'Estimate': obs, 'Ratio': ratio}, modeU=1, dampU=damp,
                           **common))[..., 0]
        ref = tv_ref(y, plain, damp)
        err = np.abs(got - ref) / np.maximum(1.0, np.abs(ref))
        check(err.max() < 2e-3 and np.abs(got - plain).max() > 1e-5, 'RL damping %.1f as rl_mult (max %.5f, moved %.5f)'
              % (damp, err.max(), np.abs(got - plain).max()))


def main():
    for name, pin in PINS.items():
        got = stream(name, (SH / (name + '.glsl')).read_text(encoding='utf-8'))
        check(got == pin, '%s: the token streams before P404 by default (%s)' % (name, got[:12]))
    combine = (SH / 'why/combine.glsl').read_text(encoding='utf-8')
    for k in ('GAMMA_TYPE', 'ARK_VIB'):
        check(('#define %s 0\n' % k) in combine, 'combine: %s 0 by default' % k)
    for t, knee in ((1, 0.0031308), (2, 0.018)):
        x = np.linspace(0, 1, 20001)
        check(np.abs(dec(enc(x, t), t) - x).max() < 1e-4, 'gamma standard %d: decG inverts encG' % t)
        check(abs(enc(np.array([0.0]), t)[0]) < 1e-12 and abs(enc(np.array([1.0]), t)[0] - 1) < 1e-3, 'gamma standard %d: 0 -> 0, 1 -> 1' % t)
        lo, hi = enc(np.array([knee - 1e-7]), t)[0], enc(np.array([knee + 1e-7]), t)[0]
        check(abs(hi - lo) < 1e-3, 'gamma standard %d: continuous at the knee (%.5f)' % (t, hi - lo))
    n = np.linspace(0, 10, 1001)
    tt = np.clip(1 - (np.maximum(n, 1) - 1) / 5, 0, 1)
    mo = tt * tt * (3 - 2 * tt)
    check(mo[n <= 1].min() == 1 and mo[n >= 6].max() == 0 and np.all(np.diff(mo) <= 0), 'motion boost: 1 frame full, 6 none, monotone')
    msdn = (SH / 'why/msdn.glsl').read_text(encoding='utf-8')
    check('float t = clamp(1.0 - (max(float(code) * effScaleU, 1.0) - 1.0) / 5.0, 0.0, 1.0);' in msdn
          and 'float mo = t * t * (3.0 - 2.0 * t);' in msdn, 'msdn: the motion boost as mirrored')
    compile_variants()
    gpu_part()
    print('why P404 check %s' % ('FAIL' if FAIL else 'PASS'))
    return 1 if FAIL else 0


if __name__ == '__main__':
    sys.exit(main())
