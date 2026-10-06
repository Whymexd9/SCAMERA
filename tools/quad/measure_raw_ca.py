"""Lateral chromatic aberration of a RAW frame (P19 study): R and B displacement against G, per tile, and a radial fit.

measure_raw_ca.py <burst.nch> [frame]
G is interpolated at the R and B sites from their four green neighbours (no demosaic); per 64 x 64-cell tile with enough
gradient and nothing clipped, R (B) is matched to G by a gain / offset and the residual solved for the displacement
d = -(sum grad G grad G^T)^-1 sum grad G e (Lucas-Kanade, one step, iterated twice). The radial model d = (k1 + k2 r^2)(p - c)
(r normalised to the half diagonal) is fitted by least squares; reported: the shift at the corner (RAW px) and the rms residual.
"""
import sys
import numpy as np
from scipy import ndimage

sys.path.insert(0, __file__.rsplit('/', 1)[0] if '/' in __file__ else '.')
import nch as N


def planes(raw, cfa, black):
    a = raw.astype(np.float32) - np.mean(black)
    ox, oy = cfa & 1, cfa >> 1               # canonical: red at (ox, oy)
    a = a[oy:oy + (a.shape[0] - oy) // 2 * 2, ox:ox + (a.shape[1] - ox) // 2 * 2]
    R = a[0::2, 0::2]; B = a[1::2, 1::2]; G1 = a[0::2, 1::2]; G2 = a[1::2, 0::2]
    # G at R (cell i,j at (2i, 2j)): left G1(i, j-1), right G1(i, j), up G2(i-1, j), down G2(i, j)
    gR = 0.25 * (G1 + np.roll(G1, 1, 1) + G2 + np.roll(G2, 1, 0))
    # G at B (2i+1, 2j+1): left G2(i, j), right G2(i, j+1), up G1(i, j), down G1(i+1, j)
    gB = 0.25 * (G2 + np.roll(G2, -1, 1) + G1 + np.roll(G1, -1, 0))
    return R, B, gR, gB


def tile_shift(c, g):
    """Displacement of c against g (cells), gain / offset matched; None when not measurable."""
    d = np.zeros(2)
    for _ in range(2):
        cs = ndimage.shift(c, (-d[1], -d[0]), order=1, mode='nearest') if np.any(d) else c
        A = np.vstack([g.ravel(), np.ones(g.size)]).T
        k, b = np.linalg.lstsq(A, cs.ravel(), rcond=None)[0]
        if not k > 0.05:
            return None
        e = (cs - b) / k - g
        gy, gx = np.gradient(g)
        M = np.array([[np.sum(gx * gx), np.sum(gx * gy)], [np.sum(gx * gy), np.sum(gy * gy)]])
        if np.linalg.cond(M) > 50 or np.trace(M) < 1e3 * g.size * 1e-4:
            return None
        step = -np.linalg.solve(M, np.array([np.sum(gx * e), np.sum(gy * e)]))
        d = d + step
    return d if np.all(np.abs(d) < 2) else None


def main():
    b = N.read(sys.argv[1])
    f = int(sys.argv[2]) if len(sys.argv) > 2 else 0
    R, B, gR, gB = planes(b['planes'][f], b['cfa'], b['black'])
    clip = 0.95 * (b['white'] - np.mean(b['black']))
    h, w = R.shape
    T = 64
    res = {'R': [], 'B': []}
    for ty in range(T, h - 2 * T, T):
        for tx in range(T, w - 2 * T, T):
            for name, c, g in (('R', R, gR), ('B', B, gB)):
                cc, gg = c[ty:ty + T, tx:tx + T], g[ty:ty + T, tx:tx + T]
                if cc.max() >= clip or gg.max() >= clip or gg.mean() < 20:
                    continue
                gy, gx = np.gradient(gg)
                if np.mean(gx * gx + gy * gy) < 4.0:
                    continue
                d = tile_shift(cc, gg)
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
        # d = (k1 + k2 r^2) p  -> least squares on both components
        A = np.concatenate([np.stack([px, px * r2], 1), np.stack([py, py * r2], 1)])
        y = np.concatenate([a[:, 2], a[:, 3]]) / half * half  # cells
        k, *_ = np.linalg.lstsq(A, y, rcond=None)
        # robust: drop the worst 20 % and refit
        resid = np.abs(A @ k - y)
        keep = resid < np.percentile(resid, 80)
        k, *_ = np.linalg.lstsq(A[keep], y[keep], rcond=None)
        rms = np.sqrt(np.mean((A[keep] @ k - y[keep]) ** 2))
        corner = (k[0] + k[1]) * 1.0  # at r = 1 (the corner), in cells per unit p -> cells
        print(f'{name}: {len(a)} tiles, k1={k[0]:.4f} k2={k[1]:.4f} cells; shift at the corner {2 * corner:+.3f} RAW px, '
              f'at r=0.5 {2 * (k[0] + 0.25 * k[1]) * 0.5:+.3f} px; fit rms {2 * rms:.3f} px; median |d| {2 * np.median(np.hypot(a[:, 2], a[:, 3])):.3f} px')


if __name__ == '__main__':
    main()
