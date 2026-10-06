"""Lateral CA left in a merged RGB (P28 device check): R and B displacement against G per tile, radial fit, as
tools/quad/measure_raw_ca.py does on a RAW frame.

measure_rgb_ca.py <out.f32> [w h] [--grid g]
<out.f32> is the worker's --nice-capture output (float RGB w x h x 3, side planes after it, ignored). Default size 4096 x 3072;
a file holding 8192 x 6144 x 3 floats is taken as the 2x grid. Per 128 x 128 tile with gradient and nothing clipped, C is
matched to G by gain / offset and its displacement solved by Lucas-Kanade (two steps); d = (k1 + k2 r^2) p fitted (r over the
half diagonal), refitted without the worst 20 %. Reported in sensor px: the shift at the corner (k1 + k2) and the median
radial component in the outer ring (r > 0.6); its sign tells over- from under-correction (a correction of the opposite sign
leaves the opposite radial component).
"""
import sys

import numpy as np
from scipy import ndimage


def load(path, w, h):
    a = np.fromfile(path, np.float32)
    if (w, h) == (4096, 3072) and a.size >= 8192 * 6144 * 3:
        w, h = 8192, 6144
    return a[:w * h * 3].reshape(h, w, 3), w, h


def tile_shift(c, g):
    d = np.zeros(2)
    gy, gx = np.gradient(g)
    M = np.array([[np.sum(gx * gx), np.sum(gx * gy)], [np.sum(gx * gy), np.sum(gy * gy)]])
    if np.linalg.cond(M) > 50:
        return None
    for _ in range(2):
        cs = ndimage.shift(c, (-d[1], -d[0]), order=1, mode='nearest') if np.any(d) else c
        A = np.vstack([g.ravel(), np.ones(g.size)]).T
        k, b = np.linalg.lstsq(A, cs.ravel(), rcond=None)[0]
        if not k > 0.05:
            return None
        e = (cs - b) / k - g
        d = d - np.linalg.solve(M, np.array([np.sum(gx * e), np.sum(gy * e)]))
    return d if np.all(np.abs(d) < 6) else None


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    grid = 1
    if '--grid' in sys.argv:
        grid = int(sys.argv[sys.argv.index('--grid') + 1])
        args = [a for a in args if a != str(grid)]
    w, h = (int(args[1]), int(args[2])) if len(args) >= 3 else (4096, 3072)
    img, w, h = load(args[0], w, h)
    if w == 8192 and grid == 1:
        grid = 2
    T = 128 * grid
    clip = 0.95 * float(np.percentile(img[..., 1], 99.9))
    res = {'R': [], 'B': []}
    for ty in range(T, h - 2 * T, T):
        for tx in range(T, w - 2 * T, T):
            g = img[ty:ty + T, tx:tx + T, 1].astype(np.float64)
            if g.max() >= clip or g.mean() < 1e-3:
                continue
            gy, gx = np.gradient(g)
            if np.mean(gx * gx + gy * gy) < (0.02 * g.mean()) ** 2:
                continue
            for name, ch in (('R', 0), ('B', 2)):
                c = img[ty:ty + T, tx:tx + T, ch].astype(np.float64)
                if c.max() >= clip:
                    continue
                d = tile_shift(c, g)
                if d is not None:
                    res[name].append((tx + T / 2, ty + T / 2, d[0], d[1]))
    cx, cy = w / 2, h / 2
    half = np.hypot(cx, cy)
    for name in ('R', 'B'):
        a = np.array(res[name])
        if len(a) < 20:
            print(name, 'too few tiles', len(a))
            continue
        px, py = (a[:, 0] - cx) / half, (a[:, 1] - cy) / half
        r2 = px * px + py * py
        A = np.concatenate([np.stack([px, px * r2], 1), np.stack([py, py * r2], 1)])
        y = np.concatenate([a[:, 2], a[:, 3]])
        k, *_ = np.linalg.lstsq(A, y, rcond=None)
        resid = np.abs(A @ k - y)
        keep = resid < np.percentile(resid, 80)
        k, *_ = np.linalg.lstsq(A[keep], y[keep], rcond=None)
        rr = np.sqrt(r2)
        radial = (a[:, 2] * px + a[:, 3] * py) / np.maximum(rr, 1e-6)
        outer = rr > 0.6
        print(f'{name}: {len(a)} tiles; shift at the corner {(k[0] + k[1]) / grid:+.3f} sensor px; outer ring (r > 0.6, {int(outer.sum())} tiles) '
              f'median radial {np.median(radial[outer]) / grid:+.3f} px, median |d| {np.median(np.hypot(a[outer, 2], a[outer, 3])) / grid:.3f} px')


if __name__ == '__main__':
    main()
