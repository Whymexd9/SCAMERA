#!/usr/bin/env python3
"""P402 (owner, 2026-10-29: «Перенеси то чего нам не хватает», the reference app's settings): why/combine.glsl's AGX_INSET
(«Плёночное выгорание светов»), hrEaseU («Сила удержания цвета в светах») and ARK_BASE (the reference fusion layout's base of the
tone) on the GPU (moderngl), the shipped shader as check_light_chroma's Renderer draws it. Checks:
- AGX_INSET with the full strength (insetU 1) is the shader without it (within 2e-6: the inverse is computed); at 0.5 a neutral
  grey keeps its colour (the inset's rows sum to 1, within 1e-3) and a saturated light keeps more chroma than at full strength;
- hrEaseU 0 bit-identical to unset; at 1 (strength 0) the light above the shoulder's knee is brighter than with the shoulder, at
  0.4 in between; below the knee bit-identical;
- ARK_BASE without headroom (clip 1) and an exposure in range bit-identical; with headroom (clip 4) the fused target above 0.7 of
  the tonemapper top is pulled under it (never brighter than without), an exposure sweep stays monotonic and without steps.
Usage: check_why_v207.py
"""
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_light_chroma as clc  # noqa: E402

FAIL = []


def check(cond, msg):
    print(('PASS ' if cond else 'FAIL ') + msg)
    if not cond:
        FAIL.append(msg)


def chroma(disp):
    lin = np.where(disp <= 0.04045, disp / 12.92, ((disp + 0.055) / 1.055) ** 2.4)
    m1 = np.array([[0.4122214708, 0.5363325363, 0.0514459929], [0.2119034982, 0.6806995451, 0.1073969566],
                   [0.0883024619, 0.2817188376, 0.6299787005]])
    m2 = np.array([[0.2104542553, 0.7936177850, -0.0040720468], [1.9779984951, -2.4285922050, 0.4505937099],
                   [0.0259040371, 0.7827717662, -0.8086757660]])
    lab = np.cbrt(np.maximum(lin @ m1.T, 0)) @ m2.T
    return np.hypot(lab[..., 1], lab[..., 2])


def main():
    r = clc.Renderer()
    scales = 0.18 * 2.0 ** np.arange(-3.0, 4.01, 0.5)
    cols = {'grey': (1, 1, 1), 'skin': (0.62, 0.45, 0.36), 'sky': (0.45, 0.60, 0.90), 'red LED': (1.5, 0.12, 0.10),
            'yellow': (1.0, 0.6, 0.13), 'green': (0.3, 0.5, 0.12)}
    rows = [clc.norm(v) for v in cols.values()]
    base = r.render(rows, scales, {})
    full = r.render(rows, scales, {'AGX_INSET': '1'}, {'insetU': 1.0})
    check(np.abs(full - base).max() <= 2e-6, 'AGX_INSET at full strength is the shader without it (%.1e)' % np.abs(full - base).max())
    for op in ('0', '1'):
        b = r.render(rows, scales, {'TONE_OP': op} if op == '1' else {}, {'toneOpU': 3})
        f = r.render(rows, scales, dict({'AGX_INSET': '1'}, **({'TONE_OP': op} if op == '1' else {})), {'insetU': 1.0, 'toneOpU': 3})
        check(np.abs(f - b).max() <= 2e-6, 'TONE_OP %s: the full strength as without (%.1e)' % (op, np.abs(f - b).max()))
    half = r.render(rows, scales, {'AGX_INSET': '1'}, {'insetU': 0.5})
    grey = list(cols).index('grey')
    check(np.abs(half[grey] - base[grey]).max() <= 1e-3, 'a grey keeps its colour at half strength (%.1e)' % np.abs(half[grey] - base[grey]).max())
    led = list(cols).index('red LED')
    lit = scales >= 0.18 * 2
    ch, cb = chroma(half[led][lit]), chroma(base[led][lit])
    print('red LED chroma, full %s, half %s' % (np.round(cb, 3), np.round(ch, 3)))
    check(np.all(ch >= cb - 1e-4) and (ch > cb + 0.005).any(), 'a saturated light keeps more chroma at half strength')

    hr = {'hrU': 20.0, 'hrKneeU': 0.5, 'hrRoomU': 0.4}
    bright = 0.18 * 2.0 ** np.arange(-2.0, 7.01, 0.25)
    wide = {'HEADROOM_WIDE': '1'}
    plain = r.render(rows, bright, wide, hr)
    zero = r.render(rows, bright, wide, dict(hr, hrEaseU=0.0))
    check(np.abs(zero - plain).max() == 0.0, 'hrEaseU 0 bit-identical to unset')
    off = r.render(rows, bright, wide, dict(hr, hrEaseU=1.0))
    mid = r.render(rows, bright, wide, dict(hr, hrEaseU=0.4))
    top = 0.18 * 2.0 ** (3.5 + 0.3)
    below = bright * 1.0 < 0.5 * top * 0.45
    check(np.abs(off[:, below] - plain[:, below]).max() == 0.0, 'below the knee bit-identical')
    y = lambda a: a @ clc.LUMA
    up = bright > 0.5 * top
    check(np.all(y(off[:, up]) >= y(plain[:, up]) - 1e-6) and (y(off[:, up]) > y(plain[:, up]) + 1e-3).any(),
          'without the shoulder (strength 0) the light above the knee is brighter')
    check(np.all(y(mid[:, up]) <= y(off[:, up]) + 1e-6) and np.all(y(mid[:, up]) >= y(plain[:, up]) - 1e-6),
          'strength 0.6 lies between')

    arkd = {'ARK_BASE': '1'}
    low = r.render(rows, scales, arkd, {'clipU': 1.0, 'headroomU': 0})
    lowb = r.render(rows, scales, {}, {'clipU': 1.0, 'headroomU': 0})
    check(np.abs(low - lowb).max() == 0.0, 'ARK_BASE without headroom bit-identical (%.1e)' % np.abs(low - lowb).max())
    hb = r.render(rows, bright, wide, hr)
    ha = r.render(rows, bright, dict(wide, **arkd), hr)
    check(np.all(y(ha) <= y(hb) + 1e-5), 'ARK_BASE with headroom never brighter than without')
    fine = 0.18 * 2.0 ** np.arange(-2.0, 7.01, 0.05)
    for i, k in enumerate(cols):
        s = y(r.render([rows[i]], fine, dict(wide, **arkd), hr)[0])
        d = np.diff(s)
        check(np.all(d >= -2e-4), '%s: ARK_BASE exposure sweep monotonic (min step %.1e)' % (k, d.min()))
        sb = y(r.render([rows[i]], fine, wide, hr)[0])
        check(np.abs(d).max() <= np.abs(np.diff(sb)).max() + 0.01, '%s: no step (%.3f against %.3f)' % (k, np.abs(d).max(), np.abs(np.diff(sb)).max()))
    print('why reference tone check %s' % ('FAIL' if FAIL else 'PASS'))
    return 1 if FAIL else 0


if __name__ == '__main__':
    sys.exit(main())
