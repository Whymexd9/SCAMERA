"""Crop an NCH v11 / v12 SCAM burst (every frame the same RAW window, even offsets keep the CFA) into a small v12 burst and pack it
for transport (.xz: the frames' 16-bit words as low / high byte planes) for tools/replay_hybrid_ab.py.
Usage: replay_crop_nch.py <burst> <out.nch> <x0> <y0> <w> <h>"""
import lzma
import struct
import sys

import numpy as np


def crop(src, dst, x0, y0, w, h):
    f = open(src, 'rb')
    head = bytearray(f.read(128))
    magic, ver, W, H, cfa, n = struct.unpack('<6I', head[:24])
    assert magic == 0x3143484e and ver in (11, 12), (hex(magic), ver)
    head[4:8] = struct.pack('<I', 12)
    assert x0 % 2 == 0 and y0 % 2 == 0 and w % 2 == 0 and h % 2 == 0 and x0 + w <= W and y0 + h <= H
    table = [bytearray(f.read(32)) for _ in range(n)]
    head[8:16] = struct.pack('<2I', w, h)
    frame_bytes = w * h * 2
    first = (128 + 32 * n + 4095) // 4096
    pages = (frame_bytes + 4095) // 4096
    out = open(dst, 'wb')
    out.write(head)
    frames = []
    for i, t in enumerate(table):
        at = struct.unpack('<I', t[28:32])[0] * 4096 if ver == 12 else 128 + 32 * n + i * W * H * 2  # v11: frames packed
        m = np.memmap(src, dtype='<u2', mode='r', offset=at, shape=(H, W))
        frames.append(np.ascontiguousarray(m[y0:y0 + h, x0:x0 + w]))
        t[28:32] = struct.pack('<I', first + i * pages)
        out.write(t)
    for i, a in enumerate(frames):
        out.seek((first + i * pages) * 4096)
        out.write(a.tobytes())
    out.truncate((first + n * pages) * 4096)
    out.close()
    print(dst, 'frames', n, 'size', w, 'x', h, 'max', max(int(a.max()) for a in frames))


def pack(src, dst):
    """xz of the cropped burst with the frames' 16-bit words split into low / high byte planes (xz finds the 10-bit
    high bytes nearly constant)."""
    d = open(src, 'rb').read()
    head = d[:128]
    n = struct.unpack('<I', head[20:24])[0]
    first = (128 + 32 * n + 4095) // 4096
    body = np.frombuffer(d[first * 4096:], '<u2')
    planes = (body & 0xff).astype(np.uint8).tobytes() + (body >> 8).astype(np.uint8).tobytes()
    open(dst, 'wb').write(lzma.compress(d[:first * 4096] + planes, preset=6))


if __name__ == '__main__':
    src, dst, x0, y0, w, h = sys.argv[1], sys.argv[2], *map(int, sys.argv[3:7])
    crop(src, dst, x0, y0, w, h)
    pack(dst, dst + '.xz')
