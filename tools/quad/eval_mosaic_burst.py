"""Scores of hybrid results of gen_mosaic_burst.py bursts against the scene (P14 / P15).

eval_mosaic_burst.py <prefix> <out_b1.f32> <out_b2.f32> <out_b4.f32> [crop png prefix]
The output of block b sits (b-1)/2 px against the sensor grid (hybridReconstructMosaic), the scene is moved by the same to
compare. Per region: luma PSNR, mean false colour on the neutral zone plate (its scene chroma is 0), and the lattice ratio of the
background (power at the block periods 2 / 4 / 8 px against the neighbouring bins; ~1 = none).
"""
import sys
import numpy as np
from scipy import ndimage
from PIL import Image

W, H = 4096, 3072
REGIONS = {
    'zone plate (neutral)': (slice(64, 1400), slice(64, 1400)),
    'zone plate (colour)': (slice(64, 1400), slice(1400, 2800)),
    'edges': (slice(100, 1390), slice(2900, 4000)),
    'bars 2-6 px (neutral)': (slice(1600, 2800), slice(100, 2000)),
    'bars 2-6 px (colour)': (slice(1600, 2800), slice(2100, 4000)),
    'background': (slice(2850, 3000), slice(100, 4000)),
}


def luma(a):
    return a @ np.array([0.25, 0.5, 0.25], np.float32)


def lattice(lum):
    n = min(lum.shape)
    lum = lum[:n, :n] - lum[:n, :n].mean()
    win = np.hanning(n)[:, None] * np.hanning(n)[None, :]
    p = np.abs(np.fft.fft2(lum * win)) ** 2
    worst = 0
    for per in (2, 4, 8):
        for (u, v) in ((0, n // per), (n // per, 0), (n // per, n // per)):
            ys = [(u + d) % n for d in range(-4, 5)]
            xs = [(v + d) % n for d in range(-4, 5)]
            nb = p[np.ix_(ys, xs)].copy()
            nb[3:6, 3:6] = np.nan
            worst = max(worst, p[u, v] / max(np.nanmedian(nb), 1e-30))
    return worst


def main():
    prefix = sys.argv[1]
    outs = sys.argv[2:5]
    crops = sys.argv[5] if len(sys.argv) > 5 else None
    gt = np.load(prefix + '_gt.npy')
    tiles = []
    for b, path in zip((1, 2, 4), outs):
        o = (b - 1) / 2
        g = np.stack([ndimage.shift(gt[..., c], (o, o), order=3, mode='nearest') for c in range(3)], -1) if o else gt
        out = np.fromfile(path, '<f4', count=W * H * 3).reshape(H, W, 3)
        line = [f'block {b}:']
        for name, (ys, xs) in REGIONS.items():
            a, r = out[ys, xs], g[ys, xs]
            mse = np.mean((luma(a) - luma(r)) ** 2)
            psnr = 10 * np.log10(0.7 ** 2 / max(mse, 1e-12))
            extra = ''
            if 'neutral' in name:
                L = np.maximum(luma(a), 1e-3)
                extra = f' falseColour={np.mean(np.abs(a[..., 0] - a[..., 1]) + np.abs(a[..., 2] - a[..., 1])) / np.mean(L):.4f}'
            if name == 'background':
                extra = f' lattice={lattice(luma(a)):.1f}'
            line.append(f'{name} {psnr:.1f} dB{extra}')
        print(' | '.join(line))
        if crops:
            for k, (y, x) in enumerate([(900, 900), (1700, 200), (1700, 2200), (500, 3300)]):
                c = np.clip(out[y:y + 160, x:x + 240] / 0.7, 0, 1) ** (1 / 2.2)
                tiles.append((b, k, (c * 255 + 0.5).astype(np.uint8)))
    if crops:
        rows = []
        for k in range(4):
            g0 = np.clip(gt[[900, 1700, 1700, 500][k]:[900, 1700, 1700, 500][k] + 160, [900, 200, 2200, 3300][k]:[900, 200, 2200, 3300][k] + 240] / 0.7, 0, 1) ** (1 / 2.2)
            row = [(g0 * 255 + 0.5).astype(np.uint8)] + [t[2] for t in tiles if t[1] == k]
            rows.append(np.concatenate(row, 1))
        Image.fromarray(np.concatenate(rows, 0)).resize((5 * 240 * 2, 4 * 160 * 2), Image.NEAREST).save(crops + '_crops.png')


if __name__ == '__main__':
    main()
