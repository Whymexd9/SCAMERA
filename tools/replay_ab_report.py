#!/usr/bin/env python3
"""Report of a tools/replay_hybrid_ab.py run: <out dir> [truth dir].

For every burst: per variant the luma detail (rms of the fine / mid band of the display luma on the textured and the flat
quarter of the reference variant), the colour high band on the textured quarter, the share of pixels that changed against the
reference variant; for a synthetic burst with <truth dir>/<burst>.truth.npz (tools/replay_synth_burst.py) also the error
against the truth (luma / chroma rms over the mean level) on the static area, the moving object and its border, and the gain of
the truth's high band. Writes <out>/report.txt and <out>/ab_<burst>.jpg (every variant side by side, display-rendered).
"""
import glob
import os
import sys
from collections import defaultdict

import numpy as np
from PIL import Image, ImageDraw
from scipy.ndimage import binary_erosion, gaussian_filter

KY = np.array([0.2126, 0.7152, 0.0722])


def main():
    d = sys.argv[1]
    td = sys.argv[2] if len(sys.argv) > 2 else None
    runs = defaultdict(dict)
    for f in sorted(glob.glob(os.path.join(d, '*.npz'))):
        b, v = os.path.basename(f)[:-4].split('__')
        runs[b][v] = np.load(f)['rgb'].astype(np.float64)
    lines = []
    for b, vs in runs.items():
        ref_name = 'base' if 'base' in vs else next(iter(vs))
        ref = vs[ref_name]
        wb = np.median(ref.reshape(-1, 3), 0)
        wb = wb[1] / np.maximum(wb, 1e-9)
        Y = (ref * wb) @ KY
        gain = 0.18 / max(np.median(Y), 1e-9)
        g = gaussian_filter(np.hypot(*np.gradient(gaussian_filter(Y, 2))), 4)
        tex, flat = g > np.percentile(g, 75), g < np.percentile(g, 25)
        truth = None
        if td and os.path.exists(os.path.join(td, b + '.truth.npz')):
            t = np.load(os.path.join(td, b + '.truth.npz'))
            T, obj = t['rgb'].astype(np.float64), t['obj']
            inner = binary_erosion(obj, iterations=6)
            stat = binary_erosion(~obj, iterations=6)
            stat[:8], stat[-8:], stat[:, :8], stat[:, -8:] = False, False, False, False
            truth = (T, {'static': stat, 'moving': inner, 'border': ~(inner | stat)})
        lines.append('== %s %dx%d (reference %s)' % (b, ref.shape[1], ref.shape[0], ref_name))
        for v, a in vs.items():
            y = np.maximum((a * wb) @ KY * gain, 0) ** (1 / 2.2)
            b1, b2 = y - gaussian_filter(y, 0.7), gaussian_filter(y, 0.7) - gaussian_filter(y, 1.6)
            c = a * wb * gain
            cu, cv = c[..., 2] - c[..., 1], c[..., 0] - c[..., 1]
            chf = np.sqrt(np.mean((cu - gaussian_filter(cu, 1.2))[tex] ** 2 + (cv - gaussian_filter(cv, 1.2))[tex] ** 2))
            dd = np.abs(a - ref).max(-1) * gain
            s = '  %-6s tex fine %.4f mid %.4f | flat fine %.4f mid %.4f | colour hf %.4f | changed %.2f%%' % (
                v, np.sqrt(np.mean(b1[tex] ** 2)), np.sqrt(np.mean(b2[tex] ** 2)), np.sqrt(np.mean(b1[flat] ** 2)),
                np.sqrt(np.mean(b2[flat] ** 2)), chf, 100 * np.mean(dd > 0.004))
            if truth:
                T, regions = truth
                stat = regions['static']
                k = np.array([np.sum(a[..., i][stat] * T[..., i][stat]) / max(np.sum(a[..., i][stat] ** 2), 1e-12) for i in range(3)])
                A = a * k
                E = A - T
                cE = np.stack([E[..., 0] - E[..., 1], E[..., 2] - E[..., 1]], -1)
                for name, m in regions.items():
                    lvl = np.mean((T @ KY)[m])
                    s += ' | %s L %.4f C %.4f' % (name, np.sqrt(np.mean((E @ KY)[m] ** 2)) / lvl, np.sqrt(np.mean(np.sum(cE[m] ** 2, -1))) / lvl)
                hpT = T @ KY - gaussian_filter(T @ KY, 1.0)
                hpA = A @ KY - gaussian_filter(A @ KY, 1.0)
                s += ' | HF gain %.3f' % (np.sum(hpT[stat] * hpA[stat]) / np.sum(hpT[stat] ** 2))
            lines.append(s)
        # side by side: the middle half of the frame at 2x
        h, w = ref.shape[:2]
        y0, x0, ch, cw = h // 4, w // 4, h // 2, w // 2
        tiles = []
        for v, a in vs.items():
            x = np.clip(a[y0:y0 + ch, x0:x0 + cw] * wb * gain, 0, 1)
            x = np.where(x <= 0.0031308, 12.92 * x, 1.055 * x ** (1 / 2.4) - 0.055)
            im = Image.fromarray((x * 255 + 0.5).astype(np.uint8)).resize((cw * 2, ch * 2), Image.NEAREST)
            ImageDraw.Draw(im).text((4, 2), b + ' ' + v, fill=(255, 64, 64))
            tiles.append(im)
        canvas = Image.new('RGB', (len(tiles) * (cw * 2 + 6), ch * 2), (16, 16, 16))
        for i, im in enumerate(tiles):
            canvas.paste(im, (i * (cw * 2 + 6), 0))
        canvas.save(os.path.join(d, 'ab_%s.jpg' % b), quality=88)
    open(os.path.join(d, 'report.txt'), 'w').write('\n'.join(lines) + '\n')
    print('\n'.join(lines))


if __name__ == '__main__':
    main()
