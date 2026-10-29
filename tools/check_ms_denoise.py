#!/usr/bin/env python3
"""P402 «Пирамидальный шумодав» (WhyMsDenoise, why/msdn.glsl; the reference app v2.07's ms denoise): the five passes on the host GPU
(moderngl) as WhyMsDenoise wires them (level 1 from the camera RGB, levels 2 and 3, the base, levels 2 and 1 rebuilt, the
full-size result), RGBA16F levels, the thresholds of WhyMsDenoise.thresholds (the reference app's driver: 0.012 x luma strength,
0.02 x chroma strength, the level shares; the base the coarsest colour share). A synthetic merge in camera RGB (white point 0.45 /
1 / 0.62, a camera -> sRGB matrix with off-diagonal terms): a flat grey wall with luminance and colour noise, a sharp black / white
edge, a saturated red patch, a colour outside sRGB (negative after the matrix). Checks:
- thresholds 0: the merge unchanged (within 3e-3 relative: the RGBA16F levels), the colour outside sRGB as well;
- the defaults (luma 1, chroma 2): the wall's luminance noise and colour noise go down (luma sigma at most 0.8 x, chroma at most
  0.5 x), its mean kept (1 %);
- the edge's step kept (95 % of its contrast 3 px either side); a red and a teal patch keep their hue (1 deg) and lightness (1 %),
  their chroma 90 / 60 % or more at the defaults (the teal sits near the wall's lightness: the luminance-guided colour mean takes
  some of it, as the reference app's), 95 / 90 % at luma 0.2 / chroma 0.5;
- WhyMsDenoise.modelFactor / thresholds as mirrored here.
Usage: check_ms_denoise.py
"""
from pathlib import Path
import re
import sys

import numpy as np
import moderngl

ROOT = Path(__file__).resolve().parents[1]
SRC = (ROOT / 'app/src/main/assets/shaders/why/msdn.glsl').read_text(encoding='utf-8')
JAVA = (ROOT / 'app/src/main/java/com/particlesdevs/photoncamera/processing/opengl/postpipeline/WhyMsDenoise.java').read_text(encoding='utf-8')
FAIL = []
try:
    ctx = moderngl.create_standalone_context(backend='egl', require=430)
except Exception:
    ctx = moderngl.create_standalone_context(require=430)
VS = '#version 310 es\nvoid main(){vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));gl_Position=vec4(p*2.-1.,0,1);}'
PROGS = {}


def check(cond, msg):
    print(('PASS ' if cond else 'FAIL ') + msg)
    if not cond:
        FAIL.append(msg)


def prog(mode):
    if mode not in PROGS:
        PROGS[mode] = ctx.program(vertex_shader=VS, fragment_shader='#version 310 es\n' + SRC.replace('#define MODE 0', '#define MODE %d' % mode, 1))
    return PROGS[mode]


def tex(a, dtype='f4'):
    t = ctx.texture((a.shape[1], a.shape[0]), 4, np.ascontiguousarray(a, np.float32 if dtype == 'f4' else np.float16).tobytes(), dtype=dtype)
    t.filter = (moderngl.LINEAR, moderngl.LINEAR)
    t.repeat_x = t.repeat_y = False
    return t


def draw(mode, size, units, uniforms, dtype='f2'):
    w, h = size
    out = ctx.texture((w, h), 4, dtype=dtype)
    out.filter = (moderngl.LINEAR, moderngl.LINEAR)
    out.repeat_x = out.repeat_y = False
    fb = ctx.framebuffer([out])
    fb.use()
    ctx.viewport = (0, 0, w, h)
    p = prog(mode)
    for i, (k, t) in enumerate(units.items()):
        if k in p:
            t.use(i)
            p[k].value = i
    for k, v in uniforms.items():
        if k in p:
            p[k].value = v
    ctx.vertex_array(p, []).render(vertices=3)
    fb.release()
    return out


