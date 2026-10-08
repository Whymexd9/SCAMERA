"""score3.py <label>...: luma PSNR against the ground truth (research/p29/device_out/gt_shift_b<b>.npy) on the moving rectangles
(RECTS of mkmove.py) and on static regions; full band and low-passed (Gaussian 1.5 px: the lattice of 4x4 / 2x2 blocks and the
moving object's blur, without the aliased zone-plate tops)."""
import sys, os
import numpy as np
D = r'C:/Users/MECHREVO/AppData/Local/Temp/claude/C--Users-MECHREVO-Downloads-x200u/bf400803-7f8d-4a20-b0ba-c4377f5d5f71/scratchpad/mvdata'
G = r'C:/Users/MECHREVO/Downloads/x200u/SCAMERA-PC/research/p29/device_out/gt_shift_b%d.npy'
W, H = 4096, 3072
REG = {'mvZone': (slice(340, 1070), slice(340, 1330)), 'mvBars': (slice(1620, 2100), slice(2130, 3120)),
       'mvBarsN': (slice(2420, 2770), slice(340, 1330)),
       'stZoneC': (slice(340, 1070), slice(1500, 2700)), 'stEdges': (slice(150, 1300), slice(3000, 3900)),
       'stBarsN': (slice(1620, 2300), slice(340, 1330))}
def gauss(z, s=1.5):
    k = np.exp(-0.5 * (np.arange(-5, 6) / s) ** 2); k /= k.sum()
    z = np.apply_along_axis(lambda v: np.convolve(np.pad(v, 5, mode='reflect'), k, 'valid'), 0, z)
    return np.apply_along_axis(lambda v: np.convolve(np.pad(v, 5, mode='reflect'), k, 'valid'), 1, z)
gts = {}
for lab in sys.argv[1:]:
    b = 4 if 'b4' in lab else 2
    if b not in gts: gts[b] = np.load(G % b) @ np.array([0.25, 0.5, 0.25], np.float32)
    a = np.fromfile(os.path.join(D, 'out_%s.f32' % lab), '<f4', count=W * H * 3).reshape(H, W, 3) @ np.array([0.25, 0.5, 0.25], np.float32)
    g = gts[b]
    out = [lab.ljust(10)]
    for name, (ys, xs) in REG.items():
        x, y = a[ys, xs], g[ys, xs]
        p = 10 * np.log10(0.49 / max(np.mean((x - y) ** 2), 1e-12))
        pl = 10 * np.log10(0.49 / max(np.mean((gauss(x) - gauss(y)) ** 2), 1e-12))
        out.append('%s %.2f/%.2f' % (name, p, pl))
    print(' | '.join(out))
