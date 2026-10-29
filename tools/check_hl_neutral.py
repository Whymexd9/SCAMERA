#!/usr/bin/env python3
"""P398 (owner, 2026-10-29, the ship's bridge: «света почему-то получаются кремовые»; the stage dump 14:14:07: white frames, sills
and a lamp lit by the sun by the windows are recovered light with the sun's warmth against the sky's white balance, R / G 1.2,
B / G 0.8, and came out cream at L 0.94 - 0.98): why/combine.glsl hlNeutralU on the GPU (moderngl), the shipped shader as
WhyCombine / LiveWhyTone draw it (check_light_chroma's Renderer, LIGHT_CHROMA 1 and YELLOW_HUE 1 as the photo). Checks:
- the sunlit white (linear sRGB 1.2 : 1 : 0.8) at the top of the tone (OKLab L >= 0.91 before) keeps at most half of its chroma,
  never darker (its brightest display channel not lower);
- below the ramp (OKLab L under 0.86) every colour bit-identical;
- bit-identical at every exposure: blue near-whites (sky, sea, haze) and saturated colours (a yellow facade, a deep orange, a
  red LED, a green leaf, a deep blue: the light's own saturation before the tone over 0.6);
- no step: an exposure sweep of the sunlit white and of a pale pink (0.05 EV a column) and a chroma sweep at the top (OKLab L
  0.95, C 0 .. 0.10 at hue 86) - the largest dE76 between neighbours at most before's + 0.6.
Usage: check_hl_neutral.py
"""
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_light_chroma as clc  # noqa: E402

FAIL = []


def check(cond, msg):
    if not cond:
        FAIL.append(msg)
        print('FAIL: ' + msg)


def lab76(disp):
    L, C, h = clc.srgb_lab(disp)
    a, b = C * np.cos(np.radians(h)), C * np.sin(np.radians(h))
    return np.stack([L, a, b], -1)


M1 = np.array([[0.4122214708, 0.5363325363, 0.0514459929], [0.2119034982, 0.6806995451, 0.1073969566],
               [0.0883024619, 0.2817188376, 0.6299787005]])
M2 = np.array([[0.2104542553, 0.7936177850, -0.0040720468], [1.9779984951, -2.4285922050, 0.4505937099],
               [0.0259040371, 0.7827717662, -0.8086757660]])


def oklab(disp):
    c = np.clip(disp, 0, 1)
    lin = np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)
    return np.cbrt(np.maximum(lin @ M1.T, 0)) @ M2.T


def oklab_to_lin(L, a, b):
    l_ = L + 0.3963377774 * a + 0.2158037573 * b
    m_ = L - 0.1055613458 * a - 0.0638541728 * b
    s_ = L - 0.0894841775 * a - 1.2914855480 * b
    l, m, s = l_ ** 3, m_ ** 3, s_ ** 3
    return np.stack([4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s, -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
                     -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s], -1)


def main():
    r = clc.Renderer()
    defs = {'LIGHT_CHROMA': '1', 'YELLOW_HUE': '1', 'HL_NEUTRAL': '1'}
    on, off = {'yellowHueU': 1.0, 'hlNeutralU': 1.0}, {'yellowHueU': 1.0, 'hlNeutralU': 0.0}
    scales = 0.18 * 2.0 ** np.arange(-1.0, 4.51, 0.25)
    sun = clc.norm((1.2, 1.0, 0.8))
    a, b = r.render([sun], scales, defs, off)[0], r.render([sun], scales, defs, on)[0]
    ka, kb = oklab(a), oklab(b)
    ca, cb = np.hypot(ka[:, 1], ka[:, 2]), np.hypot(kb[:, 1], kb[:, 2])
    top, low = ka[:, 0] >= 0.91, ka[:, 0] < 0.86
    print('sunlit white: OKLab L %s' % np.round(ka[:, 0], 3))
    print('        chroma before %s' % np.round(ca, 4))
    print('        chroma after  %s' % np.round(cb, 4))
    check(top.any() and np.all(cb[top] <= 0.5 * ca[top] + 1e-4), 'the sunlit white at the top goes white (%s of %s)' % (np.round(cb[top], 4), np.round(ca[top], 4)))
    check(np.all(b.max(-1) >= a.max(-1) - 1e-6), 'the sunlit white never darker (brightest channel)')
    check(np.abs(a[low] - b[low]).max() == 0.0, 'the sunlit white below L 0.86 bit-identical')
    alone = {'sky blue': (0.45, 0.60, 0.90), 'pale sky': (0.80, 0.90, 1.10), 'sea haze': (0.75, 0.88, 1.05), 'deep blue': (0.25, 0.45, 0.95),
             'yellow facade': (1.0, 0.597, 0.134), 'deep orange': (1.0, 0.35, 0.05), 'red LED': (1.50, 0.12, 0.10),
             'green leaf': (0.30, 0.50, 0.12)}
    rows = [clc.norm(v) for v in alone.values()]
    a, b = r.render(rows, scales, defs, off), r.render(rows, scales, defs, on)
    for i, k in enumerate(alone):
        check(np.abs(a[i] - b[i]).max() == 0.0, '%s bit-identical (max |d| %.2e)' % (k, np.abs(a[i] - b[i]).max()))
    mids = {'skin': (0.62, 0.45, 0.36), 'cream wall': (1.0, 0.90, 0.75), 'neutral': (0.5, 0.5, 0.5), 'lavender': (0.9, 0.8, 1.0)}
    rows = [clc.norm(v) for v in mids.values()]
    a, b = r.render(rows, scales, defs, off), r.render(rows, scales, defs, on)
    for i, k in enumerate(mids):
        lowk = oklab(a[i])[:, 0] < 0.86
        check(np.abs(a[i][lowk] - b[i][lowk]).max() == 0.0, '%s below L 0.86 bit-identical' % k)
    fine = 0.18 * 2.0 ** np.arange(0.0, 4.51, 0.05)
    for name, rgb in (('sunlit white', sun), ('pale pink', clc.norm((1.15, 0.95, 1.0)))):
        pa, pb = lab76(r.render([rgb], fine, defs, off)[0]), lab76(r.render([rgb], fine, defs, on)[0])
        sa, sb = np.linalg.norm(np.diff(pa, axis=0), axis=-1).max(), np.linalg.norm(np.diff(pb, axis=0), axis=-1).max()
        print('%s exposure sweep: largest dE76 between neighbours before %.2f, after %.2f' % (name, sa, sb))
        check(sb <= sa + 0.6, '%s exposure sweep continuous (%.2f against %.2f)' % (name, sb, sa))
    cs = np.arange(0.0, 0.1001, 0.0025)
    cols =[np.maximum(oklab_to_lin(0.95, c * np.cos(np.radians(86.0)), c * np.sin(np.radians(86.0))), 1e-4) for c in cs]
    img = lambda u: np.concatenate([r.render([c / (c @ clc.LUMA)], [2.2], defs, u)[0] for c in cols], 0)
    pa, pb = lab76(img(off)), lab76(img(on))
    sa, sb = np.linalg.norm(np.diff(pa, axis=0), axis=-1).max(), np.linalg.norm(np.diff(pb, axis=0), axis=-1).max()
    print('chroma sweep at the top: largest dE76 between neighbours before %.2f, after %.2f' % (sa, sb))
    check(sb <= sa + 0.6, 'chroma sweep at the top continuous (%.2f against %.2f)' % (sb, sa))
    print('highlight neutral check %s' % ('FAIL' if FAIL else 'PASS'))
    return 1 if FAIL else 0


if __name__ == '__main__':
    sys.exit(main())
