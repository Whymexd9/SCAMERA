#!/usr/bin/env python3
"""Effective-frame map read one-sided by the denoise shaders (plan W3.5), on the SHIPPED shaders.

Until W3.5 the per-pixel merged-frame map reached the GPU cleared (a GL_RED upload into GL_R8UI that the drivers reject),
so every code read "unknown" and the denoise strength was uniform. The real map is uploaded now (GL_RED_INTEGER), and the
owner's rule is that it may only STRENGTHEN the denoise where fewer than the median frames merged (the frame edge,
rejected motion): at or above the median the strength stays exactly that of the cleared map. The former shaders also
weakened the filter there (noise factor down to 0.5, variance 0.25).
Checks:
- scamdn/strmap (ScamDenoise strength map): the variance multiplier is the mean of clamp(sqrt(ref / code), 1, effMax)^2 over
  factor x factor pixels (code 0 = 1), as numpy; a block of codes >= the median or 0 gives exactly 1.0, a block below it
  more than 1;
- chromadn/nlm and chromadn/apply (the NLM engine): a map of codes at or above the median gives the output of the cleared
  map bit for bit; codes below the median on the first columns (the frame edge) filter those columns harder (less
  noise), and pixels away from them stay bit-identical.
- P214d, the lens-shading term of scamdn/strmap: with lscU unset the map is the frame map above bit for bit; with it, the
  frame factor times min(g^2, cap), g = the bilinear luminance gain of an RGBA16F shading map at the block's mean pixel
  over the reference gain, as numpy; exactly the frame factor where g <= 1 (the optical centre), never below it, never above
  it times the cap; noEffU 1 = the lens-shading factor alone. The GainMap sampler sits on a unit of its own (as the app
  binds it): a float and an unsigned sampler on one unit is GL_INVALID_OPERATION.
Usage: check_effmap_onesided.py [--shaders DIR] [--report]
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
W, H = 256, 192
EDGE = 4          # first columns merged from few frames (the frame edge)
REF = 64          # median code of the hybrid map (code scale: the median is 64)


def source(name):
    out = ['#version 430']
    for line in (SH / name).read_text(encoding='utf-8').splitlines():
        m = re.match(r'\s*#import\s+(\S+)', line)
        if m:
            out.append((SH / 'utils' / ('import_' + m.group(1) + '.glsl')).read_text(encoding='utf-8'))
            continue
        out.append(line)
    return '\n'.join(out)


def program(name):
    return ctx.program(vertex_shader=VS, fragment_shader=source(name))


def texture(a, dtype='f4', linear=False):
    a = np.ascontiguousarray(a)
    t = ctx.texture((a.shape[1], a.shape[0]), 1 if a.ndim == 2 else a.shape[2], a.tobytes(), dtype=dtype)
    f = moderngl.LINEAR if linear and dtype[0] == 'f' else moderngl.NEAREST
    t.filter = (f, f)
    t.repeat_x = t.repeat_y = False
    return t


def draw(p, size, textures, uniforms, dtype='f4'):
    out = ctx.texture(size, 4, dtype=dtype)
    out.filter = (moderngl.NEAREST, moderngl.NEAREST)
    out.repeat_x = out.repeat_y = False
    fbo = ctx.framebuffer([out])
    fbo.use()
    ctx.viewport = (0, 0) + size
    for unit, (k, t) in enumerate(textures.items()):
        t.use(unit)
        if k in p:
            p[k].value = unit
    for k, v in uniforms.items():
        if k in p:
            p[k].value = v
    ctx.vertex_array(p, []).render(vertices=3)
    ctx.finish()
    data = np.frombuffer(out.read(), np.float16 if dtype == 'f2' else np.float32).astype(np.float64).reshape(size[1], size[0], 4)
    fbo.release()
    out.release()
    return data


def maps(rng):
    """(cleared, at or above the median everywhere, below the median on the edge columns) code maps."""
    high = rng.integers(REF, 256, (H, W)).astype(np.uint8)
    high[rng.random((H, W)) < 0.05] = 0                       # unknown / clip-flagged codes
    low = high.copy()
    low[:, :EDGE] = rng.integers(8, 40, (H, EDGE))
    return np.zeros((H, W), np.uint8), high, low


def check_strmap(rng, fails):
    p = program('scamdn/strmap.glsl')
    codes = rng.integers(0, 256, (H, W)).astype(np.uint8)
    codes[:, :EDGE] = rng.integers(1, REF, (H, EDGE))
    t = texture(codes, 'u1')
    # P214d: GainMap on a unit of its own, as ScamDenoise binds it (lscU unset: never read)
    unit_gain = texture(np.ones((1, 1, 4), np.float16), 'f2', linear=True)
    worst = 0.0
    for F in (2, 4):
        size = (W // F, H // F)
        got = draw(p, size, {'EffMap': t, 'GainMap': unit_gain}, {'factorU': F, 'effRefU': float(REF), 'effMaxU': 3.0})[..., 0]
        c = codes[:size[1] * F, :size[0] * F].astype(np.float64)
        r = np.where(c > 0, np.clip(np.sqrt(REF / np.maximum(c, 1)), 1.0, 3.0), 1.0)
        want = (r * r).reshape(size[1], F, size[0], F).mean(axis=(1, 3))
        err = np.abs(got - want).max() / want.max()
        worst = max(worst, err)
        block = c.reshape(size[1], F, size[0], F)
        at_or_above = np.all((block == 0) | (block >= REF), axis=(1, 3))
        below = np.any((block > 0) & (block < REF), axis=(1, 3))
        if err > 1e-5:
            fails.append('scamdn/strmap F=%d: not mean(clamp(sqrt(ref/code), 1, max)^2) (rel error %.2e)' % (F, err))
        if not np.all(got[at_or_above] == 1.0):
            fails.append('scamdn/strmap F=%d: blocks at or above the median are not exactly 1 (min %.4f max %.4f)'
                         % (F, got[at_or_above].min(), got[at_or_above].max()))
        if not np.all(got[below] > 1.0):
            fails.append('scamdn/strmap F=%d: blocks below the median are not strengthened' % F)
    if REPORT:
        print('scamdn/strmap: max rel error %.1e vs numpy' % worst)
    t.release()
    unit_gain.release()
    p.release()


LSC_MARGIN = 1.0 + 1.0 / 512.0   # ScamDenoise.LSC_MARGIN


def bilinear(img, u, v):
    """GL_LINEAR + GL_CLAMP_TO_EDGE sampling of img (h, w, c) at normalised (u, v) arrays."""
    h, w = img.shape[:2]
    x = np.clip(u * w - 0.5, 0.0, w - 1.0)
    y = np.clip(v * h - 0.5, 0.0, h - 1.0)
    x0 = np.floor(x).astype(int)
    y0 = np.floor(y).astype(int)
    x1 = np.minimum(x0 + 1, w - 1)
    y1 = np.minimum(y0 + 1, h - 1)
    fx = (x - x0)[..., None]
    fy = (y - y0)[..., None]
    top = img[y0, x0] * (1 - fx) + img[y0, x1] * fx
    bottom = img[y1, x0] * (1 - fx) + img[y1, x1] * fx
    return top * (1 - fy) + bottom * fy


def check_strmap_lsc(rng, fails):
    """P214d: the lens-shading term of scamdn/strmap (ScamDenoise.lscTerm): f x min(g^2, cap) of the shading gain."""
    p = program('scamdn/strmap.glsl')
    codes = rng.integers(0, 256, (H, W)).astype(np.uint8)
    codes[:, :EDGE] = rng.integers(1, REF, (H, EDGE))
    t = texture(codes, 'u1')
    # a phone-like map (17 x 13 sites of r, g_even, g_odd, b): radial luminance shading 1 -> 3.8 at the corners with a
    # colour tilt and a flat centre (so whole blocks read the smallest gain), stored as RGBA16F like ScamRgb's GainMap
    mw, mh = 17, 13
    yy, xx = np.mgrid[0:mh, 0:mw]
    r2 = (((xx - (mw - 1) / 2) / ((mw - 1) / 2)) ** 2 + ((yy - (mh - 1) / 2) / ((mh - 1) / 2)) ** 2) / 2
    lum = 1.0 + 2.8 * np.maximum(r2 - 0.1, 0.0) / 0.9
    sites = np.stack([lum * 1.08, lum, lum * 0.98, lum * 0.94], axis=-1).astype(np.float16)
    gain = texture(sites, 'f2', linear=True)
    unit_gain = texture(np.ones((1, 1, 4), np.float16), 'f2', linear=True)
    s64 = sites.astype(np.float64)
    site_lum = (s64[..., 0] + 0.5 * (s64[..., 1] + s64[..., 2]) + s64[..., 3]) / 3   # ScamDenoise.lscGain
    inv = 1.0 / (site_lum.min() * LSC_MARGIN)
    cap = 4.0
    worst = 0.0
    for F in (2, 4):
        size = (W // F, H // F)
        # every uniform set on every draw: a program keeps the values of the previous one
        base = {'factorU': F, 'effRefU': float(REF), 'effMaxU': 3.0, 'noEffU': 0, 'lscU': (0.0, 0.0),
                'fullInvU': (1.0 / W, 1.0 / H)}
        frames = draw(p, size, {'EffMap': t, 'GainMap': unit_gain}, base)[..., 0]
        off = draw(p, size, {'EffMap': t, 'GainMap': gain}, dict(base, lscU=(0.0, cap)))[..., 0]
        if not np.array_equal(off, frames):
            fails.append('scamdn/strmap F=%d: lscU.x = 0 does not give the frame map bit for bit' % F)
        on = draw(p, size, {'EffMap': t, 'GainMap': gain}, dict(base, lscU=(inv, cap)))[..., 0]
        alone = draw(p, size, {'EffMap': t, 'GainMap': gain}, dict(base, noEffU=1, lscU=(inv, cap)))[..., 0]
        oy, ox = np.mgrid[0:size[1], 0:size[0]] * F
        g4 = bilinear(s64, (ox + 0.5 * (F - 1)) / W, (oy + 0.5 * (F - 1)) / H)
        g = (g4[..., 0] + 0.5 * (g4[..., 1] + g4[..., 2]) + g4[..., 3]) / 3 * inv
        want_lsc = np.where(g > 1.0, np.minimum(g * g, cap), 1.0)
        err = max(np.abs(alone - want_lsc).max() / cap, np.abs(on - frames * want_lsc).max() / (frames * cap).max())
        worst = max(worst, err)
        # GPUs filter with 8 bits of sub-texel weight (a desktop GPU: 1.3e-3 here; llvmpipe filters exactly): a looser bound
        # than the exact frame-map comparison above
        if err > 3e-3:
            fails.append('scamdn/strmap F=%d: lens-shading term not f x min(g^2, cap) (rel error %.2e)' % (F, err))
        centre = g < 0.999
        if not centre.any() or not np.array_equal(on[centre], frames[centre]) or not np.all(alone[centre] == 1.0):
            fails.append('scamdn/strmap F=%d: the optical centre (g <= 1) is not the frame factor exactly' % F)
        if np.any(on < frames) or np.any(on > frames * cap * (1 + 1e-6)) or np.any(alone < 1.0):
            fails.append('scamdn/strmap F=%d: the lens-shading term weakens the map or exceeds the cap' % F)
        if not np.any(alone >= cap * (1 - 1e-6)):
            fails.append('scamdn/strmap F=%d: the corners (g^2 above the cap) do not reach the cap' % F)
    if REPORT:
        print('scamdn/strmap lens shading: max rel error %.1e vs numpy' % worst)
    for x in (t, gain, unit_gain):
        x.release()
    p.release()


def scene(rng):
    """Linear RGB with noise (noisier edge columns), its u = sqrt(Y + c) and the NLM side inputs."""
    rgb = 0.02 + 0.004 * rng.standard_normal((H, W, 3))
    rgb[:, :EDGE] += 0.006 * rng.standard_normal((H, EDGE, 3))
    rgb = np.maximum(rgb, 0).astype(np.float32)
    y = rgb @ np.array([0.2126, 0.7152, 0.0722], np.float32)
    u = np.sqrt(y + 0.008).astype(np.float32)
    return rgb, u


def nlm(p, u_tex, eff_tex, h):
    return draw(p, (W, H), {'InputBuffer': u_tex, 'EffMap': eff_tex},
                {'h': h, 'pxStepU': 1, 'useEff': 1, 'effRef': REF / 8.0, 'effMax': 3.0})[..., 0]


def apply(p, tex, eff_tex, sigma):
    return draw(p, (W, H), dict(tex, EffMap=eff_tex),
                {'sigma': sigma, 'pxStepU': 1, 'lowRatio': 2.0, 'grain': 0.3, 'offsetC': 0.008, 'lumaAmount': 1.0,
                 'chromaAmount': 1.0, 'darkFade': (0.0008, 0.003), 'useEff': 1, 'effRef': REF / 8.0, 'effMax': 3.0})[..., :3]


def check_nlm_apply(rng, fails):
    rgb, u = scene(rng)
    cleared, high, low = (texture(m, 'u1') for m in maps(rng))
    u_tex = texture(u)
    sigma = 0.004 / (2 * np.sqrt(0.02 + 0.008))
    h = 0.6 * max(0.0035, 3 * sigma)
    p = program('chromadn/nlm.glsl')
    n0, nh, nl = (nlm(p, u_tex, m, h) for m in (cleared, high, low))
    p.release()
    far = slice(EDGE + 8, W)
    noise = lambda a: np.diff(a[:, :EDGE], axis=0).std()
    if REPORT:
        print('chromadn/nlm: high codes max |d| %.1e; edge noise cleared %.2e -> low codes %.2e; far max |d| %.1e'
              % (np.abs(nh - n0).max(), noise(n0), noise(nl), np.abs(nl - n0)[:, far].max()))
    if not np.array_equal(nh, n0):
        fails.append('chromadn/nlm: codes at or above the median change the output (max %.2e)' % np.abs(nh - n0).max())
    if not noise(nl) < 0.9 * noise(n0):
        fails.append('chromadn/nlm: codes below the median do not filter harder (%.3e vs %.3e)' % (noise(nl), noise(n0)))
    if not np.array_equal(nl[:, far], n0[:, far]):
        fails.append('chromadn/nlm: pixels away from the low codes changed')

    # chromadn/apply: the luma level of the NLM result above, colour inputs from the 2x2 mean
    half = rgb.reshape(H // 2, 2, W // 2, 2, 3).mean(axis=(1, 3)).astype(np.float32)
    quarter = np.zeros((H // 4, W // 4), np.float32)
    tex = {'InputBuffer': texture(rgb), 'Before': texture(half), 'After': texture(half), 'Noisy': u_tex,
           'Clean': texture(n0.astype(np.float32)), 'Coarse': texture(quarter, linear=True)}
    p = program('chromadn/apply.glsl')
    a0, ah, al = (apply(p, tex, m, sigma) for m in (cleared, high, low))
    p.release()
    if REPORT:
        print('chromadn/apply: high codes max |d| %.1e; low codes edge max |d| %.1e, far max |d| %.1e'
              % (np.abs(ah - a0).max(), np.abs(al - a0)[:, :EDGE].max(), np.abs(al - a0)[:, far].max()))
    if not np.array_equal(ah, a0):
        fails.append('chromadn/apply: codes at or above the median change the output (max %.2e)' % np.abs(ah - a0).max())
    if np.array_equal(al[:, :EDGE], a0[:, :EDGE]):
        fails.append('chromadn/apply: codes below the median have no effect')
    if not np.array_equal(al[:, far], a0[:, far]):
        fails.append('chromadn/apply: pixels away from the low codes changed')
    for t in list(tex.values()) + [cleared, high, low]:
        t.release()


def check_guard(fails):
    """Codes at or above the median must skip the division: a GPU dividing through an approximate reciprocal (Adreno) can
    give ref / ref slightly above 1, which the clamp at 1 would keep (llvmpipe divides exactly, so only the source shows it)."""
    for name, guard in (('scamdn/strmap.glsl', 'float(v) < ref'), ('chromadn/nlm.glsl', 'float(v) * 0.125 < effRef'),
                        ('chromadn/apply.glsl', 'float(v) * 0.125 < effRef')):
        if guard not in (SH / name).read_text(encoding='utf-8'):
            fails.append('%s: no "%s" guard before the division (median pixels may be strengthened on the GPU)' % (name, guard))


def main():
    rng = np.random.default_rng(11)
    fails = []
    check_guard(fails)
    check_strmap(rng, fails)
    check_strmap_lsc(rng, fails)
    check_nlm_apply(rng, fails)
    if fails:
        for f in fails:
            print('FAIL:', f)
        sys.exit(1)
    print('effective-frame map one-sided PASS: strmap / nlm / apply strengthen only below the median, bit-identical at or above;'
          ' strmap lens-shading term one-sided and capped')


if __name__ == '__main__':
    main()
