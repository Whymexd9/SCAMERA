#!/usr/bin/env python3
"""P62 Mochi (GCam 11 PhotometricMerge) on the GPU: the shipped programs kHybMochiStats / kHybMochiBlur / kHybMochiApply of
app/src/main/cpp/scam-hybrid.h, wired as HybridGpu::mochi does (one band), on synthetic base / bracketed pairs.
research/mochi/MOCHI_IMPL.md.

Scene: a smooth colour gradient (RAW 10-bit, black 64, RGGB), the bracketed frame at t = 2 with a known photometric offset
per RAW phase (own normalised units). Checks:
- the offset is removed: the corrected frame equals the ideal t x base within 1 DN (interior), also through a translation
  homography (the frame shifted by (4, 2) px);
- sites at or above the clip threshold (0.95) are passed through bit-exactly, and no corrected site reaches the merge's
  clip level;
- an offset with |c_rggb|^2 > 500 (10-bit DN) is zeroed (GCam), the frame is unchanged;
- a moving object (content that is not in the base) does not drive the correction: its tiles' cells are gated, the
  corrected background around it stays within 1 DN, the correction stays within GCam's +-20 DN everywhere;
- dark tiles (base SNR < 5) get the correction scaled by SNRWeight(snr, 5).
Usage: check_hybrid_mochi.py
"""
from pathlib import Path
import re
import sys
import numpy as np
import moderngl

ROOT = Path(__file__).resolve().parents[1]
SRC = (ROOT / 'app/src/main/cpp/scam-hybrid.h').read_text(encoding='utf-8')

try:
    ctx = moderngl.create_standalone_context(backend='egl', require=430)
except Exception:
    ctx = moderngl.create_standalone_context(require=430)


def shader(name):
    m = re.search(r'static const char\* ' + name + r'=R"\((.*?)\)";', SRC, re.S)
    body = m.group(1).replace('#version 310 es', '#version 430', 1)
    return ctx.compute_shader(body)


STATS, BLUR, APPLY = shader('kHybMochiStats'), shader('kHybMochiBlur'), shader('kHybMochiApply')
W, H = 256, 192
BLACK, WHITE = 64.0, 1023.0
RANGE = WHITE - BLACK
NX, NY = (W + 15) // 16, (H + 15) // 16
CLIP_LEVEL = 0.98


def setu(prog, name, value):
    if name in prog:
        prog[name].value = value


