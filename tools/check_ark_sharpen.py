#!/usr/bin/env python3
"""Offline check of the ARK luma sharpening of the LMC hybrid (ArkLumaSharpen + shaders ark/sharp_*).

Runs the shipped fragment shaders with moderngl (GLSL ES 3.1 through ARB_ES3_1_compatibility; on Linux add
backend='egl' to create_standalone_context), driven by a Python port of ArkLumaSharpen.Run, and compares them with the
numpy reference of ArkCam's process_luma_fp16 (tools/ark_sharpen_ref.py = research/hybrid5/ref/sharpen):
  1. PSF tables: Airy r 1 against the emulated kernel (ark_sharpen.md 3.2);
  2. scale 1 (1x grid): the whole X8U chain, GPU vs reference, on synthetic texture + edges, per stage and in total;
  3. scale 2 (Sabre 2x grid): the response at f / 2 of a 2x grid equals the 1x response at f (MTF table of
     ark_sharpen.md 0.3), i.e. the 50 MP image downscaled looks like the 12 MP one.
Usage: python tools/check_ark_sharpen.py
"""
import sys, math
from pathlib import Path
import numpy as np
import moderngl

ROOT = Path(__file__).resolve().parents[1]
SH = ROOT / 'app/src/main/assets/shaders'
sys.path.insert(0, str(Path(__file__).resolve().parent))
import ark_sharpen_ref as R

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


# ------------------------------------------------------------------ Python port of ArkLumaSharpen (PSF + passes)
def psf(kernel, rad):
    """ArkLumaSharpen.psf: (half, quadrant[81]) of an Airy (2), pillbox (1) or Gaussian (0) PSF of radius rad."""
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


# ArkLumaSharpen.SCALE2_*: on the 2x grid the scaled discrete kernels sharpen up to 19 % more than the 1x ones at the
# same angular frequency (the 1x Gaussian sigma 0.5 and 3x3 bilateral are coarse); these amounts bring the response
# back within 5 % of the 1x one up to 0.4 cycles / px (1x).
SCALE2_BIL, SCALE2_RL_GAUSS = 0.75, 0.85


def comp(s, k):
    return 1.0 + (k - 1.0) * min(max(s - 1.0, 0.0), 1.0)


def gpu_sharpen(ya, P=R.X8U, scale=1.0):
    """ArkLumaSharpen.Run on a Ya array; returns the output and the per-stage outputs."""
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
        h0 = run('ark/sharp_box', empty(rw, rh, 2, 'f4'), {'InputBuffer': L}, modeU=0, radiusU=r, downU=d)
        a0 = run('ark/sharp_box', empty(rw, rh, 2), {'InputBuffer': h0}, modeU=1, radiusU=r, epsU=max(P['gf_eps'], 1e-6))
        h1 = run('ark/sharp_box', empty(rw, rh, 2), {'InputBuffer': a0}, modeU=2, radiusU=r)
        ab = run('ark/sharp_box', empty(rw, rh, 2), {'InputBuffer': h1}, modeU=3, radiusU=r)
    usm_on = P['usm_radius'] > 0 and abs(P['usm_amount']) > 1e-3
    bil_on = P['bil_radius'] > 0 and abs(P['bil_amount']) > 1e-3
    L1 = run('ark/sharp_compose', empty(W, H, 1), {'InputBuffer': L, 'GfAB': ab},
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
            ratio = run('ark/sharp_rl', empty(W, H, 1), {'Observed': obs, 'Estimate': est}, modeU=0, **common)
            nxt = run('ark/sharp_rl', empty(W, H, 1), {'Observed': obs, 'Estimate': est, 'Ratio': ratio},
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
    check(all(abs(a - b) / b < 0.06 for a, b in zip(g1, target)), 'scale 1 response = ark_sharpen.md 0.3 table within 6 %')
    g2 = [mtf_gain(lambda a: gpu_sharpen(a, scale=2.0)[0], f / 2, n=512) for f in (0.05, 0.1, 0.2, 0.3, 0.4, 0.5)]
    print('     scale 2 GPU at f/2              ' + '  '.join(f'{g:5.2f}' for g in g2))
    check(all(abs(a - b) / b < 0.08 for a, b in zip(g2[:5], g1[:5])), 'scale 2 at f/2 follows scale 1 at f (within 8 %, f <= 0.4)')
    print('ALL OK' if ok else 'FAILED')
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main())
