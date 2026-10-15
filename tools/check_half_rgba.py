#!/usr/bin/env python3
"""P145: the half-float RGB -> RGBA16F repack on the GPU (assets/shaders/scamhdr/halfrgba.glsl, HalfRgbUpload).

The compute shader runs on the desktop GPU (moderngl, GL 4.3) as HalfRgbUpload drives it - bands of rows copied unchanged
into a storage buffer - and the texture is read back as halves:
- all 65536 half patterns (the runtime check of HalfRgbUpload) come back bit for bit, NaN as a NaN, alpha 1.0;
- an image of odd width in bands whose byte count is no multiple of 4 gives the CPU repack (ScamRgb.halfRgbToRgbaRows).
A driver that flushes 16-bit denormals would fail the first case; on the phone HalfRgbUpload then keeps the CPU repack.
"""
import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
SHADER = ROOT / 'app/src/main/assets/shaders/scamhdr/halfrgba.glsl'
FAILURES = []
GL_RGBA16F = 0x881A


def check(cond, msg):
    if not cond:
        FAILURES.append(msg)
        print('FAIL: ' + msg)


def source():
    text = SHADER.read_text(encoding='utf-8')
    text = '\n'.join(l for l in text.splitlines() if not l.startswith('precision'))
    text = text.replace('\nLAYOUT\n', '\nlayout(local_size_x = 16, local_size_y = 16, local_size_z = 1) in;\n', 1)
    return '#version 430\n' + text


def repack(ctx, prog, halves, w, h, band):
    """HalfRgbUpload.run: bands into two alternating buffers, one dispatch per band."""
    import moderngl
    tex = ctx.texture((w, h), 4, dtype='f2')
    tex.filter = (moderngl.NEAREST, moderngl.NEAREST)
    tex.bind_to_image(0, read=False, write=True, format=GL_RGBA16F)
    raw = halves.astype(np.uint16).tobytes()
    row_bytes = w * 6
    capacity = (band * row_bytes + 3) & ~3
    buffers = [ctx.buffer(reserve=capacity), ctx.buffer(reserve=capacity)]
    for k, y0 in enumerate(range(0, h, band)):
        rows = min(band, h - y0)
        buf = buffers[k & 1]
        buf.write(raw[y0 * row_bytes:(y0 + rows) * row_bytes])
        buf.bind_to_storage_buffer(1)
        prog['bandU'] = (w, y0)
        prog['rowsU'] = rows
        prog.run((w + 15) // 16, (rows + 15) // 16, 1)
    ctx.memory_barrier()
    out = np.frombuffer(tex.read(), np.uint16).reshape(h, w, 4).copy()
    for b in buffers:
        b.release()
    tex.release()
    return out


def same(got, want):
    """Bit equality of halves, any NaN for a NaN."""
    nan = ((want & 0x7c00) == 0x7c00) & ((want & 0x03ff) != 0)
    got_nan = ((got & 0x7c00) == 0x7c00) & ((got & 0x03ff) != 0)
    return np.where(nan, got_nan, got == want)


def main():
    try:
        import moderngl
    except ImportError:
        print('moderngl missing: half RGB repack check skipped')
        return 0
    try:
        ctx = moderngl.create_standalone_context(backend='egl', require=430)
    except Exception:
        ctx = moderngl.create_standalone_context(require=430)
    prog = ctx.compute_shader(source())
    print('half RGB -> RGBA16F on the GPU:')
    # all 65536 patterns as the RGB of 128 x 171 pixels (HalfRgbUpload.probe)
    w, h = 128, (65536 + 3 * 128 - 1) // (3 * 128)
    pat = np.zeros(w * h * 3, np.uint16)
    pat[:65536] = np.arange(65536, dtype=np.uint32).astype(np.uint16)
    out = repack(ctx, prog, pat, w, h, h)
    rgb = out[..., :3].reshape(-1)
    ok = same(rgb, pat)
    check(ok.all(), 'patterns: %d of 65536 halves lost (first 0x%04x)' % ((~ok).sum(), int(pat[np.argmin(ok)]) if not ok.all() else 0))
    check((out[..., 3] == 0x3C00).all(), 'patterns: alpha is not 1.0')
    denorm = (pat > 0) & (pat < 0x0400)
    print('  all 65536 halves: %s (denormals %s)' % ('kept' if ok.all() else 'LOST',
                                                      'kept' if ok[denorm].all() else 'flushed by this driver'))
    # an image of odd width in bands of 7 rows (7 * 1001 * 6 bytes: no multiple of 4)
    rng = np.random.default_rng(3)
    w, h = 1001, 37
    img = rng.normal(0.2, 0.3, (h, w, 3)).astype(np.float16)
    img[3, 5, 1] = np.float16(6e-6)      # a denormal
    img[4, 6, 0] = np.float16(-0.0)
    halves = img.view(np.uint16).reshape(-1)
    out = repack(ctx, prog, halves, w, h, 7)
    want = np.concatenate([img.view(np.uint16), np.full((h, w, 1), 0x3C00, np.uint16)], axis=2)
    ok = same(out, want)
    check(ok.all(), 'odd width in bands: %d halves differ' % (~ok).sum())
    print('  1001 x 37 in bands of 7 rows: %s' % ('same as the CPU repack' if ok.all() else 'DIFFERS'))
    if FAILURES:
        print('half RGB repack check FAILED (%d)' % len(FAILURES))
        return 1
    print('half RGB repack check PASS')
    return 0


if __name__ == '__main__':
    sys.exit(main())
