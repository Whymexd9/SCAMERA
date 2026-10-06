"""Synthetic Bayer RAWs with a known lateral chromatic aberration (P28 parity / recovery tests).

gen_synth.py <out dir>
Scene: wires, branches, window frames, discs, letters-like bars and a few coloured patches on a sky gradient, drawn at 3x and
box-downsampled (anti-aliased), blurred by a sigma 0.6 px lens PSF. Lateral CA: the red and blue images are scaled about the
frame centre, the displacement d(p) = (p - c) (k1 + k2 r^2) with r over the half diagonal (R +1.6 px, B -0.9 px at the corner on
the 12 MP frame), resampled cubic. Then the CFA mosaic, Poisson-Gaussian noise, 12-bit codes over black 64. Each case also gets
<name>.truth.r16 (same mosaic without CA and noise) and <name>.json (the model). Files: .r16 = "R16C", int32 w, h, cfa (sensor
phase of red), float black[4], float white, uint16 w*h.
"""
import json
import os
import sys

import numpy as np
from PIL import Image, ImageDraw
from scipy import ndimage

BLACK, WHITE = 64.0, 4095.0


def write_r16(path, codes, cfa):
    h, w = codes.shape
    with open(path, 'wb') as f:
        f.write(b'R16C')
        f.write(np.array([w, h, cfa], '<i4').tobytes())
        f.write(np.array([BLACK] * 4 + [WHITE], '<f4').tobytes())
        f.write(codes.astype('<u2').tobytes())


