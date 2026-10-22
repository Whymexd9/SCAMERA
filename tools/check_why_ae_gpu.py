#!/usr/bin/env python3
"""P145: the WHY auto exposure statistics on the GPU (assets/shaders/why/aestats.glsl, WhyAeGpu) against WhyAe.stats.

The compute shader runs on the desktop GPU (moderngl, GL 4.3) over synthetic whyLow images (RGBA16F like the real one:
log-normal scene, highlights up to 3.5, negatives, NaN, +-Inf, sizes that are no multiple of the 64-pixel tile) for the
three meterings, and is compared with a float32 numpy copy of the two CPU loops of WhyAe.stats:
- the maximum key decodes to the CPU's maximum exactly;
- both histograms hold the exact total weight, and at most 0.01 % of the weight sits in another bin (a GPU may fuse the
  luma's multiply-add and move a value that lies on a bin edge);
- the sums of luma and log luma agree to 1e-5 (relative).
"""
import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
SHADER = ROOT / 'app/src/main/assets/shaders/why/aestats.glsl'
FAILURES = []
TILE = 64


def check(cond, msg):
    if not cond:
        FAILURES.append(msg)
        print('FAIL: ' + msg)


def source(pass_no):
    text = SHADER.read_text(encoding='utf-8')
    text = text.replace('#define PASS 1', '#define PASS %d' % pass_no, 1)
    text = text.replace('\nLAYOUT\n', '\nlayout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;\n', 1)
    return '#version 430\n' + text


def f32(v):
    return np.float32(v)


def cpu_stats(img, mode):
    """WhyAe.stats in float32 as Java computes it (no fused multiply-add)."""
    rr, gg, bb = (img[..., c].astype(np.float32) for c in range(3))
    finite = np.isfinite(img[..., :3])
    vals = img[..., :3][finite]
    gmax = max(np.float32(-1000.0), vals.max()) if vals.size else np.float32(-1000.0)
    clip = min(float(gmax), 4.0) if gmax > f32(1.05) else 1.0
    inv = np.float32(1.0 / clip)
    with np.errstate(invalid='ignore', over='ignore'):
        yv = (gg * f32(0.7152) + rr * f32(0.2126)) + bb * f32(0.0722)
        nan_m = np.isnan(rr) | np.isnan(gg) | np.isnan(bb) | np.isnan(yv)
        mv = np.maximum(np.maximum(np.maximum(gg, bb), rr), yv)
        mv = np.where(nan_m, f32(0), mv).astype(np.float32)
        yv = np.where(np.isnan(yv), f32(0), yv).astype(np.float32)
    h, w = rr.shape
    ys, xs = np.mgrid[0:h, 0:w].astype(np.float32)
    half_w, half_h = f32(w * 0.5), f32(h * 0.5)
    dx = (xs - half_w) / half_w
    dy = (ys - half_h) / half_h
    d2 = (dx * dx + dy * dy).astype(np.float32)
    if mode == 2:
        wt = np.where(d2 < f32(0.04), 10, np.where(d2 < f32(0.16), 2, 0))
    elif mode == 1:
        wt = np.where(d2 < f32(0.25), 4, np.where(d2 < f32(0.5625), 2, 1))
    else:
        wt = np.ones_like(d2, dtype=np.int64)
    with np.errstate(invalid='ignore', over='ignore'):
        iy = (np.clip(yv * inv, f32(0), f32(1)) * f32(4095.99)).astype(np.int64)
        im = (np.clip(mv * inv, f32(0), f32(1)) * f32(4095.99)).astype(np.int64)
    hy = np.bincount(iy.ravel(), weights=wt.ravel(), minlength=4096)
    hm = np.bincount(im.ravel(), weights=wt.ravel(), minlength=4096)
    # P159: the max channel above the ceiling in 48 log2 bins (WhyAe.tailBin: 12 an octave)
    with np.errstate(invalid='ignore', over='ignore', divide='ignore'):
        ratio = (mv * inv).astype(np.float32)
        above = ratio > f32(1)
        k = np.clip((np.log2(np.where(above, ratio, f32(1))).astype(np.float32) * f32(12)).astype(np.int64), 0, 47)
    tail = np.bincount(k[above].ravel(), weights=wt[above].ravel(), minlength=48)
    nz = wt != 0
    with np.errstate(invalid='ignore', over='ignore'):
        sum_y = float(np.sum(yv[nz].astype(np.float64) * wt[nz]))
        sum_log = float(np.sum(np.log(np.maximum(yv[nz], f32(1e-4))).astype(np.float32).astype(np.float64) * wt[nz]))
    return gmax, inv, hy, hm, sum_y, sum_log, float(wt.sum()), tail


