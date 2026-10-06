"""Zipper scores of a hybrid result of gen_zipper_burst.py against the base frame's scene (P18).

eval_zipper.py <prefix> <out.f32> <grid 1|2> [crop png]
- edge PSNR: luma error on the pixels within 3 px of a leaf / branch edge;
- zipper: rms of the period-2 component of that error ([1 -2 1]/4 in x and y), relative to the luma there: the alternating
  bright / dark dots and the 1-2 px staircase along edges;
- false colour on the edges: mean |R/G - R/G (scene)| + |B/G - B/G (scene)|.
On the 2x grid the result is averaged 2x2 to the sensor grid first (the scene is on the sensor grid).
"""
import sys
import numpy as np
from scipy import ndimage
from PIL import Image

W, H = 4096, 3072


def main():
    prefix, path, grid = sys.argv[1], sys.argv[2], int(sys.argv[3])
    gt = np.load(prefix + '_gt.npy')
    mask = np.load(prefix + '_mask.npy')
    out = np.fromfile(path, '<f4', count=W * H * 3 * grid * grid).reshape(H * grid, W * grid, 3)
    if grid == 2:
        out = out.reshape(H, 2, W, 2, 3).mean(axis=(1, 3))
    m = mask.copy()
    m[:16] = m[-16:] = False
    m[:, :16] = m[:, -16:] = False
    lum = lambda a: a @ np.array([0.25, 0.5, 0.25], np.float32)
    e = lum(out) - lum(gt)
    psnr = 10 * np.log10(0.8 ** 2 / np.mean(e[m] ** 2))
    k = np.array([1, -2, 1], np.float32) / 4
    zx = ndimage.convolve1d(e, k, axis=1)
    zy = ndimage.convolve1d(e, k, axis=0)
    zip_ = np.sqrt(np.mean((zx[m] ** 2 + zy[m] ** 2) / 2)) / np.mean(lum(gt)[m])
    eps = 0.01
    rg = lambda a: (a[..., 0] + eps) / (a[..., 1] + eps)
    bg = lambda a: (a[..., 2] + eps) / (a[..., 1] + eps)
    fc = np.mean(np.abs(rg(out) - rg(gt))[m] + np.abs(bg(out) - bg(gt))[m])
    flat = ~ndimage.binary_dilation(mask, iterations=12)
    flat[:16] = flat[-16:] = False
    noise = np.sqrt(np.mean(e[flat] ** 2)) / np.mean(lum(gt)[flat])
    print(f'{path}: edge PSNR {psnr:.2f} dB  zipper {100 * zip_:.3f} %  edge false colour {fc:.4f}  sky noise {100 * noise:.3f} %')
    if len(sys.argv) > 4:
        tiles = []
        for (y, x) in [(1500, 2000), (800, 900), (2200, 3000)]:
            a = np.clip(np.concatenate([gt[y:y + 120, x:x + 180], out[y:y + 120, x:x + 180]], 1), 0, 1) ** (1 / 2.2)
            tiles.append((a * 255 + 0.5).astype(np.uint8))
        Image.fromarray(np.concatenate(tiles, 0)).resize((360 * 4, 360 * 4), Image.NEAREST).save(sys.argv[4])


if __name__ == '__main__':
    main()
