#!/usr/bin/env python3
"""P399 (owner, 2026-10-29, a ship's bridge, vivo X200 Ultra: «артефакты на краях окна и на приспособлении посреди окна»; the stage
dump 14:22:07: a violet / magenta band 4 - 6 px along the dark ring and the window frame against the sky, in the merge already -
R / G 1.25 - 1.33, B / G 1.16 - 1.43 - which the shadow lift and the colour grid of the tone made wider): scamhdr/scamrgb purpleU on
the GPU (moderngl), purpleU 1 against unset. Synthetic scenes in white-balanced units (white point 0.39 / 1 / 0.63 as the shot,
base white 1, ultrashort white 8). Checks:
- the lens fringe (violet .. magenta, 1 - 4 px into a dark bar against a bright neutral sky) goes neutral: its OKLab chroma at most
  a quarter of before, its luminance kept (1e-3);
- the dark bar beyond 7 px from the sky and the sky itself bit-identical;
- bit-identical: a violet flower on grey (no light near), a violet LED with its violet glow (the light is violet itself), a blue
  sky round a blown sun, a red and an orange surface against the sky, a neutral dark bar against the sky;
- a violet surface against the sky: only the band within 6 px of the sky changes (the compromise), its inside bit-identical.
Usage: check_purple_fringe.py
"""
from pathlib import Path
import sys

import numpy as np
import moderngl

SH = Path(__file__).resolve().parents[1] / 'app/src/main/assets/shaders'
try:
    ctx = moderngl.create_standalone_context(backend='egl', require=430)
except Exception:
    ctx = moderngl.create_standalone_context(require=430)
VS = '#version 430\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.0-1.0,0,1);}'
PROG = ctx.program(vertex_shader=VS, fragment_shader='#version 430\n' + (SH / 'scamhdr/scamrgb.glsl').read_text(encoding='utf-8'))
WP = np.array([0.39, 1.0, 0.63], np.float32)
LUMA = np.array([0.2126, 0.7152, 0.0722])
FAIL = []


def check(cond, msg):
    print(('PASS ' if cond else 'FAIL ') + msg)
    if not cond:
        FAIL.append(msg)


def scamrgb(wb, purple):
    """ScamRgb per channel (hlModeU 1) without clip flags or chroma blocks; wb: the scene in white-balanced units."""
    h, w = wb.shape[:2]
    raw = np.ascontiguousarray((wb * WP).astype(np.float32))
    inp = ctx.texture((w, h), 3, raw.tobytes(), dtype='f4')
    gm = ctx.texture((1, 1), 4, np.ones(4, np.float32).tobytes(), dtype='f4')
    for t in (inp, gm):
        t.filter = (moderngl.NEAREST, moderngl.NEAREST)
    out = ctx.texture((w, h), 4, dtype='f4')
    fbo = ctx.framebuffer([out])
    fbo.use()
    ctx.viewport = (0, 0, w, h)
    inp.use(0)
    gm.use(1)
    u = {'InputBuffer': 0, 'GainMap': 1, 'whitePoint': tuple(map(float, WP)), 'inverseSize': (1.0 / w, 1.0 / h), 'cropOffset': (0, 0),
         'hlModeU': 1, 'hlStrengthU': 1.0, 'clipLoU': (1.0, 1.0, 1.0), 'clipHiU': (8.0, 8.0, 8.0), 'clipFlagsU': 0, 'chromaU': 0,
         'purpleU': purple}
    for k, v in u.items():
        if k in PROG:
            PROG[k].value = v
    ctx.vertex_array(PROG, []).render(vertices=3)
    a = np.frombuffer(out.read(), np.float32).reshape(h, w, 4)[..., :3].copy()
    for t in (inp, gm, out, fbo):
        t.release()
    return a


M1 = np.array([[0.4122214708, 0.5363325363, 0.0514459929], [0.2119034982, 0.6806995451, 0.1073969566],
               [0.0883024619, 0.2817188376, 0.6299787005]])
M2 = np.array([[0.2104542553, 0.7936177850, -0.0040720468], [1.9779984951, -2.4285922050, 0.4505937099],
               [0.0259040371, 0.7827717662, -0.8086757660]])


def chroma(rgb):
    x = np.maximum(rgb, 0) / np.maximum(rgb.mean(-1, keepdims=True), 1e-6)
    lab = np.cbrt(np.maximum(x @ M1.T, 0)) @ M2.T
    return np.hypot(lab[..., 1], lab[..., 2])