def rel(a, b):
    """Relative difference; equal infinities or both NaN (+Inf and -Inf pixels: the order of the sum decides) count as equal."""
    if not np.isfinite(b):
        return 0.0 if a == b or (np.isnan(a) and np.isnan(b)) else 1.0
    return abs(a - b) / max(abs(b), 1e-9)


def decode(key):
    key = int(key) & 0xffffffff
    if key == 0:
        return np.float32(-1000.0)
    bits = key & 0x7fffffff if key & 0x80000000 else (~key) & 0xffffffff
    return max(np.frombuffer(np.uint32(bits).tobytes(), np.float32)[0], np.float32(-1000.0))


def scene(h, w, seed, bright=True, specials=True):
    rng = np.random.default_rng(seed)
    base = np.exp(rng.normal(np.log(0.12), 1.3, (h, w, 1))).astype(np.float32)
    tint = rng.uniform(0.7, 1.3, (h, w, 3)).astype(np.float32)
    img = np.concatenate([base * tint, np.ones((h, w, 1), np.float32)], axis=2)
    if bright:
        img[h // 4:h // 4 + 40, w // 3:w // 3 + 60, :3] = rng.uniform(1.2, 3.5, (40, 60, 3))
    else:
        img[..., :3] = np.minimum(img[..., :3], 1.0)
    img[rng.random((h, w)) < 0.002, 0] = -0.01                 # signed merge noise in the shadows
    if specials:
        img[5, 7, 1] = np.nan
        img[9, 3, :3] = np.inf
        img[11, 13, 2] = -np.inf
        img[h - 2, w - 3, 0] = np.nan
    return img.astype(np.float16)


def main():
    try:
        import moderngl
    except ImportError:
        print('moderngl missing: WHY AE GPU check skipped')
        return 0
    try:
        ctx = moderngl.create_standalone_context(backend='egl', require=430)
    except Exception:
        ctx = moderngl.create_standalone_context(require=430)
    pass1 = ctx.compute_shader(source(1))
    pass2 = ctx.compute_shader(source(2))
    cases = [(750, 1000, 0, True, True), (750, 1000, 1, True, True), (750, 1000, 2, True, True),
             (512, 640, 0, False, False), (1536, 2048, 0, True, False)]
    print('WHY AE statistics, GPU vs WhyAe.stats:')
    for k, (h, w, mode, bright, specials) in enumerate(cases):
        img = scene(h, w, 7 + k, bright, specials)
        tex = ctx.texture((w, h), 4, img.tobytes(), dtype='f2')
        tex.filter = (moderngl.NEAREST, moderngl.NEAREST)
        tex.use(0)
        gx, gy = (w + TILE - 1) // TILE, (h + TILE - 1) // TILE
        maxbuf = ctx.buffer(reserve=4)
        maxbuf.write(np.zeros(1, np.uint32).tobytes())
        maxbuf.bind_to_storage_buffer(1)
        pass1['whyLow'] = 0
        pass1['sizeU'] = (w, h)
        pass1.run(gx, gy, 1)
        ctx.finish()
        key = np.frombuffer(maxbuf.read(), np.uint32)[0]
        g_gmax = decode(key)
        clip = min(float(g_gmax), 4.0) if g_gmax > np.float32(1.05) else 1.0
        inv = np.float32(1.0 / clip)
        hyb, hmb = ctx.buffer(reserve=4096 * 4), ctx.buffer(reserve=4096 * 4)
        hyb.write(np.zeros(4096, np.uint32).tobytes())
        hmb.write(np.zeros(4096, np.uint32).tobytes())
        sums = ctx.buffer(reserve=8 * gx * gy)
        hyb.bind_to_storage_buffer(2)
        hmb.bind_to_storage_buffer(3)
        sums.bind_to_storage_buffer(4)
        tailb = ctx.buffer(reserve=48 * 4)
        tailb.write(np.zeros(48, np.uint32).tobytes())
        tailb.bind_to_storage_buffer(5)
        pass2['whyLow'] = 0
        pass2['sizeU'] = (w, h)
        pass2['invClipU'] = float(inv)
        pass2['modeU'] = mode
        pass2.run(gx, gy, 1)
        ctx.finish()
        hy = np.frombuffer(hyb.read(), np.uint32).astype(np.float64)
        hm = np.frombuffer(hmb.read(), np.uint32).astype(np.float64)
        s = np.frombuffer(sums.read(), np.float32).astype(np.float64)
        sum_y, sum_log = s[0::2].sum(), s[1::2].sum()
        c_gmax, c_inv, c_hy, c_hm, c_sy, c_sl, c_tot, c_tail = cpu_stats(img.astype(np.float32), mode)
        tail = np.frombuffer(tailb.read(), np.uint32).astype(np.float64)
        # a pixel exactly at the ceiling may fall either side (the GPU fuses the luma's multiply-adds)
        check(abs(tail.sum() - c_tail.sum()) <= 2 + 1e-3 * c_tail.sum(), 'tail weight %d vs %d' % (tail.sum(), c_tail.sum()))
        moved_t = np.abs(tail - c_tail).sum() / 2 / max(c_tail.sum(), 1)
        check(moved_t <= 1e-3, 'tail: %.4f %% of its weight in another bin' % (moved_t * 100))
        label = '%dx%d metering %d%s' % (w, h, mode, '' if bright else ' (no highlights)')
        check(g_gmax == c_gmax, '%s: max %r vs CPU %r' % (label, g_gmax, c_gmax))
        check(inv == c_inv, '%s: 1/clip differs' % label)
        moved_y = np.abs(hy - c_hy).sum() / 2 / c_tot
        moved_m = np.abs(hm - c_hm).sum() / 2 / c_tot
        check(hy.sum() == hm.sum(), '%s: the histograms hold different weights' % label)
        if mode == 0:
            check(hy.sum() == c_tot, '%s: weight %d instead of %d' % (label, hy.sum(), c_tot))
        else:
            check(abs(hy.sum() - c_tot) <= c_tot * 1e-3, '%s: weight %d vs %d' % (label, hy.sum(), c_tot))
        check(moved_y <= 1e-4 and moved_m <= 1e-4, '%s: %.5f %% / %.5f %% of the weight in another bin'
              % (label, moved_y * 100, moved_m * 100))
        ry, rl = rel(sum_y, c_sy), rel(sum_log, c_sl)
        check(ry < 1e-5 and rl < 1e-5, '%s: sums differ (%.2e, %.2e)' % (label, ry, rl))
        print('  %-34s max %.4f  bins moved %.5f %% / %.5f %%  sums %.1e / %.1e' % (label, float(g_gmax), moved_y * 100,
                                                                                   moved_m * 100, ry, rl))
        for b in (tex, maxbuf, hyb, hmb, sums, tailb):
            b.release()
    if FAILURES:
        print('WHY AE GPU check FAILED (%d)' % len(FAILURES))
        return 1
    print('WHY AE GPU check PASS')
    return 0


if __name__ == '__main__':
    sys.exit(main())