def half(s):
    return ((s[0] + 1) // 2, (s[1] + 1) // 2)


def ms_denoise(cam, to_render, wp, th, shadow):
    """WhyMsDenoise.Run: th[l] = (luma, chroma) of level l, th[3][1] the base's chroma."""
    h, w = cam.shape[:2]
    src = tex(np.dstack([cam, np.ones((h, w))]))
    mat = tuple(np.asarray(to_render, np.float64).T.flatten())   # GLProg.setVar(float[9]): row-major, transposed upload
    inv = tuple(np.linalg.inv(np.asarray(to_render, np.float64)).T.flatten())
    lab, size = [None], (w, h)
    for l in (1, 2, 3):
        size = half(size)
        lab.append(draw(0 if l == 1 else 1, size, {'InputBuffer': src if l == 1 else lab[l - 1]},
                        {'toRenderU': mat, 'neutralU': tuple(wp)}))
    rebuilt = draw(2, lab[3].size, {'InputBuffer': lab[3]}, {'chromaThU': th[3][1], 'shadowU': shadow})
    for l in (2, 1):
        rebuilt = draw(3, lab[l].size, {'InputBuffer': lab[l], 'Coarse': lab[l + 1], 'CoarseRecon': rebuilt},
                       {'lumaThU': th[l][0], 'chromaThU': th[l][1], 'shadowU': shadow, 'levelU': l})
    out = draw(4, (w, h), {'InputBuffer': src, 'Coarse': lab[1], 'CoarseRecon': rebuilt},
               {'toRenderU': mat, 'fromRenderU': inv, 'neutralU': tuple(wp), 'lumaThU': th[0][0], 'chromaThU': th[0][1],
                'shadowU': shadow, 'levelU': 0}, dtype='f4')
    return np.frombuffer(out.read(), np.float32).reshape(h, w, 4)[..., :3].copy()


def oklab(c):
    m1 = np.array([[0.4122214708, 0.5363325363, 0.0514459929], [0.2119034982, 0.6806995451, 0.1073969566],
                   [0.0883024619, 0.2817188376, 0.6299787005]])
    m2 = np.array([[0.2104542553, 0.7936177850, -0.0040720468], [1.9779984951, -2.4285922050, 0.4505937099],
                   [0.0259040371, 0.7827717662, -0.8086757660]])
    return np.cbrt(np.maximum(c, 1e-9) @ m1.T) @ m2.T


def model_factor(s, o, scale):
    if not scale > 0.001 or not (s > 0 or o > 0):
        return 1.0
    sigma = np.sqrt(max(max(s, 0) * 0.18 + max(o, 0), 1e-10))
    nf = min(3.5, max(0.4, 20.0 / max(0.18 / sigma, 1.0)))
    return min(4.0, max(0.25, 1.0 + (nf - 1.0) * scale))


def thresholds(luma=1.0, chroma=2.0, model=1.0, levels_l=(1, 1, 1), levels_c=(1, 1, 1)):
    t = [[0.012 * luma * model * levels_l[l], 0.02 * chroma * model * levels_c[l]] for l in range(3)]
    return t + [[0.0, t[2][1]]]


def main():
    check('0.012f * Math.max(0f, WhyTone.value("ms_luma", 1f)) * model' in JAVA and '0.02f * Math.max(0f, WhyTone.value("ms_chroma", 2f)) * model' in JAVA
          and 't[3][1] = t[2][1];' in JAVA and '20.0 / Math.max(0.18 / sigma, 1.0)' in JAVA and 'Math.min(4.0, 1.0 + (nf - 1.0) * scale)' in JAVA,
          'WhyMsDenoise.thresholds / modelFactor as mirrored here')
    check(abs(model_factor(1e-5, 1e-7, 1.0) - 0.4) < 1e-9 and model_factor(0, 0, 1) == 1.0 and model_factor(1e-3, 1e-5, 0.0) == 1.0
          and abs(model_factor(1e-2, 1e-4, 1.5) - 4.0) < 1e-9, 'the noise model factor: 0.4 on a clean frame, 1 without a model or scale, up to 4')
    rng = np.random.default_rng(7)
    H, W = 192, 256
    wp = np.array([0.45, 1.0, 0.62])
    to_render = np.array([[1.62, -0.48, -0.14], [-0.21, 1.42, -0.21], [0.03, -0.52, 1.49]])
    inv = np.linalg.inv(to_render)
    scene = np.full((H, W, 3), 0.18)                                   # render-linear sRGB
    scene[:, 160:] = 0.02
    scene[:, 200:] = 0.6                                               # a sharp edge at x 200 (dark | bright)
    scene[20:70, 20:80] = (0.30, 0.03, 0.02)                           # a saturated red
    scene[120:150, 20:60] = (0.02, 0.25, 0.40)                         # a colour that sits outside sRGB after the matrix round trip
    noisy = scene * (1 + 0.06 * rng.standard_normal((H, W, 1))) + 0.006 * rng.standard_normal((H, W, 3))
    cam = (noisy @ inv.T) / wp
    out0 = ms_denoise(cam, to_render, wp, thresholds(0.0, 0.0), 1.5)
    rel = np.abs(out0 - cam).max() / np.abs(cam).max()
    check(rel <= 3e-3, 'thresholds 0: the merge unchanged (%.1e of its range)' % rel)
    out = ms_denoise(cam, to_render, wp, thresholds(), 1.5)
    ren = lambda c: (c * wp) @ to_render.T
    luma = lambda c: ren(c) @ np.array([0.2126, 0.7152, 0.0722])
    wall = (slice(80, 180), slice(90, 150))
    nb, na = luma(cam)[wall].std(), luma(out)[wall].std()
    cb = (ren(cam)[wall] - luma(cam)[wall][..., None]).std(axis=(0, 1)).mean()
    ca = (ren(out)[wall] - luma(out)[wall][..., None]).std(axis=(0, 1)).mean()
    print('wall: luma sigma %.4f -> %.4f, colour sigma %.4f -> %.4f' % (nb, na, cb, ca))
    check(na <= 0.8 * nb, 'the wall\'s luminance noise goes down')
    check(ca <= 0.5 * cb, 'the wall\'s colour noise goes down')
    check(abs(luma(out)[wall].mean() / luma(cam)[wall].mean() - 1) <= 0.01, 'the wall\'s mean kept (%.4f)' % (luma(out)[wall].mean() / luma(cam)[wall].mean()))
    ys = luma(out)[:, 194:197].mean(), luma(out)[:, 203:206].mean()
    yb = luma(cam)[:, 194:197].mean(), luma(cam)[:, 203:206].mean()
    check((ys[1] - ys[0]) >= 0.95 * (yb[1] - yb[0]), 'the edge keeps its step (%.3f of %.3f)' % (ys[1] - ys[0], yb[1] - yb[0]))
    patches = {'the red patch': (slice(25, 65), slice(25, 75)), 'the teal patch': (slice(122, 148), slice(22, 58))}
    for label, (th, keep) in {'defaults': (thresholds(), {'the red patch': 0.9, 'the teal patch': 0.6}),
                              'luma 0.2 / chroma 0.5': (thresholds(0.2, 0.5, levels_l=(0.4, 0.1, 0.35), levels_c=(0.2, 0.4, 0.6)),
                                                        {'the red patch': 0.95, 'the teal patch': 0.9})}.items():
        o = ms_denoise(cam, to_render, wp, th, 1.5)
        for name, r in patches.items():
            a, b = oklab(ren(cam)[r].mean((0, 1))), oklab(ren(o)[r].mean((0, 1)))
            ca_, cb_ = np.hypot(a[1], a[2]), np.hypot(b[1], b[2])
            dh = abs((np.degrees(np.arctan2(b[2], b[1]) - np.arctan2(a[2], a[1])) + 180) % 360 - 180)
            print('%s, %s: OKLab C %.4f -> %.4f, hue %.2f deg, L %.4f -> %.4f' % (label, name, ca_, cb_, dh, a[0], b[0]))
            # the reference app's colour filter pulls a colour towards a luminance-guided mean: a patch near the wall's lightness
            # (the teal) gives up some of its chroma at the default strength 2
            check(cb_ >= keep[name] * ca_ and dh <= 1.0 and abs(b[0] / a[0] - 1) <= 0.01, '%s, %s: hue and lightness kept, chroma %.0f %% or more'
                  % (label, name, 100 * keep[name]))
    print('ms denoise check %s' % ('FAIL' if FAIL else 'PASS'))
    return 1 if FAIL else 0


if __name__ == '__main__':
    sys.exit(main())
