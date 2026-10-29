#!/usr/bin/env python3
"""P395 (owner, 2026-10-29, vivo X200 Ultra: the yellow «Лента» facade and the sunlit birches orange-ochre beside ArkCam's yellow;
the burst 10:27:53 through the host chain: the facade's scene hue after the camera matrix OKLab 82, the tone keeps it, ArkCam ~90):
why/combine.glsl yellowHueU on the GPU (moderngl), the shipped shader as WhyCombine / LiveWhyTone draw it (the uniforms of
check_light_chroma's Renderer, LIGHT_CHROMA 1 as the photo). Checks:
- the facade's colour (linear sRGB 1 : 0.597 : 0.134, the burst) turns towards yellow: CIELAB hue +4 .. +7 deg at every exposure from
  the grey to the light tones, the lightness within 1 L*, the chroma not lower than before by more than 2 C*;
- bit-identical (max |d| 0): skin (ColorChecker light / dark skin, Fitzpatrick III - V), a deep orange, a red-orange sunset glow, a
  cream wall, a neutral, lavender, a green leaf, lime, the blues, at 0.18 x 2^-1 .. 2^3;
- no step: a hue sweep of a saturated colour (OKLab 40 .. 120 deg, 0.25 deg a column) and a saturation sweep at the band's centre
  (C / L 0.06 .. 0.20) - the largest dE76 between neighbours at most before's + 0.6;
Usage: check_yellow_hue.py
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


def oklab_to_lin(L, a, b):
    l_ = L + 0.3963377774 * a + 0.2158037573 * b
    m_ = L - 0.1055613458 * a - 0.0638541728 * b
    s_ = L - 0.0894841775 * a - 1.2914855480 * b
    l, m, s = l_ ** 3, m_ ** 3, s_ ** 3
    return np.stack([4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s, -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
                     -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s], -1)


def main():
    r = clc.Renderer()
    defs = {'LIGHT_CHROMA': '1', 'YELLOW_HUE': '1'}
    on, off = {'yellowHueU': 1.0}, {'yellowHueU': 0.0}
    scales = 0.18 * 2.0 ** np.arange(-1.0, 3.01, 0.5)
    facade = clc.norm((1.0, 0.597, 0.134))
    a, b = r.render([facade], scales, defs, off)[0], r.render([facade], scales, defs, on)[0]
    La, Ca, ha = clc.srgb_lab(a)
    Lb, Cb, hb = clc.srgb_lab(b)
    dh = hb - ha
    print('facade: hue before %s' % np.round(ha, 1))
    print('        hue after  %s' % np.round(hb, 1))
    # (from the light tones on LIGHT_CHROMA turns it towards yellow already: there it turns less, never past 94 deg)
    mid = ha < 85.0
    check(np.all((dh[mid] >= 4.0) & (dh[mid] <= 7.0)), 'the facade turns +4 .. +7 deg up to the light tones (%s)' % np.round(dh, 2))
    check(np.all((dh[~mid] >= 0.0) & (hb[~mid] <= 94.0)), 'the facade in the light tones: towards yellow, not past 94 deg (%s)' % np.round(hb, 1))
    check(np.all(np.abs(Lb - La) <= 1.0), 'the facade keeps its lightness (%s)' % np.round(Lb - La, 2))
    check(np.all(Cb >= Ca - 2.0), 'the facade keeps its chroma (%s)' % np.round(Cb - Ca, 2))
    alone = {'skin light': (0.76, 0.58, 0.50), 'skin dark': (0.45, 0.32, 0.26), 'fitz III': (0.80, 0.60, 0.46), 'fitz IV': (0.66, 0.46, 0.33),
             'fitz V': (0.47, 0.31, 0.21), 'deep orange': (1.0, 0.35, 0.05), 'sunset glow': (1.0, 0.38, 0.12), 'cream wall': (1.0, 0.90, 0.75),
             'neutral': (0.5, 0.5, 0.5), 'lavender': (0.9, 0.8, 1.0), 'green leaf': (0.30, 0.50, 0.12), 'lime': (0.55, 0.85, 0.10),
             'sky blue': (0.45, 0.60, 0.90), 'deep blue': (0.25, 0.45, 0.95)}
    rows = [clc.norm(v) for v in alone.values()]
    a, b = r.render(rows, scales, defs, off), r.render(rows, scales, defs, on)
    for i, k in enumerate(alone):
        check(np.abs(a[i] - b[i]).max() == 0.0, '%s bit-identical (max |d| %.2e)' % (k, np.abs(a[i] - b[i]).max()))
    # sweeps at 0.18 x 2: hue (OKLab 40 .. 120 deg) at C / L 0.18, saturation at 82 deg (C / L 0.06 .. 0.20)
    hs = np.arange(40.0, 120.01, 0.25)
    Lk = 0.62
    hue_cols = [oklab_to_lin(Lk, 0.18 * Lk * np.cos(np.radians(t)), 0.18 * Lk * np.sin(np.radians(t))) for t in hs]
    sats = np.arange(0.06, 0.2001, 0.0025)
    sat_cols = [oklab_to_lin(Lk, s * Lk * np.cos(np.radians(82.0)), s * Lk * np.sin(np.radians(82.0))) for s in sats]
    for name, cols in (('hue sweep', hue_cols), ('saturation sweep', sat_cols)):
        cols = [np.maximum(c, 1e-4) for c in cols]
        base = np.stack([np.asarray(c) for c in cols])
        # one row, the colours as columns: rows of one colour each, one exposure (the colour's own luminance x 2)
        img = lambda u: np.concatenate([r.render([c / (c @ clc.LUMA)], [0.36], defs, u)[0] for c in cols], 0)
        pa, pb = lab76(img(off)), lab76(img(on))
        sa = np.linalg.norm(np.diff(pa, axis=0), axis=-1).max()
        sb = np.linalg.norm(np.diff(pb, axis=0), axis=-1).max()
        print('%s: largest dE76 between neighbours before %.2f, after %.2f' % (name, sa, sb))
        check(sb <= sa + 0.6, '%s continuous (%.2f against %.2f)' % (name, sb, sa))
    print('yellow hue check %s' % ('FAIL' if FAIL else 'PASS'))
    return 1 if FAIL else 0


if __name__ == '__main__':
    sys.exit(main())