def scene(w, h, seed):
    """Linear RGB scene (h, w, 3) in 0..1."""
    rng = np.random.default_rng(seed)
    s = 3
    W, H = w * s, h * s
    yy = np.linspace(0, 1, H, dtype=np.float32)[:, None, None]
    sky = np.concatenate([0.55 + 0.25 * yy, 0.60 + 0.25 * yy, 0.75 + 0.15 * yy], -1)  # (H, 1, 3)
    img = Image.fromarray(np.ascontiguousarray(np.broadcast_to(np.clip(sky * 255, 0, 255).astype(np.uint8), (H, W, 3))))
    d = ImageDraw.Draw(img)
    def col(neutral=True):
        if neutral or rng.random() < 0.8:
            v = int(rng.choice([8, 20, 40, 200, 235, 250]))
            return (v, v, v)
        return tuple(int(x) for x in rng.integers(20, 240, 3))
    n = int(w * h / 40000)
    for _ in range(n):  # wires and branches
        x0, y0 = rng.integers(0, W), rng.integers(0, H)
        a = rng.uniform(0, np.pi)
        L = rng.uniform(0.05, 0.4) * W
        d.line([(x0, y0), (x0 + L * np.cos(a), y0 + L * np.sin(a))], fill=col(), width=int(rng.integers(2, 12)))
    for _ in range(n // 2):  # window frames
        x0, y0 = rng.integers(0, W), rng.integers(0, H)
        ww, hh = rng.integers(30, 400), rng.integers(30, 400)
        d.rectangle([x0, y0, x0 + ww, y0 + hh], outline=col(), width=int(rng.integers(3, 15)), fill=col(False) if rng.random() < 0.3 else None)
    for _ in range(n // 3):  # discs
        x0, y0, r = rng.integers(0, W), rng.integers(0, H), rng.integers(10, 120)
        d.ellipse([x0 - r, y0 - r, x0 + r, y0 + r], fill=col(False))
    for _ in range(n // 2):  # bar groups (text-like)
        x0, y0 = rng.integers(0, W), rng.integers(0, H)
        c = col()
        for k in range(int(rng.integers(3, 9))):
            d.rectangle([x0 + k * 18, y0, x0 + k * 18 + 8, y0 + 60], fill=c)
    u8 = np.asarray(img)
    a = np.empty((h, w, 3), np.float32)
    for r0 in range(0, h, 128):  # anti-aliased (box over s x s), in row chunks
        r1 = min(h, r0 + 128)
        a[r0:r1] = u8[r0 * s:r1 * s].reshape(r1 - r0, s, w, s, 3).astype(np.float32).mean(axis=(1, 3)) / 255.0
    del u8, img, d
    a = np.stack([ndimage.gaussian_filter(a[..., c], 0.6) for c in range(3)], -1)
    return a ** 2.2  # linear


def with_ca(img, k):
    """Red / blue scaled about the centre: displacement (p - c)(k1 + k2 r^2), r over the half diagonal."""
    h, w, _ = img.shape
    cy, cx = (h - 1) / 2, (w - 1) / 2
    half = np.hypot(cx, cy)
    y, x = np.mgrid[0:h, 0:w].astype(np.float32)
    r2 = ((x - cx) ** 2 + (y - cy) ** 2) / half ** 2
    out = img.copy()
    for c, (k1, k2) in ((0, k['R']), (2, k['B'])):
        s = k1 + k2 * r2
        # the colour plane shows scene point q at p = q + d(q); sample the scene at q ~ p - d(p)
        out[..., c] = ndimage.map_coordinates(img[..., c], [y - (y - cy) * s, x - (x - cx) * s], order=3, mode='reflect')
    return out


def mosaic(img, cfa):
    h, w, _ = img.shape
    m = np.empty((h, w), np.float32)
    for py in range(2):
        for px in range(2):
            p = (py << 1) | px
            c = 0 if p == cfa else 2 if p == (cfa ^ 3) else 1
            m[py::2, px::2] = img[py::2, px::2, c]
    return m


def codes(m, rng, noise=True):
    v = m * 0.92 * (WHITE - BLACK)
    if noise:
        v = rng.poisson(np.maximum(v, 0) / 1.0).astype(np.float32) + rng.normal(0, 2.0, v.shape).astype(np.float32)
    return np.clip(np.round(v + BLACK), 0, WHITE).astype(np.uint16)


def case(out, name, w, h, cfa, corner_r, corner_b, seed, shift=None):
    rng = np.random.default_rng(seed + 1000)
    img = scene(w, h, seed)
    if shift is not None:
        img = np.stack([ndimage.shift(img[..., c], shift, order=3, mode='reflect') for c in range(3)], -1)
    half = np.hypot((w - 1) / 2, (h - 1) / 2)
    # corner displacement = half (k1 + k2); split 30 / 70 between the linear and the cubic term
    k = {'R': (0.3 * corner_r / half, 0.7 * corner_r / half), 'B': (0.3 * corner_b / half, 0.7 * corner_b / half)}
    ca = with_ca(img, k)
    write_r16(os.path.join(out, name + '.r16'), codes(mosaic(ca, cfa), rng), cfa)
    write_r16(os.path.join(out, name + '.truth.r16'), codes(mosaic(img, cfa), rng, noise=False), cfa)
    with open(os.path.join(out, name + '.json'), 'w') as f:
        json.dump({'w': w, 'h': h, 'cfa': cfa, 'corner_px': {'R': corner_r, 'B': corner_b}, 'k': k, 'seed': seed,
                   'shift': shift}, f)
    print(name, w, h, 'cfa', cfa, 'corner R %+.2f B %+.2f px' % (corner_r, corner_b))


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else 'synth'
    os.makedirs(out, exist_ok=True)
    case(out, 'syn12', 4000, 3000, 0, 1.6, -0.9, 1)
    case(out, 'syn12b', 4000, 3000, 0, 1.6, -0.9, 1, shift=(2.4, -3.3))  # the same scene moved: a second burst frame
    case(out, 'syn_bggr', 2048, 1536, 3, 1.2, 0.8, 2)
    case(out, 'syn_grbg_edge', 1574, 1236, 1, -1.0, 1.4, 3)  # W, H mod 112 in (0, 8): RT's pass-one border fill overruns
    case(out, 'syn_gbrg_big', 3072, 2304, 2, 3.0, -2.5, 4)   # large CA (shifts near the +-3.99 clamp after the fit)
    case(out, 'syn_small', 640, 480, 0, 1.0, -0.6, 5)        # few blocks: the 4-coefficient fit
    case(out, 'syn_tiny', 320, 256, 0, 1.0, -0.6, 6)         # too few blocks: no correction
    case(out, 'syn_none', 2048, 1536, 0, 0.0, 0.0, 7)        # no CA


if __name__ == '__main__':
    main()