def mochi(base, alt, t, h=(1, 0, 0, 0, 1, 0, 0, 0), noise=(1e-5, 1e-7, 1e-5, 1e-7)):
    """base, alt: uint16 H x W. Returns (corrected alt, tiles, info, final)."""
    bb = ctx.buffer(base.astype('<u2').tobytes())
    ab = ctx.buffer(alt.astype('<u2').tobytes())
    tiles = ctx.buffer(reserve=NX * NY * 16)
    info = ctx.buffer(reserve=NX * NY * 16)
    final = ctx.buffer(reserve=NX * NY * 16)
    out = ctx.buffer(reserve=W * H * 2)
    bb.bind_to_storage_buffer(0); ab.bind_to_storage_buffer(1); tiles.bind_to_storage_buffer(2)
    info.bind_to_storage_buffer(3); final.bind_to_storage_buffer(4); out.bind_to_storage_buffer(5)
    for p in (STATS, BLUR, APPLY):
        setu(p, 'size', (W, H)); setu(p, 'tileCount', (NX, NY))
        setu(p, 'black', (BLACK,) * 4); setu(p, 'inv', (1.0 / RANGE,) * 4); setu(p, 'clipAt', 0.95)
    setu(STATS, 'altRows', (0, H)); setu(STATS, 'baseRows', (0, H)); setu(STATS, 'tileRows', (0, NY))
    setu(STATS, 'greens', (1, 2)); setu(STATS, 'hA', tuple(h[:4])); setu(STATS, 'hB', tuple(h[4:]))
    setu(STATS, 'noise', noise); setu(STATS, 'expo', (t, 1.0 / t)); setu(STATS, 'bound', 22.4 / 1023.0)
    STATS.run((NX + 7) // 8, (NY + 7) // 8, 1)
    ctx.memory_barrier()
    BLUR.run((NX + 7) // 8, (NY + 7) // 8, 1)
    ctx.memory_barrier()
    setu(APPLY, 'altRows', (0, H)); setu(APPLY, 'applyRows', (0, H)); setu(APPLY, 'capLevel', max(CLIP_LEVEL - 0.005, 0.95))
    APPLY.run((W // 2 + 7) // 8, (H + 7) // 8, 1)
    ctx.memory_barrier()
    res = np.frombuffer(out.read(), '<u2').reshape(H, W).astype(np.float64)
    tl = np.frombuffer(tiles.read(), '<f4').reshape(NY, NX, 4)
    inf = np.frombuffer(info.read(), '<f4').reshape(NY, NX, 4)
    fin = np.frombuffer(final.read(), '<f4').reshape(NY, NX, 4)
    for b in (bb, ab, tiles, info, final, out):
        b.release()
    return res, tl, inf, fin


def scene(scale=1.0):
    y, x = np.mgrid[0:H, 0:W].astype(np.float64)
    gain = np.array([[0.9, 1.0], [1.0, 0.7]])  # R G / G B
    s = 0.04 + 0.16 * (x / W) + 0.08 * (y / H) + 0.03 * np.sin(x / 23.0) * np.cos(y / 17.0)
    return s * gain[(y % 2).astype(int), (x % 2).astype(int)] * scale


def raw(norm):
    return np.clip(np.floor(BLACK + norm * RANGE + 0.5), 0, 65535)


def phase_map(v):
    y, x = np.mgrid[0:H, 0:W]
    return np.asarray(v)[(y % 2) * 2 + (x % 2)]


fails = []


def check(cond, msg):
    print(('PASS ' if cond else 'FAIL ') + msg)
    if not cond:
        fails.append(msg)


T = 2.0
INNER = (slice(16, H - 16), slice(16, W - 16))
s = scene()
base = raw(s)
offset = np.array([6.0, -4.0, -3.0, 9.0]) / 1023.0  # own normalised units (10-bit DN 6 / -4 / -3 / 9)
ideal = raw(s * T)
alt = raw(s * T + phase_map(offset))

# 1. the offset is removed
res, tl, inf, fin = mochi(base, alt, T)
err = np.abs(res - ideal)[INNER]
meas = inf[..., 3] > 0
est = tl[meas].mean(axis=0) * 1023.0
check(np.abs(est + offset * 1023.0).max() < 0.5, f'measured correction {np.round(est, 2)} DN10 = -offset {offset * 1023.0}')
check(err.max() <= 1.0, f'static: corrected frame = ideal within 1 DN (max {err.max():.2f}, before {np.abs(alt - ideal)[INNER].max():.0f})')

# 2. translation (frame content shifted by (4, 2) px: base (x, y) is seen at (x + 4, y + 2) of the frame)
sh = np.roll(np.roll(s, 2, axis=0), 4, axis=1)
alt2, ideal2 = raw(sh * T + phase_map(offset)), raw(sh * T)
res2, *_ = mochi(base, alt2, T, h=(1, 0, 4, 0, 1, 2, 0, 0))
e2 = np.abs(res2 - ideal2)[INNER]
check(e2.max() <= 1.0, f'translation (4, 2) px: corrected frame = ideal within 1 DN (max {e2.max():.2f})')

# 3. clipped sites untouched, nothing pushed to the clip level
s3 = s.copy()
s3[60:100, 100:180] = 0.49  # x 2 = 0.98: clipped in the bracketed frame
alt3 = raw(np.minimum(s3 * T + phase_map(offset), 1.0))
base3 = raw(s3)
res3, *_ = mochi(base3, alt3, T)
clipped = (alt3 - BLACK) / RANGE >= 0.95
check(np.array_equal(res3[clipped], alt3[clipped]), f'{int(clipped.sum())} clipped sites passed through bit-exactly')
check(((res3[~clipped] - BLACK) / RANGE).max() < CLIP_LEVEL, 'no corrected site reaches the merge clip level')

# 4. |c|^2 > 500: zeroed
big = np.array([15.0, 15.0, 15.0, 15.0]) / 1023.0
alt4 = raw(s * T + phase_map(big))
res4, tl4, inf4, _ = mochi(base, alt4, T)
check(np.array_equal(res4, alt4) and inf4[..., 2][inf4[..., 3] > 0].min() > 0, 'offset |c|^2 = 900 > 500 zeroed: frame unchanged')

# 5. moving object: a bright patch only in the bracketed frame
s5 = s * T + phase_map(offset)
s5[80:120, 64:112] += 0.25
alt5 = raw(s5)
res5, tl5, inf5, fin5 = mochi(base, alt5, T)
bg = np.ones((H, W), bool); bg[64:136, 48:128] = False
bg_in = bg[INNER]
e5 = np.abs(res5 - ideal)[INNER][bg_in]
check(e5.max() <= 1.0, f'moving object: background corrected within 1 DN (max {e5.max():.2f})')
check(np.abs(fin5).max() * 1023.0 <= 20.0 + 1e-3, f'correction within GCam +-20 DN everywhere (max {np.abs(fin5).max() * 1023.0:.2f})')
obj_tiles = inf5[5:7, 4:7]
check(obj_tiles[..., 1].max() < 64, f'object tiles gated (accepted cells {obj_tiles[..., 1].min():.0f}..{obj_tiles[..., 1].max():.0f} of 64)')

# 6. SNR weight: dark scene with a heavy noise model
sd = scene(0.02)
based, altd = raw(sd), raw(sd * T + phase_map(offset))
_, _, infd, find = mochi(based, altd, T, noise=(2e-4, 4e-6, 2e-4, 4e-6))
wd = infd[..., 0][infd[..., 3] > 0]
check(wd.max() < 1.0 and wd.min() > 0.0, f'dark tiles: SNR weight {wd.min():.2f}..{wd.max():.2f} (< 1)')
cd = np.abs(find[2:-2, 2:-2, 0]).mean() * 1023.0
check(cd < 6.0 * wd.mean() + 0.5, f'dark tiles: correction scaled by the SNR weight ({cd:.2f} DN10 of 6)')

if fails:
    print(f'{len(fails)} check(s) failed')
    sys.exit(1)
print('all checks passed')
