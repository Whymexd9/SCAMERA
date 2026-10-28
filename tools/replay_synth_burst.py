#!/usr/bin/env python3
"""Synthetic hand-held burst with a moving object and its ground truth, for tools/replay_hybrid_ab.py.

Usage: replay_synth_burst.py <scene.png|.jpg|proc[:WxH]> <out.nch> <day|night> [frames] [seed] [plain|bracket|flicker[:A]]
The scene (sRGB; "proc": a generated one, 1536 x 1024 or W x H: coloured 1/f texture, a zone plate, gratings at three angles and
colour edges) becomes linear RGB at 2x the burst size; every frame samples it on an RGGB lattice after a sub-pixel hand
shift (sigma 1.2 px, frame 0 = the base at 0) while an object window (the middle third of the frame) moves a further 2.5 px
per frame to the right; Poisson-Gaussian noise of the chosen level, 10 bit (black 64, white 1023). Writes <out.nch> (NCH v12,
all frames normal, exposure 1) and <out>.truth.npz (the base frame's true linear RGB, the object mask).
P371 roles: "bracket" makes the last six frames three bracketed (x2) and three ultrashort (x1/8) ones and puts a lamp into
the scene (clipped in the normal and bracketed frames, ~0.5 of white in the ultrashort ones), without the moving object (the
merge's Bento / Shasta / Mochi / ultrashort paths); "flicker" adds rolling-shutter lamp flicker to the ultrashort frames (rows
x 1 +- A, A 0.3 by default, a phase of its own per frame), which the plain path's ultrashort row check corrects: the speculative
merge restarts (HybridUsRestart); a larger A (0.45) also fails their GPU homographies (the translation fallback).
"""
import struct
import sys

import numpy as np
from PIL import Image
from scipy.ndimage import map_coordinates

LEVELS = {'day': (6.0e-5, 2.0e-7), 'night': (3.0e-3, 2.0e-5)}  # slope, offset of the normalised signal (variance)


