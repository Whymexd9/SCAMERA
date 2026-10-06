"""Synthetic wind burst for the P18 zipper study: a static bright sky and moving leaves / thin branches.

gen_zipper_burst.py <out prefix> [frames] [seed] [max leaf motion px]
Writes <prefix>.nch (plain Bayer RGGB, NCH v11) and <prefix>_gt.npy (the base frame's scene after the lens blur) and
<prefix>_mask.npy (pixels within 3 px of a leaf / branch edge in the base frame). The camera is still except a small hand
shake (+-1.5 px, the same for the whole frame); the leaves move by their own random offsets (+-max px) in every frame, as
birch leaves in wind. Scene: sky 0.75 (slightly blue), leaves dark green ellipses at many angles, branches of 1-3 px.
"""
import sys
import numpy as np
from scipy import ndimage

sys.path.insert(0, __file__.rsplit('/', 1)[0] if '/' in __file__ else '.')
import gen_mosaic_burst as g

W, H = g.W, g.H


def leaves_layer(rng, n_leaves=900, n_branches=220):
    """Coverage (0..1) and colour of the leaves, drawn at 4x and box-filtered (antialiased edges)."""
    S = 4
    cov = np.zeros((H, W), np.float32)
    col = np.zeros((H, W, 3), np.float32)
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    leaves = []
    for _ in range(n_leaves):
        cx, cy = rng.uniform(200, W - 200), rng.uniform(200, H - 200)
        a, b = rng.uniform(12, 40), rng.uniform(6, 18)
        th = rng.uniform(0, np.pi)
        c = np.array([rng.uniform(0.05, 0.2), rng.uniform(0.12, 0.3), rng.uniform(0.03, 0.1)], np.float32)
        leaves.append((cx, cy, a, b, th, c))
    branches = []
    for _ in range(n_branches):
        x0, y0 = rng.uniform(100, W - 100), rng.uniform(100, H - 100)
        ang, length, width = rng.uniform(0, np.pi), rng.uniform(80, 400), rng.uniform(0.8, 3.0)
        branches.append((x0, y0, ang, length, width))
    return leaves, branches


def render(leaves, branches, offsets, sky):
    """The scene with every leaf / branch moved by its own offset (supersampled edges)."""
    img = sky.copy()
    for (cx, cy, a, b, th, c), (dx, dy) in zip(leaves, offsets[:len(leaves)]):
        cx += dx; cy += dy
        r = int(a + 3)
        x0, x1, y0, y1 = int(cx - r), int(cx + r + 1), int(cy - r), int(cy + r + 1)
        ys, xs = np.mgrid[y0 * 4:y1 * 4, x0 * 4:x1 * 4].astype(np.float32) / 4 + 0.125
        u = (xs - cx) * np.cos(th) + (ys - cy) * np.sin(th)
        v = -(xs - cx) * np.sin(th) + (ys - cy) * np.cos(th)
        m = ((u / a) ** 2 + (v / b) ** 2 <= 1).astype(np.float32)
        m = m.reshape(y1 - y0, 4, x1 - x0, 4).mean(axis=(1, 3))
        img[y0:y1, x0:x1] = img[y0:y1, x0:x1] * (1 - m[..., None]) + c * m[..., None]
    for (x0, y0, ang, length, width), (dx, dy) in zip(branches, offsets[len(leaves):]):
        x0 += dx; y0 += dy
        x1, y1 = x0 + length * np.cos(ang), y0 + length * np.sin(ang)
        bx0, bx1 = max(0, int(min(x0, x1) - 4)), min(W, int(max(x0, x1) + 5))
        by0, by1 = max(0, int(min(y0, y1) - 4)), min(H, int(max(y0, y1) + 5))
        if bx1 <= bx0 or by1 <= by0:
            continue
        ys, xs = np.mgrid[by0 * 4:by1 * 4, bx0 * 4:bx1 * 4].astype(np.float32) / 4 + 0.125
        t = np.clip(((xs - x0) * (x1 - x0) + (ys - y0) * (y1 - y0)) / (length ** 2), 0, 1)
        d = np.hypot(xs - (x0 + t * (x1 - x0)), ys - (y0 + t * (y1 - y0)))
        m = (d <= width / 2).astype(np.float32).reshape(by1 - by0, 4, bx1 - bx0, 4).mean(axis=(1, 3))
        img[by0:by1, bx0:bx1] = img[by0:by1, bx0:bx1] * (1 - m[..., None]) + np.array([0.06, 0.05, 0.04], np.float32) * m[..., None]
    return img


def main():
    prefix = sys.argv[1]
    n = int(sys.argv[2]) if len(sys.argv) > 2 else 12
    rng = np.random.default_rng(int(sys.argv[3]) if len(sys.argv) > 3 else 3)
    maxm = float(sys.argv[4]) if len(sys.argv) > 4 else 2.0
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    sky = np.stack([0.62 + 0.05 * yy / H, 0.72 + 0.03 * yy / H, 0.80 + 0.0 * yy / H], -1).astype(np.float32)
    leaves, branches = leaves_layer(rng)
    count = len(leaves) + len(branches)
    planes, meta = [], []
    for i in range(n):
        offs = np.zeros((count, 2), np.float32) if i == 0 else rng.uniform(-maxm, maxm, (count, 2)).astype(np.float32)
        scene = render(leaves, branches, offs, sky)
        shake = (0.0, 0.0) if i == 0 else tuple(rng.uniform(-1.5, 1.5, 2))
        f = np.stack([ndimage.shift(ndimage.gaussian_filter(scene[..., c], 0.6), (shake[1], shake[0]), order=3, mode='reflect') for c in range(3)], -1)
        f = np.clip(f, 0, 1)
        if i == 0:
            np.save(prefix + '_gt.npy', f)
            lum = f.mean(axis=2)
            edge = np.hypot(ndimage.sobel(lum, 0), ndimage.sobel(lum, 1)) > 0.2
            np.save(prefix + '_mask.npy', ndimage.binary_dilation(edge, iterations=3))
        v = g.cfa(f, 1)
        v = v + rng.standard_normal(v.shape).astype(np.float32) * np.sqrt(g.SLOPE * v + g.OFFSET)
        planes.append(np.clip(np.round(g.BLACK + v * (g.WHITE - g.BLACK)), 0, g.WHITE))
        meta.append({'order': -33.3 * i})
        print('frame', i, flush=True)
    g.write_nch(prefix + '.nch', planes, meta)
    print('wrote', prefix + '.nch')


if __name__ == '__main__':
    main()
