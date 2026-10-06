"""Synthetic colour-block mosaic bursts (P14 / P15 checks) from one linear RGB scene with known shifts.

gen_mosaic_burst.py <scene.npy|-> <out prefix> [frames] [seed]
Writes <prefix>_b1.nch, _b2.nch, _b4.nch (plain Bayer, Quad, Tetra RGGB; NCH v11, header block 0 = the worker measures it),
<prefix>_gt.npy (the base frame's scene after the lens blur, linear RGB float32) and <prefix>_shifts.txt.
Without a scene file ("-") the scene is synthetic: a neutral and a colour zone plate, slanted black/white and colour edges,
fine bars and a smooth gradient, 4096 x 3072. Each frame is the scene moved by a random sub-pixel translation (+-4 px), blurred
by the lens (gaussian sigma 0.6 px), sampled by the CFA and given shot + read noise of the model var = slope v + offset (units of
white), quantised at black 64 / white 1023.
"""
import sys
import numpy as np
from scipy import ndimage

W, H = 4096, 3072
BLACK, WHITE = 64.0, 1023.0
SLOPE, OFFSET = 4e-5, 6e-7


def synthetic_scene():
    y, x = np.mgrid[0:H, 0:W].astype(np.float32)
    img = np.zeros((H, W, 3), np.float32)
    # background gradient
    img[...] = (0.15 + 0.25 * x[..., None] / W) * np.array([0.9, 1.0, 1.1], np.float32)
    # neutral zone plate (top left 1400 x 1400): frequency rises to Nyquist at the corner
    zx, zy = x[:1400, :1400] - 0, y[:1400, :1400] - 0
    zp = 0.5 + 0.45 * np.cos(np.pi * (zx * zx + zy * zy) / 1400.0 / 2)
    img[:1400, :1400] = 0.05 + 0.6 * zp[..., None]
    # colour zone plate (top, 1400..2800): red/cyan
    zx2 = x[:1400, 1400:2800] - 1400
    zp2 = 0.5 + 0.45 * np.cos(np.pi * (zx2 * zx2 + zy[:1400, :1400] ** 2) / 1400.0 / 2)
    img[:1400, 1400:2800, 0] = 0.05 + 0.6 * zp2
    img[:1400, 1400:2800, 1] = 0.05 + 0.6 * (1 - zp2)
    img[:1400, 1400:2800, 2] = 0.05 + 0.6 * (1 - zp2)
    # slanted edges (right top): black/white, red/green, blue/yellow
    for k, (a, b) in enumerate([((0.03, 0.03, 0.03), (0.7, 0.7, 0.7)), ((0.6, 0.08, 0.05), (0.08, 0.5, 0.1)), ((0.05, 0.1, 0.6), (0.6, 0.6, 0.08))]):
        y0 = 100 + k * 430
        xx, yy = x[y0:y0 + 400, 2900:4000] - 2900, y[y0:y0 + 400, 2900:4000] - y0
        m = (yy - 200) * np.cos(0.08) - (xx - 550) * np.sin(0.08) > 0
        img[y0:y0 + 400, 2900:4000] = np.where(m[..., None], np.array(b, np.float32), np.array(a, np.float32))
    # fine bars of 1, 2, 3 px period (bottom left), neutral and coloured
    for k, p in enumerate([2, 3, 4, 6]):
        y0 = 1600 + k * 300
        bars = ((x[y0:y0 + 250, 100:2000] // (p / 2)) % 2).astype(np.float32)
        img[y0:y0 + 250, 100:2000] = (0.08 + 0.55 * bars)[..., None]
        bars2 = ((y[y0:y0 + 250, 2100:4000] // (p / 2)) % 2).astype(np.float32)
        img[y0:y0 + 250, 2100:4000, 0] = 0.08 + 0.55 * bars2
        img[y0:y0 + 250, 2100:4000, 1] = 0.08 + 0.3 * bars2
        img[y0:y0 + 250, 2100:4000, 2] = 0.08
    return img


def cfa(img, block):
    yy, xx = np.mgrid[0:H, 0:W]
    p = (((yy // block) & 1) << 1) | ((xx // block) & 1)
    c = np.where(p == 0, 0, np.where(p == 3, 2, 1))
    return np.take_along_axis(img, c[..., None], axis=2)[..., 0]


def write_nch(path, planes, frames_meta, cfa_order=0):
    n = len(planes)
    h = np.zeros(32, '<u4')
    h[0], h[1], h[2], h[3], h[4], h[5] = 0x3143484e, 11, W, H, cfa_order, n
    head = bytearray(h.tobytes())
    head[24:28] = np.float32(WHITE).tobytes()
    head[28:44] = np.full(4, BLACK, np.float32).tobytes()
    head[44:48] = np.uint32(0).tobytes()                       # flags
    head[48:52] = np.uint32(0).tobytes()                       # base index
    head[52:56] = np.uint32(1).tobytes()                       # grid 1
    head[56:60] = np.uint32(0).tobytes()                       # colour block: measured by the worker
    table = bytearray()
    for m in frames_meta:
        r = bytearray(32)
        r[0:4] = np.uint32(1).tobytes()
        r[4:8] = np.float32(1).tobytes()
        r[8:12] = np.uint32(100).tobytes()
        r[12:16] = np.float32(SLOPE).tobytes()
        r[16:20] = np.float32(OFFSET).tobytes()
        r[20:24] = np.float32(m['order']).tobytes()
        table += r
    with open(path, 'wb') as f:
        f.write(head)
        f.write(table)
        for p in planes:
            f.write(p.astype('<u2').tobytes())


def main():
    scene_path, prefix = sys.argv[1], sys.argv[2]
    n = int(sys.argv[3]) if len(sys.argv) > 3 else 8
    rng = np.random.default_rng(int(sys.argv[4]) if len(sys.argv) > 4 else 1)
    scene = synthetic_scene() if scene_path == '-' else np.load(scene_path).astype(np.float32)
    shifts = [(0.0, 0.0)] + [tuple(rng.uniform(-4, 4, 2)) for _ in range(n - 1)]
    frames = []
    for i, (dx, dy) in enumerate(shifts):
        f = np.stack([ndimage.shift(ndimage.gaussian_filter(scene[..., c], 0.6), (dy, dx), order=3, mode='reflect') for c in range(3)], -1)
        frames.append(np.clip(f, 0, 1))
        if i == 0:
            np.save(prefix + '_gt.npy', frames[0])
    with open(prefix + '_shifts.txt', 'w') as f:
        for dx, dy in shifts:
            f.write(f'{dx:.4f} {dy:.4f}\n')
    meta = [{'order': -33.3 * i} for i in range(n)]
    for block in (1, 2, 4):
        planes = []
        for i, fr in enumerate(frames):
            v = cfa(fr, block)
            v = v + rng.standard_normal(v.shape).astype(np.float32) * np.sqrt(SLOPE * v + OFFSET)
            planes.append(np.clip(np.round(BLACK + v * (WHITE - BLACK)), 0, WHITE))
        write_nch(f'{prefix}_b{block}.nch', planes, meta)
        print('wrote', f'{prefix}_b{block}.nch')


if __name__ == '__main__':
    main()