def proc_scene(W=1536, H=1024, seed=3):
    """Linear RGB test scene: 1/f colour texture (left), zone plate (top right), gratings 3-6 px at 0 / 30 / 60 degrees and
    colour edges (bottom right)."""
    rng = np.random.default_rng(seed)
    fy, fx = np.meshgrid(np.fft.fftfreq(H), np.fft.fftfreq(W), indexing='ij')
    f = np.hypot(fx, fy)
    f[0, 0] = 1
    tex = []
    for c in range(3):
        spec = (rng.normal(size=(H, W)) + 1j * rng.normal(size=(H, W))) / f ** 1.1
        t = np.real(np.fft.ifft2(spec))
        tex.append((t - t.mean()) / t.std())
    mix = np.array([[0.7, 0.25, 0.05], [0.2, 0.7, 0.1], [0.1, 0.3, 0.6]])
    rgb = np.clip(0.35 + 0.12 * np.einsum('ij,jhw->hwi', mix, np.array(tex)), 0.02, 1)
    y, x = np.mgrid[0:H, 0:W].astype(np.float64)
    zp = (x > W / 2) & (y < H / 2)
    r2 = ((x - 0.75 * W) ** 2 + (y - 0.25 * H) ** 2)
    rgb[zp] = (0.45 + 0.4 * np.cos(np.pi * r2 / (0.9 * W)))[zp][:, None] * np.array([1.0, 0.95, 0.9])
    for k, (ang, per) in enumerate(((0, 6), (30, 4.5), (60, 3.5))):
        band = (x > W / 2) & (y >= H / 2 + k * H / 8) & (y < H / 2 + (k + 1) * H / 8)
        a = np.radians(ang)
        g = 0.5 + 0.4 * np.sign(np.sin(2 * np.pi * (x * np.cos(a) + y * np.sin(a)) / (2 * per)))
        rgb[band] = g[band][:, None] * np.array([0.9, 0.9, 0.85])
    edges = (x > W / 2) & (y >= 7 * H / 8)
    cols = np.array([[0.8, 0.1, 0.1], [0.1, 0.7, 0.2], [0.15, 0.2, 0.85], [0.9, 0.85, 0.2], [0.85, 0.85, 0.85], [0.05, 0.05, 0.05]])
    idx = ((x - W / 2) // 26).astype(int) % len(cols)
    rgb[edges] = cols[idx[edges]]
    return rgb


def main():
    src, out, level = sys.argv[1], sys.argv[2], sys.argv[3]
    n = int(sys.argv[4]) if len(sys.argv) > 4 else 12
    rng = np.random.default_rng(int(sys.argv[5]) if len(sys.argv) > 5 else 7)
    mode = sys.argv[6] if len(sys.argv) > 6 else 'plain'
    amplitude = 0.3
    if mode.startswith('flicker:'):
        mode, amplitude = 'flicker', float(mode.split(':', 1)[1])
    if mode not in ('plain', 'bracket', 'flicker'):
        raise SystemExit('mode: plain, bracket or flicker[:A]')
    if mode != 'plain' and n < 8:
        raise SystemExit('bracket / flicker: at least 8 frames (2 normal, 3 bracketed, 3 ultrashort)')
    if src.startswith('proc'):
        size = src.split(':', 1)[1].lower().split('x') if ':' in src else None
        lin = proc_scene(int(size[0]), int(size[1])) if size else proc_scene()
    else:
        img = np.asarray(Image.open(src).convert('RGB')).astype(np.float64) / 255
        lin = np.where(img <= 0.04045, img / 12.92, ((img + 0.055) / 1.055) ** 2.4)
    H2, W2 = lin.shape[:2]
    h, w = (H2 // 2) & ~1, (W2 // 2) & ~1
    slope, offset = LEVELS[level]
    scale = 0.6 if level == 'day' else 0.12  # the night scene is dark
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float64)
    obj = (xx >= w / 3) & (xx < 2 * w / 3) & (yy >= h / 4) & (yy < 3 * h / 4)
    cfa = np.array([[0, 1], [1, 2]])  # RGGB: colour index per (y & 1, x & 1)
    ch = cfa[yy.astype(int) & 1, xx.astype(int) & 1]
    # P371: roles 1 normal, 3 bracketed, 5 ultrashort and their exposures (the merge's units: the normal frame's)
    roles, exposures = [1] * n, [1.0] * n
    if mode != 'plain':
        roles[n - 6:] = [3, 3, 3, 5, 5, 5]
        exposures[n - 6:] = [2.0, 2.0, 2.0, 0.125, 0.125, 0.125]
        H2, W2 = lin.shape[:2]
        ly, lx = np.mgrid[0:H2, 0:W2]
        lamp = (lx - 0.7 * W2) ** 2 + (ly - 0.3 * H2) ** 2 < (0.06 * min(W2, H2)) ** 2
        lin = lin.copy()
        lin[lamp] = 4.0 / scale  # x4 white in a normal frame: ~0.5 in an ultrashort one
    frames, truth = [], None
    for i in range(n):
        dx, dy = (0.0, 0.0) if i == 0 else tuple(rng.normal(0, 1.2, 2))
        ox = dx + (np.where(obj, 2.5 * i, 0.0) if mode == 'plain' else 0.0)
        # scene coordinates (2x): sample centre of burst pixel (x, y) shifted by the hand (and the object)
        sx, sy = 2 * (xx + ox) + 0.5, 2 * (yy + dy) + 0.5
        rgb = np.stack([map_coordinates(lin[..., c], [sy, sx], order=3, mode='reflect') for c in range(3)], -1)
        rgb = np.clip(rgb * scale * exposures[i], 0, 1 if mode == 'plain' else 8)
        if mode == 'flicker' and roles[i] == 5:
            # rolling-shutter flicker of the lamp-lit scene: the rows' level swings with the mains (a phase per frame)
            rgb = rgb * (1 + amplitude * np.sin(2 * np.pi * yy / (h / 3) + rng.uniform(0, 2 * np.pi)))[..., None]
        rgb = np.clip(rgb, 0, 1)
        if i == 0:
            truth = rgb.copy()
        v = np.take_along_axis(rgb, ch[..., None], -1)[..., 0]
        v = v + rng.normal(0, 1, v.shape) * np.sqrt(slope * v + offset)
        frames.append(np.clip(np.round(64 + v * (1023 - 64)), 0, 1023).astype('<u2'))
    head = bytearray(128)
    struct.pack_into('<6If4fI', head, 0, 0x3143484e, 12, w, h, 0, n, 1023.0, 64.0, 64.0, 64.0, 64.0, 0)
    struct.pack_into('<3I', head, 48, 0, 1, 1)  # base 0, output grid 1, colour block 1 (Bayer)
    first = (128 + 32 * n + 4095) // 4096
    pages = (w * h * 2 + 4095) // 4096
    with open(out, 'wb') as f:
        f.write(head)
        for i in range(n):
            f.write(struct.pack('<IfIfffII', roles[i], exposures[i], 400 if level == 'day' else 6400, slope, offset, float(i * 33), 0,
                                first + i * pages))
        for i, a in enumerate(frames):
            f.seek((first + i * pages) * 4096)
            f.write(a.tobytes())
        f.truncate((first + n * pages) * 4096)
    np.savez_compressed(out + '.truth.npz', rgb=truth.astype(np.float32), obj=obj)
    print(out, w, 'x', h, n, 'frames', level, mode)


if __name__ == '__main__':
    main()
