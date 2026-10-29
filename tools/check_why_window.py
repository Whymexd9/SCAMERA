#!/usr/bin/env python3
"""P400 (owner, 2026-10-29, the ship's bridge: «на кранах видно, такую проблему мы уже решали очень давно» - the tower and the crane
behind a window 0.4 - 0.55 EV under the sky where the scene holds 2.3 - 3.1 EV): why/lpyr WINDOW (WhyFusion.windowParams /
windowLevel, WhyTone.windowDepth) on the host GPU, the photo's pass order (check_why_pyramid_bands.fuse) with the Bento weights and
the shipped bands. A room (wall I 0.02 x AE) with a daylit window (sky 3.5, a tower 0.74, a crane bar 0.4 in it), a sunlit sill
(0.5) far from the window, a backlit face (0.05) in front of the glass. The window's daylight EV: the shot's ae 1.28 and EV offset
14.18 (vivo X200 Ultra 14:22:07). Checks:
- the tower and the crane in the window go darker (fused luma -0.05 or more), the blown sky in it and the room bit-identical;
- the backlit face in front of the glass and the room's wall bit-identical; the sunlit sill (daylight in the room: the compromise)
  darker by the depth at most;
- the same room lit by a lamp (EV offset 7.5: the brightest at EV 8.5, no daylight) bit-identical;
- a strength of 0 bit-identical to the fusion without the term;
- WhyFusion.windowParams: off without headroom, under a landscape's median (EV 13), on in the room (the shot's p50 0.044).
Usage: check_why_window.py
"""
import math
import re
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_why_pyramid as cwp  # noqa: E402
import check_why_pyramid_bands as cwb  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
FUSION_JAVA = (ROOT / 'app/src/main/java/com/particlesdevs/photoncamera/processing/opengl/postpipeline/WhyFusion.java').read_text()
TONE_JAVA = (ROOT / 'app/src/main/java/com/particlesdevs/photoncamera/processing/opengl/postpipeline/WhyTone.java').read_text()
FAIL = []


def check(cond, msg):
    print(('PASS ' if cond else 'FAIL ') + msg)
    if not cond:
        FAIL.append(msg)


def smooth(a, b, x):
    t = min(max((x - a) / (b - a), 0.0), 1.0)
    return t * t * (3 - 2 * t)


def window_params(depth, hr, clip, p50, ae, ev):
    """WhyFusion.windowParams."""
    if not depth > 0 or not hr > clip or not math.isfinite(ev) or not ae > 0:
        return None
    median = math.log2(max(p50, 1e-6)) + ev
    strength = depth * (1 - smooth(10.0, 11.5, median))
    if strength < 1e-4:
        return None
    shift = math.log2(ae) - ev
    return strength, 10.5 + shift, 12.0 + shift


def window_level(width, deepest):
    return max(1, min(int(math.floor(math.log2(math.sqrt(3.0) * width / 111.0) + 0.5)), deepest))


def main():
    depth = float(re.search(r'value\("window_depth", ([0-9.]+)f\)', TONE_JAVA).group(1))
    check('10.5f + shift, 12f + shift' in FUSION_JAVA and '(median - 10f) / 1.5f' in FUSION_JAVA, 'windowParams as mirrored here')
    ae, ev = 1.2798, 14.18
    H, W = 384, 512
    yy, xx = np.mgrid[0:H, 0:W]
    lum = np.full((H, W), 0.02)
    win = (yy >= 60) & (yy < 220) & (xx >= 220) & (xx < 470)
    lum[win] = 3.5
    tower = (yy >= 110) & (yy < 220) & (xx >= 330) & (xx < 380)
    crane = (yy >= 120) & (yy < 128) & (xx >= 240) & (xx < 330)
    lum[tower] = 0.74
    lum[crane] = 0.4
    face = (yy >= 160) & (yy < 220) & (xx >= 260) & (xx < 290)
    lum[face] = 0.05
    sill = (yy >= 300) & (yy < 330) & (xx >= 20) & (xx < 160)
    lum[sill] = 0.5
    img = cwb.grey(lum / ae)
    u = dict(cwb.BENTO, aeU=ae, expU=(0.06757302, 1.0, 0.2599481, 2.8267896))
    gl = cwb.Gl()
    n = cwp.levels_for(W, H, 24)
    deepest = n + cwb.REF_DOWN - 1
    wl = window_level(W, deepest)
    p = window_params(depth, 20.36, 4.0, 0.04395, ae, ev)
    check(p is not None and abs(p[0] - depth) < 1e-6, 'the shot (median EV %.1f) takes the full depth %.2f' % (math.log2(0.04395) + ev, depth))
    off, _ = cwb.fuse(gl, img, u, 24, cwb.BANDS)
    zero, _ = cwb.fuse(gl, img, u, 24, cwb.BANDS, win=(0.0, p[1], p[2], wl))
    on, _ = cwb.fuse(gl, img, u, 24, cwb.BANDS, win=(p[0], p[1], p[2], wl))
    check(np.abs(zero - off).max() == 0.0, 'a strength of 0 bit-identical (%.1e)' % np.abs(zero - off).max())
    d = on - off
    inner = lambda m: m & (np.abs(np.gradient(np.log2(lum))[0]) + np.abs(np.gradient(np.log2(lum))[1]) == 0)
    for name, m, lo, hi in (('the tower', tower, -1.0, -0.05), ('the crane', crane, -1.0, -0.05)):
        v = float(np.median(d[m]))
        print('%s: fused %.3f -> %.3f' % (name, float(np.median(off[m])), float(np.median(on[m]))))
        check(lo <= v <= hi, '%s goes darker (%.3f)' % (name, v))
    sky = win & ~tower & ~crane & ~face
    for name, m in (('the sky in the window', inner(sky)), ('the backlit face', inner(face)), ('the room', (lum == 0.02) & ~win)):
        v = float(np.abs(d[m]).max())
        check(v == 0.0, '%s unchanged (%.1e)' % (name, v))
    v = float(d[sill].min())
    check(-p[0] - 1e-6 <= v <= 0.0, 'the sunlit sill darker by the depth at most (%.3f)' % v)
    pl = window_params(depth, 20.36, 4.0, 0.04395, ae, 7.5)
    lamp, _ = cwb.fuse(gl, img, u, 24, cwb.BANDS, win=(pl[0], pl[1], pl[2], wl))
    check(np.abs(lamp - off).max() == 0.0, 'a lamp-lit room (EV offset 7.5) bit-identical (%.1e)' % np.abs(lamp - off).max())
    check(window_params(depth, 0.0, 4.0, 0.04395, ae, ev) is None, 'no headroom: off')
    check(window_params(depth, 20.36, 4.0, 2.0 ** (13.0 - ev), ae, ev) is None, 'a landscape (median EV 13): off')
    check(window_params(depth, 20.36, 4.0, 0.04395, ae, float('nan')) is None, 'exposure unknown: off')
    print('why window check %s' % ('FAIL' if FAIL else 'PASS'))
    return 1 if FAIL else 0


if __name__ == '__main__':
    sys.exit(main())
