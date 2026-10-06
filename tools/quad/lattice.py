"""P14 checks on a hybrid result (float RGB w*h*3, camera RGB in base units, sensor geometry; trailers ignored).

lattice.py <out.f32> <w> <h> <prefix> [crop x,y,size ...]
- preview <prefix>_s.jpg (grey-world WB on mid-tones, gamma 2.2, 1/4 size) and full-resolution crops <prefix>_c<i>.png
- lattice: power of the luma spectrum at the block frequencies (period 2 and 4 px, both axes and the diagonal) against
  the median of the surrounding bins. A colour-block lattice shows as a ratio far above 1 on flat and textured areas alike.
- zipper / false colour: mean |chroma| at the Nyquist bins (period 2) relative to the chroma floor.
"""
import sys
import numpy as np
from PIL import Image


def load(path, w, h):
    a = np.fromfile(path, dtype='<f4', count=w * h * 3)
    return a.reshape(h, w, 3)


def wb_gamma(rgb):
    y = rgb.mean(axis=2)
    m = (y > np.percentile(y, 20)) & (y < np.percentile(y, 95))
    g = np.array([rgb[..., c][m].mean() for c in range(3)])
    g = g[1] / g
    out = rgb * g
    scale = 0.9 / max(np.percentile(out.mean(axis=2), 99.5), 1e-6)
    return np.clip(out * scale, 0, 1) ** (1 / 2.2), g


def peak_ratios(lum):
    """Block-frequency power against its neighbours. Hann window (no boundary cross); the peaks of period 2 / 4 lie
    off the axes as well as on them: on an axis the reference is the median of the same axis line +-2..6 bins (an
    axis-aligned edge puts a whole line there), off an axis the median of the 9x9 neighbourhood without the centre 3x3.
    Ratio ~1: no lattice; >> 1 (tens): a periodic pattern of that period."""
    n = lum.shape[0]
    win = np.hanning(n)[:, None] * np.hanning(n)[None, :]
    f = np.fft.fft2((lum - lum.mean()) * win)
    p = np.abs(f) ** 2
    out = {}
    for name, (u, v) in {'p2x': (0, n // 2), 'p2y': (n // 2, 0), 'p2d': (n // 2, n // 2), 'p4x': (0, n // 4), 'p4y': (n // 4, 0),
                         'p4d': (n // 4, n // 4), 'p4x2y': (n // 2, n // 4), 'p2x4y': (n // 4, n // 2)}.items():
        if u == 0:
            ref = np.median([p[0, (v + d) % n] for d in list(range(-6, -1)) + list(range(2, 7))])
        elif v == 0:
            ref = np.median([p[(u + d) % n, 0] for d in list(range(-6, -1)) + list(range(2, 7))])
        else:
            ys = [(u + d) % n for d in range(-4, 5)]
            xs = [(v + d) % n for d in range(-4, 5)]
            nb = p[np.ix_(ys, xs)].copy()
            nb[3:6, 3:6] = np.nan
            ref = np.nanmedian(nb)
        out[name] = float(p[u, v] / max(ref, 1e-30))
    return out


def main():
    path, w, h, prefix = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), sys.argv[4]
    rgb = load(path, w, h)
    disp, g = wb_gamma(rgb)
    print('wb gains', np.round(g, 3), 'max', float(rgb.max()))
    Image.fromarray((disp[::4, ::4] * 255 + 0.5).astype(np.uint8)).save(prefix + '_s.jpg', quality=92)
    crops = [tuple(int(v) for v in c.split(',')) for c in sys.argv[5:]] or [(w // 2 - 256, h // 2 - 256, 512)]
    for i, (x, y, s) in enumerate(crops):
        c = disp[y:y + s, x:x + s]
        Image.fromarray((c * 255 + 0.5).astype(np.uint8)).save(f'{prefix}_c{i}.png')
        lum = rgb[y:y + s, x:x + s].mean(axis=2) ** (1 / 2.2)
        r = peak_ratios(lum)
        chroma = (rgb[y:y + s, x:x + s] * g)
        chroma = chroma / np.maximum(chroma.mean(axis=2, keepdims=True), 1e-6)
        rc = peak_ratios(chroma[..., 0] - chroma[..., 1])
        bc = peak_ratios(chroma[..., 2] - chroma[..., 1])
        print(f'crop {i} ({x},{y},{s}) luma ' + ' '.join(f'{k}={v:.1f}' for k, v in r.items()))
        print(f'         R-G ' + ' '.join(f'{k}={v:.1f}' for k, v in rc.items()) + ' | B-G ' + ' '.join(f'{k}={v:.1f}' for k, v in bc.items()))


if __name__ == '__main__':
    main()
