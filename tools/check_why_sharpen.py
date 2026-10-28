#!/usr/bin/env python3
"""Offline check of the WHY luma sharpening of the LMC hybrid (WhyLumaSharpen + shaders why/sharp_*).

Runs the shipped fragment shaders with moderngl (GLSL ES 3.1 through ARB_ES3_1_compatibility; on Linux add
backend='egl' to create_standalone_context), driven by a Python port of WhyLumaSharpen.Run, and compares them with the
numpy reference of WhyCam's process_luma_fp16 (tools/why_sharpen_ref.py = research/hybrid5/ref/sharpen):
  1. PSF tables: Airy r 1 against the emulated kernel (why_sharpen.md 3.2);
  2. scale 1 (1x grid): the whole X8U chain, GPU vs reference, on synthetic texture + edges, per stage and in total;
  3. scale 2 (Sabre 2x grid): the response at f / 2 of a 2x grid equals the 1x response at f (MTF table of
     why_sharpen.md 0.3), i.e. the 50 MP image downscaled looks like the 12 MP one;
  4. P214e R1 (a mosaic shot, why/sharp_r1.glsl): B(Ya) = the 3x3 binomial as numpy computes it, S + (Ya - B) with S = B
     gives Ya back, the whole R1 chain = Ya + process_luma(B) - B, and the Quad / Tetra site lattice (diagonal 4 px,
     2 px) is lifted far less than by the plain sharpening while the image detail below ~0.1 cycles / px keeps its gain;
  5. P337 lattice-free source (a plain Bayer shot, sharp_r1 mode 3): Ya minus its 2 px alternations (checker, rows, columns
     over the 5 x 5 sites, weights (1 2 2 2 1) / 8) = numpy; a constant / linear ramp passes untouched; the chain lifts the
     2 px rows / columns / checker at most 5 % (the plain chain: x3.9-4.7), detail up to 0.2 cycles / px keeps its gain (8 %).
Usage: python tools/check_why_sharpen.py
"""
import sys, math
from pathlib import Path
import numpy as np
import moderngl

ROOT = Path(__file__).resolve().parents[1]
SH = ROOT / 'app/src/main/assets/shaders'
sys.path.insert(0, str(Path(__file__).resolve().parent))
import why_sharpen_ref as R

try:
    ctx = moderngl.create_standalone_context(backend='egl', require=430)
except Exception:
    ctx = moderngl.create_standalone_context(require=430)
VS = "#version 310 es\nin vec2 vPos;\nvoid main(){ gl_Position = vec4(vPos, 0.0, 1.0); }\n"
quad = ctx.buffer(np.array([-1, -1, 1, -1, -1, 1, 1, 1], 'f4').tobytes())
_progs = {}


def prog(name):
    if name not in _progs:
        src = (SH / (name + '.glsl')).read_text(encoding='utf-8')
        p = ctx.program(vertex_shader=VS, fragment_shader='#version 310 es\n' + src)
        _progs[name] = (p, ctx.vertex_array(p, [(quad, '2f', 'vPos')]))
    return _progs[name]


def tex(a, comps=1, dtype='f2'):
    a = np.asarray(a, np.float32)
    if a.ndim == 2:
        a = a[..., None]
    h, w, c = a.shape
    t = ctx.texture((w, h), c, np.ascontiguousarray(a.astype(np.float16 if dtype == 'f2' else np.float32)).tobytes(), dtype=dtype)
    t.filter = (moderngl.NEAREST, moderngl.NEAREST)
    return t


def empty(w, h, c, dtype='f2'):
    t = ctx.texture((w, h), c, dtype=dtype)
    t.filter = (moderngl.NEAREST, moderngl.NEAREST)
    return t


