"""P28 base mode on a merged RGB: correctRgb with the base frame's estimate against the CA-free truth (synthetic, noise-free RGB
of a gen_synth case = what a clean merge of that burst gives). Variants: first pass's field, summed passes, one per pass.

check_rgb.py <work dir> <case name, e.g. syn12> [--passes N] [--avoid]
"""
import json
import os
import subprocess
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from run_parity import zig  # noqa: E402
import gen_synth  # noqa: E402


def main():
    work, name = sys.argv[1], sys.argv[2]
    opts = sys.argv[3:]
    synth = os.path.join(work, 'synth')
    meta = json.load(open(os.path.join(synth, name + '.json')))
    w, h = meta['w'], meta['h']
    img = gen_synth.scene(w, h, meta['seed'])
    k = {c: tuple(v) for c, v in meta['k'].items()}
    ca = gen_synth.with_ca(img, k) * 0.92
    truth = img * 0.92
    rgb_path = os.path.join(work, name + '.rgb.f32')
    ca.astype(np.float32).tofile(rgb_path)
    exe = os.path.join(work, 'rgb_check.exe' if os.name == 'nt' else 'rgb_check')
    subprocess.run(zig() + ['-std=c++17', '-O2', os.path.join(HERE, 'rgb_check.cpp'), '-o', exe], check=True)
    prefix = os.path.join(work, name + '.rgbout')
    r = subprocess.run([exe, os.path.join(synth, name + '.r16'), rgb_path, prefix] + opts, capture_output=True, text=True, check=True)
    print(r.stdout.strip())
    y, x = np.mgrid[0:h, 0:w]
    rr = np.hypot(x - (w - 1) / 2, y - (h - 1) / 2) / np.hypot((w - 1) / 2, (h - 1) / 2)
    outer = rr > 0.6
    # edges only (where CA shows): |grad G| of the truth above its 80th percentile in the outer ring
    gy, gx = np.gradient(truth[..., 1])
    grad = np.hypot(gx, gy)
    edge = outer & (grad > np.percentile(grad[outer], 80))

    def rms(a, c, m):
        return float(np.sqrt(np.mean((a[..., c][m] - truth[..., c][m]) ** 2)) * 4031)  # in 12-bit codes over black

    res = {'before': {c: round(rms(ca, i, edge), 2) for c, i in (('R', 0), ('B', 2))}}
    for v in ('first', 'sum', 'seq'):
        out = np.fromfile(prefix + '.' + v + '.f32', np.float32).reshape(h, w, 3)
        res[v] = {c: round(rms(out, i, edge), 2) for c, i in (('R', 0), ('B', 2))}
        res[v]['G_changed'] = int(np.count_nonzero(out[..., 1] != ca[..., 1].astype(np.float32)))
    print(json.dumps(res))


if __name__ == '__main__':
    main()