def bar(fill, sky=5.8, fringe=None, w=96, h=64, top=24, bottom=40):
    """A horizontal bar (rows top .. bottom - 1) of colour fill against a neutral sky; fringe: colours of the bar's first rows."""
    img = np.full((h, w, 3), sky, np.float32)
    img[top:bottom] = fill
    if fringe is not None:
        for i, c in enumerate(fringe):
            img[top + i] = c
            img[bottom - 1 - i] = c
    return img


def same(a, b):
    return float(np.abs(a - b).max())


def main():
    # the lens fringe: violet .. magenta rows on the dark side, brightest next to the sky (the merge's colours on the ring)
    fringe = [np.array(v, np.float32) for v in ((0.42, 0.33, 0.39), (0.27, 0.21, 0.30), (0.18, 0.14, 0.21), (0.13, 0.11, 0.15))]
    scene = bar(np.array([0.10, 0.11, 0.11], np.float32), fringe=fringe)
    a, b = scamrgb(scene, 0.0), scamrgb(scene, 1.0)
    rows = list(range(24, 28)) + list(range(36, 40))
    ca, cb = chroma(a[rows, 8:-8]), chroma(b[rows, 8:-8])
    ya, yb = a[rows, 8:-8] @ LUMA, b[rows, 8:-8] @ LUMA
    print('fringe chroma before %s, after %s' % (np.round(ca.mean(-1), 3), np.round(cb.mean(-1), 3)))
    check(np.all(cb <= 0.25 * ca + 1e-4), 'the lens fringe goes neutral (chroma %.3f -> %.3f at most)' % (ca.max(), cb.max()))
    check(np.abs(yb / ya - 1).max() < 1e-3, 'the fringe keeps its luminance (%.1e)' % np.abs(yb / ya - 1).max())
    check(same(a[:24], b[:24]) == 0.0 and same(a[40:], b[40:]) == 0.0, 'the sky bit-identical')
    flower = np.array([0.25, 0.12, 0.35], np.float32)
    cases = {
        'a violet flower on grey': bar(flower, sky=0.30),
        'a violet LED with its violet glow': bar(np.array([0.30, 0.10, 0.36], np.float32), sky=0.0,
                                                 fringe=[np.array([4.0, 1.2, 4.6], np.float32)] * 3),
        'a blue sky round a blown sun': bar(np.array([30.0, 30.0, 30.0], np.float32), sky=0.0,
                                            fringe=[np.array([0.45, 0.60, 0.90], np.float32)] * 6) * 1.0,
        'a red surface against the sky': bar(np.array([0.30, 0.05, 0.06], np.float32)),
        'an orange surface against the sky': bar(np.array([0.40, 0.18, 0.04], np.float32)),
        'a neutral dark bar against the sky': bar(np.array([0.10, 0.10, 0.10], np.float32)),
    }
    for k, s in cases.items():
        check(same(scamrgb(s, 0.0), scamrgb(s, 1.0)) == 0.0, '%s bit-identical' % k)
    # the blue sky round the sun: the sky rows only (the sun's own recovery is the same either way)
    s = np.full((64, 96, 3), 0.0, np.float32)
    s[:] = (0.45, 0.60, 0.90)
    s[28:36, 40:56] = 30.0
    check(same(scamrgb(s, 0.0), scamrgb(s, 1.0)) == 0.0, 'a blue sky with a blown sun in it bit-identical')
    v = bar(flower, top=16, bottom=48)
    a, b = scamrgb(v, 0.0), scamrgb(v, 1.0)
    changed = np.abs(a - b).max(-1) > 0
    rows = np.nonzero(changed.any(-1))[0]
    print('a violet surface against the sky: rows changed %s' % rows)
    check(len(rows) > 0 and rows.min() >= 16 and rows.max() <= 47 and np.all((rows <= 16 + 5) | (rows >= 47 - 5)),
          'a violet surface against the sky changes within 6 px of the sky only')
    check(same(a[23:41], b[23:41]) == 0.0, 'its inside beyond 7 px bit-identical')
    print('purple fringe check %s' % ('FAIL' if FAIL else 'PASS'))
    return 1 if FAIL else 0


if __name__ == '__main__':
    sys.exit(main())