def run(name, target, textures=None, **uniforms):
    p, vao = prog(name)
    unit = 0
    for k, t in (textures or {}).items():
        if k in p:
            p[k].value = unit
            t.use(location=unit)
            unit += 1
    for k, v in uniforms.items():
        if k in p:
            p[k].value = v
    fbo = ctx.framebuffer(color_attachments=[target])
    fbo.use()
    ctx.viewport = (0, 0, target.width, target.height)
    vao.render(moderngl.TRIANGLE_STRIP)
    ctx.finish()
    fbo.release()
    return target


def read(t):
    dt = np.float16 if t.dtype == 'f2' else np.float32
    return np.frombuffer(t.read(), dt).reshape(t.height, t.width, t.components).astype(np.float64)


# ------------------------------------------------------------------ Python port of WhyLumaSharpen (PSF + passes)
def psf(kernel, rad):
    """WhyLumaSharpen.psf: (half, quadrant[81]) of an Airy (2), pillbox (1) or Gaussian (0) PSF of radius rad."""
    if kernel == 2:
        k = R.airy_psf(rad)
    elif kernel == 1:
        k = R.disk_psf(rad)
    else:
        g = R.gauss1d(rad); k = np.outer(g, g)
    half = k.shape[0] // 2
    if half > 8:                                    # the shader holds at most 17 x 17: truncate and renormalise
        k = k[half - 8:half + 9, half - 8:half + 9]; k = k / k.sum(); half = 8
    q = np.zeros(81, np.float32)
    for dy in range(half + 1):
        for dx in range(half + 1):
            q[dy * (half + 1) + dx] = k[half + dy, half + dx]
    return half, q


# WhyLumaSharpen.SCALE2_*: on the 2x grid the scaled discrete kernels sharpen up to 19 % more than the 1x ones at the
# same angular frequency (the 1x Gaussian sigma 0.5 and 3x3 bilateral are coarse); these amounts bring the response
# back within 5 % of the 1x one up to 0.4 cycles / px (1x).
SCALE2_BIL, SCALE2_RL_GAUSS = 0.75, 0.85


def comp(s, k):
    return 1.0 + (k - 1.0) * min(max(s - 1.0, 0.0), 1.0)


def gpu_sharpen(ya, P=R.X8U, scale=1.0):
    """WhyLumaSharpen.Run on a Ya array; returns the output and the per-stage outputs."""
    H, W = ya.shape
    s = scale
    d = max(1, int(math.floor(s + 1e-6)))
    nb = max(1, int(round(s)))
    out = {}
    L = tex(ya)
    gf_on = P['gf_radius'] >= 1 and abs(P['gf_lc']) > 1e-3
    ab = empty(1, 1, 2)
    if gf_on:
        r = max(1, int(round(P['gf_radius'] * s / d)))
        rw, rh = (W + d - 1) // d, (H + d - 1) // d
        h0 = run('why/sharp_box', empty(rw, rh, 2, 'f4'), {'InputBuffer': L}, modeU=0, radiusU=r, downU=d)
        a0 = run('why/sharp_box', empty(rw, rh, 2), {'InputBuffer': h0}, modeU=1, radiusU=r, epsU=max(P['gf_eps'], 1e-6))
        h1 = run('why/sharp_box', empty(rw, rh, 2), {'InputBuffer': a0}, modeU=2, radiusU=r)
        ab = run('why/sharp_box', empty(rw, rh, 2), {'InputBuffer': h1}, modeU=3, radiusU=r)
    usm_on = P['usm_radius'] > 0 and abs(P['usm_amount']) > 1e-3
    bil_on = P['bil_radius'] > 0 and abs(P['bil_amount']) > 1e-3
    L1 = run('why/sharp_compose', empty(W, H, 1), {'InputBuffer': L, 'GfAB': ab},
             downU=d, gfLcU=P['gf_lc'] if gf_on else 0.0,
             usmSigmaU=P['usm_radius'] * s if usm_on else 0.0, usmAmountU=P['usm_amount'], usmThreshU=P['usm_thresh'] / 255.0,
             bilAmountU=P['bil_amount'] * comp(s, SCALE2_BIL) if bil_on else 0.0, bilRadiusU=int(math.ceil(math.ceil(P['bil_radius']) * s)),
             bilSigmaSU=max(0.5, P['bil_radius'] * 0.5) * s, bilSigmaLU=max(P['bil_color'] / 100.0, 1e-3), nbU=nb,
             haloU=P['halo_control'] / 100.0, protectShadowsU=P['protect_shadows'] / 100.0,
             protectHighlightsU=P['protect_highlights'] / 100.0, grainU=P['film_grain'] / 255.0)
    out['compose'] = read(L1)[..., 0]
    obs = L1
    for i, (kern, rad, amt, it, damp) in enumerate(P['rl']):
        if not (rad > 0 and abs(amt) > 1e-3 and it >= 1):
            continue
        half, q = psf(kern, rad * s)
        common = dict(psfHalfU=half, psfU=tuple(float(v) for v in q), amountU=amt * (comp(s, SCALE2_RL_GAUSS) if kern == 0 else 1.0), haloU=P['rl_halo_control'] / 100.0,
                      marginU=P['rl_halo_margin'], macroU=P['rl_halo_macro'], protectShadowsU=P['protect_shadows'] / 100.0,
                      protectHighlightsU=P['protect_highlights'] / 100.0, nbU=nb)
        est = obs
        for k in range(it):
            ratio = run('why/sharp_rl', empty(W, H, 1), {'Observed': obs, 'Estimate': est}, modeU=0, **common)
            nxt = run('why/sharp_rl', empty(W, H, 1), {'Observed': obs, 'Estimate': est, 'Ratio': ratio},
                      modeU=2 if k == it - 1 else 1, **common)
            est = nxt
        obs = est
        out['rl%d' % (i + 1)] = read(obs)[..., 0]
    return read(obs)[..., 0], out


