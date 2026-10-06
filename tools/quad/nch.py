"""NCH v10/v11 hybrid burst reader (LmcHybridBurst.java layout) and mosaic helpers for the P14 study."""
import numpy as np


def read(path):
    raw = np.memmap(path, dtype=np.uint8, mode='r')
    h = np.frombuffer(raw[:128].tobytes(), dtype='<u4')
    assert h[0] == 0x3143484e and h[1] in (10, 11), h[:2]
    w, ht, cfa, n = int(h[2]), int(h[3]), int(h[4]), int(h[5])
    white = np.frombuffer(raw[24:28].tobytes(), '<f4')[0]
    black = np.frombuffer(raw[28:44].tobytes(), '<f4').copy()
    frames = []
    for i in range(n):
        r = raw[128 + 32 * i:160 + 32 * i].tobytes()
        role, = np.frombuffer(r[0:4], '<u4')
        exp, = np.frombuffer(r[4:8], '<f4')
        iso, = np.frombuffer(r[8:12], '<u4')
        slope, offset, order = np.frombuffer(r[12:24], '<f4')
        frames.append(dict(role=int(role), exposure=float(exp), iso=int(iso), slope=float(slope), offset=float(offset), order=float(order)))
    off = 128 + 32 * n
    planes = np.frombuffer(raw[off:off + n * w * ht * 2], dtype='<u2').reshape(n, ht, w)
    return dict(w=w, h=ht, cfa=cfa, white=float(white), black=black, frames=frames, planes=planes, grid=int(h[13]) if h[1] >= 11 else 1)


def phase_means(img, black, period=8, margin=0.1):
    """Mean of every (y mod period, x mod period) phase over the centre of the frame, black subtracted."""
    ht, w = img.shape
    y0, y1 = int(ht * margin) // period * period, int(ht * (1 - margin)) // period * period
    x0, x1 = int(w * margin) // period * period, int(w * (1 - margin)) // period * period
    c = img[y0:y1, x0:x1].astype(np.float64) - float(np.mean(black))
    return c.reshape((y1 - y0) // period, period, (x1 - x0) // period, period).mean(axis=(0, 2))


def block_fit(m):
    """Residual of the 8x8 phase means against the colour-block models of block 1 (Bayer), 2 (Quad), 4 (Tetra)."""
    out = {}
    py, px = np.mgrid[0:8, 0:8]
    for b in (1, 2, 4):
        cls = ((py // b) & 1) * 2 + ((px // b) & 1)
        pred = np.zeros_like(m)
        for k in range(4):
            pred[cls == k] = m[cls == k].mean()
        res = ((m - pred) ** 2).mean()
        between = ((pred - m.mean()) ** 2).mean()
        out[b] = (res, between)
    return out