# ------------------------------------------------------------------ test images
def synth(h=192, w=256, seed=3):
    rng = np.random.default_rng(seed)
    f = np.fft.fftfreq(h)[:, None] ** 2 + np.fft.fftfreq(w)[None, :] ** 2
    spec = (rng.normal(size=(h, w)) + 1j * rng.normal(size=(h, w))) / np.maximum(np.sqrt(f), 1.0 / w)
    tex1f = np.real(np.fft.ifft2(spec)); tex1f = tex1f / tex1f.std()
    yy, xx = np.mgrid[0:h, 0:w]
    base = 0.05 + 0.5 * (xx / w)                               # dark .. mid ramp
    img = base * (1 + 0.15 * tex1f)
    img[:, w // 2:w // 2 + 20] = 0.7                          # strong edges (lc >= 0.4)
    img[h // 3:h // 3 + 6, :] *= 0.4
    img[(xx - 60) ** 2 + (yy - 140) ** 2 < 15 ** 2] = 0.95     # highlight disc (protect highlights)
    img += rng.normal(0, 0.003, img.shape)                     # grain
    return np.clip(img, 0, 1).astype(np.float32)


def gpu_r1(ya, P=R.X8U, scale=1.0, sharpen=None):
    """P214e: WhyLumaSharpen on a mosaic shot - sharp_r1 mode 0 (B), mode 1 (D = Ya - B), the chain on B, mode 2 (S + D).
    Returns the output, B and D."""
    H, W = ya.shape
    L = tex(ya)
    B = run('why/sharp_r1', empty(W, H, 1), {'InputBuffer': L}, modeU=0)
    D = run('why/sharp_r1', empty(W, H, 1), {'InputBuffer': L, 'Smoothed': B}, modeU=1)
    b = read(B)[..., 0]
    # b and the chain's output are fp16 values: uploading them again is exact
    s_out = gpu_sharpen(b, P, scale)[0] if sharpen is None else sharpen(b)
    out = run('why/sharp_r1', empty(W, H, 1), {'Sharpened': tex(s_out), 'Residual': D}, modeU=2)
    return read(out)[..., 0], b, read(D)[..., 0]


def gpu_lattice(ya, P=R.X8U, scale=1.0, sharpen=None):
    """P337: WhyLumaSharpen on a plain Bayer shot - sharp_r1 mode 3 (Ya without its 2 px lattice), mode 1 (the lattice), the
    chain on the source, mode 2 (S + lattice). Returns the output and the source."""
    H, W = ya.shape
    L = tex(ya)
    B = run('why/sharp_r1', empty(W, H, 1), {'InputBuffer': L}, modeU=3)
    D = run('why/sharp_r1', empty(W, H, 1), {'InputBuffer': L, 'Smoothed': B}, modeU=1)
    b = read(B)[..., 0]
    s_out = gpu_sharpen(b, P, scale)[0] if sharpen is None else sharpen(b)
    out = run('why/sharp_r1', empty(W, H, 1), {'Sharpened': tex(s_out), 'Residual': D}, modeU=2)
    return read(out)[..., 0], b


def lattice_free(a):
    """numpy mode 3: a minus the checker + row + column alternation of the 5 x 5 sites (edge clamped), weights (1 2 2 2 1) / 8."""
    a = a.astype(np.float64)
    h, w = a.shape
    p = np.pad(a, 2, mode='edge')
    wv = [0.125, 0.25, 0.25, 0.25, 0.125]
    sg = lambda i: 1 if i % 2 == 0 else -1
    lat = sum((sg(i) * sg(j) + sg(j) + sg(i)) * wv[i + 2] * wv[j + 2] * p[2 + j:2 + j + h, 2 + i:2 + i + w]
              for j in range(-2, 3) for i in range(-2, 3))
    return a - lat


def binomial3(a):
    """numpy B: [1 2 1]^T [1 2 1] / 16 with the edge pixels repeated (as sharp_r1 clamps), rounded to fp16."""
    p = np.pad(a.astype(np.float64), 1, mode='edge')
    h = p[:, :-2] + 2 * p[:, 1:-1] + p[:, 2:]
    v = h[:-2] + 2 * h[1:-1] + h[2:]
    return R.H16((v / 16).astype(np.float32)).astype(np.float64)


def pattern_gain(fn, fy, fx, amp=0.005, mean=0.18, n=128):
    """Amplitude gain of a 2D cosine (fy, fx cycles / px) at small amplitude on a flat field, central crop."""
    yy, xx = np.mgrid[0:n, 0:n]
    ref = amp * np.cos(2 * np.pi * (fy * yy + fx * xx))
    o = fn((mean + ref).astype(np.float32))
    c = slice(16, n - 16)
    d = o[c, c] - o[c, c].mean()
    r = ref[c, c] - ref[c, c].mean()
    return float(np.sum(d * r) / np.sum(r * r))


def mtf_gain(fn, f, amp=0.005, mean=0.18, n=256, scale=1.0):
    """Amplitude gain of a horizontal cosine of frequency f (cycles / px) at small amplitude, central rows."""
    x = np.arange(n)
    img = np.tile(mean + amp * np.cos(2 * np.pi * f * x), (64, 1)).astype(np.float32)
    o = fn(img)
    row = o[32, 32:n - 32] - o[32, 32:n - 32].mean()
    ref = img[32, 32:n - 32] - img[32, 32:n - 32].mean()
    return float(np.dot(row, ref) / np.dot(ref, ref))


def main():
    ok = True

    def check(cond, msg):
        nonlocal ok
        print(('OK   ' if cond else 'FAIL ') + msg)
        ok &= bool(cond)

    half, q = psf(2, 1.0)
    emu = [0.000598, 0.003352, 0.001576, 0.003352, 0.000598, 0.003352, 0.011861, 0.084271, 0.011861, 0.003352,
           0.001576, 0.084271, 0.579961]
    full = R.airy_psf(1.0).ravel()[:13]
    check(half == 2 and np.max(np.abs(full - emu)) < 2e-6, f'Airy r1 5x5 = emulated table (max diff {np.max(np.abs(full - emu)):.2e})')
    check(abs(q[0] - 0.579961) < 2e-6 and abs(q[1] - 0.084271) < 2e-6 and abs(q[2] - 0.001576) < 2e-6, 'PSF quadrant layout')

    img = synth()
    ref_c = R.luma_compose(R.H16(img), R.sep_conv(R.H16(img), R.gauss1d(0.5)), R.guided_ab(R.H16(img), 8, 0.01),
                           R.bilateral(R.H16(img), 0.5, 0.1),
                           [0, 0, 1.0, 50 / 255, 1.0, 0.25, 0, 0.0, 50 / 255, 0.0, 1.0, 0.0, 1.0, 0.0, 0.15, 0.4])
    ref = R.process_luma(img)
    gpu, st = gpu_sharpen(img)
    m = 4
    dc = np.abs(st['compose'] - ref_c)[m:-m, m:-m]
    da = np.abs(gpu - ref)[m:-m, m:-m]
    print(f'     compose |gpu-ref| max {dc.max():.2e} mean {dc.mean():.2e};  total max {da.max():.2e} mean {da.mean():.2e} p99.9 {np.percentile(da, 99.9):.2e}')
    check(dc.max() < 2e-3, 'luma_compose (USM + bilateral + GF + halo + protect) matches the reference within fp16')
    # RL amplifies one-ulp differences of the fp16 buffers (ulp 4.9e-4 at 0.5): compare each stage on the same input
    r1 = R.rl_pass(R.H16(st['compose']), 2, 1.0, 1.0, 3, 0, 1.0, 0, 1.0, 0.15, 0.4)
    r3 = R.rl_pass(R.H16(st['rl1']), 0, 0.5, 1.0, 3, 0, 1.0, 0, 1.0, 0.15, 0.4)
    d1 = np.abs(st['rl1'] - r1)[m:-m, m:-m]
    d3 = np.abs(st['rl3'] - r3)[m:-m, m:-m]
    print(f'     RL Airy r1 x3 max {d1.max():.2e} mean {d1.mean():.2e};  RL Gauss 0.5 x3 max {d3.max():.2e} mean {d3.mean():.2e}')
    check(d1.max() < 2.5e-3 and d3.max() < 2.5e-3 and d1.mean() < 4e-4 and d3.mean() < 4e-4,
          'each RL stage matches the reference on the same input (<= 5 ulp, mean < 1 ulp)')
    check(np.percentile(da, 99.9) < 5e-3 and da.mean() < 8e-4, 'X8U chain (compose + RL Airy r1 x3 + RL Gauss 0.5 x3): total within fp16 drift')

    print('     MTF (amp 0.005, mean 0.18)   f:  ' + '  '.join(f'{f:5.2f}' for f in (0.05, 0.1, 0.2, 0.3, 0.4, 0.5)))
    g1 = [mtf_gain(lambda a: gpu_sharpen(a)[0], f) for f in (0.05, 0.1, 0.2, 0.3, 0.4, 0.5)]
    print('     scale 1 GPU                     ' + '  '.join(f'{g:5.2f}' for g in g1))
    target = [1.28, 1.41, 2.05, 2.89, 3.69, 3.91]
    check(all(abs(a - b) / b < 0.06 for a, b in zip(g1, target)), 'scale 1 response = why_sharpen.md 0.3 table within 6 %')
    g2 = [mtf_gain(lambda a: gpu_sharpen(a, scale=2.0)[0], f / 2, n=512) for f in (0.05, 0.1, 0.2, 0.3, 0.4, 0.5)]
    print('     scale 2 GPU at f/2              ' + '  '.join(f'{g:5.2f}' for g in g2))
    check(all(abs(a - b) / b < 0.08 for a, b in zip(g2[:5], g1[:5])), 'scale 2 at f/2 follows scale 1 at f (within 8 %, f <= 0.4)')

    # ---- 4. P214e R1
    ya16 = R.H16(img).astype(np.float64)
    r1_out, b, dres = gpu_r1(img)
    # the store of the fp16 plane may round to nearest or toward zero (this host's driver truncates): 1 ulp of B
    ulp = lambda v: np.spacing(np.abs(v).astype(np.float16)).astype(np.float64)
    db = np.abs(b - binomial3(ya16))
    check(np.all(db <= ulp(b)), f'R1 source B(Ya) = numpy 3x3 binomial within one fp16 ulp (max diff {db.max():.2e})')
    dd = np.abs(dres - (ya16 - b))
    check(np.all(dd <= ulp(dres)), f'R1 residual D = Ya - B of the stored B (max diff {dd.max():.2e}, {int(np.count_nonzero(dd))} px rounded)')
    ident = gpu_r1(img, sharpen=lambda a: a)[0]
    di = np.abs(ident - ya16)
    check(np.count_nonzero(di) <= 0.01 * di.size and np.all(di <= ulp(ya16)),
          f'R1 with S = identity gives Ya back ({int(np.count_nonzero(di))} of {di.size} px off, by at most one ulp)')
    ref_r1 = np.maximum(ya16 + (R.process_luma(b.astype(np.float32)).astype(np.float64) - b), 0.0)
    dr = np.abs(r1_out - ref_r1)[m:-m, m:-m]
    print(f'     R1 chain |gpu-ref| max {dr.max():.2e} mean {dr.mean():.2e} p99.9 {np.percentile(dr, 99.9):.2e}')
    check(np.percentile(dr, 99.9) < 5e-3 and dr.mean() < 8e-4, 'R1 chain (Ya + process_luma(B) - B) matches the reference within fp16 drift')
    check(np.abs(r1_out - gpu)[m:-m, m:-m].mean() > 1e-3, 'R1 changes the result of a mosaic shot (the plain chain is the Bayer one)')

    plain = lambda a: gpu_sharpen(a)[0]
    r1 = lambda a: gpu_r1(a)[0]
    lat = [('diag 4 px (0.25, +0.25)', 0.25, 0.25), ('diag 4 px (0.25, -0.25)', 0.25, -0.25), ('2 px rows (0.5, 0)', 0.5, 0.0),
           ('2 px cols (0, 0.5)', 0.0, 0.5), ('4 px cols (0, 0.25)', 0.0, 0.25)]
    print('     lattice gain (amp 0.005 on 0.18)     plain    R1')
    gp, gr = [], []
    for name, fy, fx in lat:
        gp.append(pattern_gain(plain, fy, fx)); gr.append(pattern_gain(r1, fy, fx))
        print(f'       {name:30s} {gp[-1]:6.2f} {gr[-1]:6.2f}')
    check(all(r - 1 <= 0.5 * (p - 1) + 0.02 for p, r in zip(gp[:2], gr[:2])), 'R1: the diagonal lattice is lifted at most half as much as by the plain sharpening')
    check(all(abs(r - 1) <= 0.05 for r in gr[2:4]), 'R1: the 2 px lattice passes at its own level (within 5 %)')
    fs = (0.05, 0.1, 0.2, 0.3)
    m1 = [mtf_gain(r1, f) for f in fs]
    print('     MTF R1 (scale 1)              f:  ' + '  '.join(f'{f:5.2f}' for f in fs))
    print('     plain                             ' + '  '.join(f'{g:5.2f}' for g in g1[:4]))
    print('     R1                                ' + '  '.join(f'{g:5.2f}' for g in m1))
    check(all(abs(a - b) / b < 0.06 for a, b in zip(m1[:2], g1[:2])), 'R1: detail up to 0.1 cycles / px keeps the plain gain (within 6 %)')
    check(all(1.0 < a < b for a, b in zip(m1[2:], g1[2:4])), 'R1: finer detail is still sharpened, less than by the plain chain')

    # ---- 4b. the guided filter's local contrast on the dark side of a bright edge (owner's window burst: a frame at 1 - 3 % of the
    # pane lost 78 - 98 % of itself over 5 - 12 px, a dark violet outline): the frame keeps >= 1 - gf_lc - 0.1 of itself 4 .. 14 px
    # from the edge, in the compose and in the whole chain on the lattice-free source; the pane's overshoot as before
    xx = np.arange(128)[None, :].repeat(64, 0).astype(np.float32)
    frame = 0.010 + 0.39 / (1 + np.exp(-(xx - 63.5) / 0.9))
    frame = (frame * (1 + 0.03 * np.random.default_rng(7).normal(size=frame.shape))).astype(np.float32)
    for name, out in (('compose', gpu_sharpen(frame)[1]['compose']), ('X8U chain', gpu_lattice(frame)[0])):
        r = (out / frame)[8:-8]
        keep = float(np.median(r[:, 64 - 14:64 - 3], 0).min())
        over = float(np.median(r[:, 64:70], 0).max())
        print(f'     {name}: dark frame keeps {keep:.3f} of itself 4 .. 14 px from a bright pane; pane overshoot {over:.3f}')
        check(keep >= 1 - R.X8U['gf_lc'] - 0.1, f'{name}: a dark frame beside a bright pane keeps >= {1 - R.X8U["gf_lc"] - 0.1:.2f} of itself (no trench)')
        check(over < 1.10, f'{name}: the pane overshoot stays below 10 %')

    # ---- 5. P337 lattice-free source of a plain Bayer shot
    lf_out, lf_src = gpu_lattice(img)
    dl = np.abs(lf_src - lattice_free(ya16))[2:-2, 2:-2]
    check(dl.max() < 2e-3, f'lattice-free source = numpy (max diff {dl.max():.2e}, fp16)')
    yy, xx = np.mgrid[0:64, 0:96]
    ramp = (0.1 + 0.002 * xx + 0.001 * yy).astype(np.float32)
    dramp = np.abs(gpu_lattice(ramp, sharpen=lambda a: a)[1] - R.H16(ramp))[2:-2, 2:-2]
    check(dramp.max() <= 2.5e-4, f'a linear ramp passes the lattice removal untouched (max diff {dramp.max():.2e}: one fp16 ulp at 0.3)')
    lfree = lambda a: gpu_lattice(a)[0]
    print('     lattice gain (amp 0.005 on 0.18)     plain    lattice-free')
    g_plain, g_lf = [], []
    for name, fy, fx in (('2 px rows (0.5, 0)', 0.5, 0.0), ('2 px cols (0, 0.5)', 0.0, 0.5), ('2 px checker (0.5, 0.5)', 0.5, 0.5)):
        g_plain.append(pattern_gain(plain, fy, fx)); g_lf.append(pattern_gain(lfree, fy, fx))
        print(f'       {name:30s} {g_plain[-1]:6.2f} {g_lf[-1]:6.2f}')
    check(all(abs(g - 1) <= 0.05 for g in g_lf), 'lattice-free: the 2 px rows, columns and checker pass at their own level (within 5 %)')
    m3 = [mtf_gain(lfree, f) for f in fs]
    print('     lattice-free                      ' + '  '.join(f'{g:5.2f}' for g in m3))
    check(all(abs(a - b) / b < 0.08 for a, b in zip(m3[:3], g1[:3])), 'lattice-free: detail up to 0.2 cycles / px keeps the plain gain (within 8 %)')
    check(1.0 < m3[3] <= g1[3] + 0.02, 'lattice-free: 0.3 cycles / px is still sharpened')
    print('ALL OK' if ok else 'FAILED')
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main())
