#!/usr/bin/env python3
"""Numpy reference of the LMC hybrid noise reduction (LmcDenoise, shaders lmcdn/*), written from the spec
research/hybrid5/denoise_port.md section 7 (GCam 9.6 LumaDenoise F16 / ChromaDenoise as ArkCam 2.85 runs them), not from
the shaders: exact kD8 / {1,3,3,1} pyramids with edge clamp, BilateralFilter3x3 (as research/hybrid5/ref/denoise/bf_sim.py,
edge clamp instead of wrap), BilateralFilterChroma3x3, Up2 = bilinear x2 at p/2 - 0.25, revert with kVarMultLUT.
Also the host arithmetic of LmcDenoiseTables (tiers, interpolation, statistics reduction, SNR), in float64.
"""
import numpy as np

KD8 = np.array([-3, -7, 17, 57, 57, 17, -7, -3], np.float64) / 128.0
H4 = np.array([1, 3, 3, 1], np.float64) / 8.0
M = np.array([[0.2126, 0.7152, 0.0721996],
              [-0.0999069 / 0.615, -0.336094 / 0.615, 0.436001 / 0.615],
              [0.614998 / 0.615, -0.558606 / 0.615, -0.0563914 / 0.615]])
MINV = np.linalg.inv(M)


def to_yuv(rgb):
    return rgb @ M.T


def to_rgb(yuv):
    return yuv @ MINV.T


# ---------------------------------------------------------------- pyramids
def _down1(a, k, off, axis):
    n = a.shape[axis]
    m = (n + 1) // 2
    q = np.arange(m)
    out = 0
    for i, w in enumerate(k):
        idx = np.clip(2 * q + i - off, 0, n - 1)
        out = out + w * np.take(a, idx, axis=axis)
    return out


def down_kd8(a):
    return _down1(_down1(a, KD8, 3, 0), KD8, 3, 1)


def down_h4(a):
    return _down1(_down1(a, H4, 1, 0), H4, 1, 1)


def _up1(c, n, axis):
    p = np.arange(n)
    b = p >> 1
    o = np.where(p & 1, 1, -1)
    nb = np.clip(b + o, 0, c.shape[axis] - 1)
    b = np.minimum(b, c.shape[axis] - 1)
    return 0.75 * np.take(c, b, axis=axis) + 0.25 * np.take(c, nb, axis=axis)


def up2(c, shape):
    """GCam UpsampleDelta: bilinear x2, coarse position p/2 - 0.25 (= weights 0.75/0.25 per axis)."""
    return _up1(_up1(c, shape[0], 0), shape[1], 1)


def lp121(a):
    h, w = a.shape[:2]
    out = 0
    for j, wj in ((-1, .25), (0, .5), (1, .25)):
        for i, wi in ((-1, .25), (0, .5), (1, .25)):
            out = out + wi * wj * a[np.clip(np.arange(h) + j, 0, h - 1)][:, np.clip(np.arange(w) + i, 0, w - 1)]
    return out


def shifted(a, dx, dy):
    h, w = a.shape[:2]
    return a[np.clip(np.arange(h) + dy, 0, h - 1)][:, np.clip(np.arange(w) + dx, 0, w - 1)]


# ---------------------------------------------------------------- filters
def bf3x3(img, s, sigma, thr):
    """GCam BilateralFilter3x3 (luma): 3x3 neighbours at stride s, weighted 3x3 patch |difference|, strict D<=sigma,
    soft D<=2 sigma, 1-2-1 weights (centre 4), strict mean if strict weights > thr (outlier*16) else soft mean."""
    P = {(i, j): shifted(img, i * s, j * s) for i in range(-2, 3) for j in range(-2, 3)}
    c = img
    sw = [np.full_like(img, 4.0), np.full_like(img, 4.0)]
    sm = [4.0 * c, 4.0 * c]
    for ry in (-1, 0, 1):
        for rx in (-1, 0, 1):
            if rx == 0 and ry == 0:
                continue
            D = 0
            for py in (-1, 0, 1):
                for px in (-1, 0, 1):
                    wgt = (0.5 if py == 0 else 0.25) * (0.5 if px == 0 else 0.25)
                    D = D + wgt * np.abs(P[(px + rx, py + ry)] - P[(px, py)])
            ew = 2.0 if (rx == 0 or ry == 0) else 1.0
            n = P[(rx, ry)]
            wa = np.where(D <= sigma, ew, 0.0)
            wb = np.where(0.5 * D <= sigma, ew, 0.0)
            sw[0] = sw[0] + wa; sm[0] = sm[0] + wa * n
            sw[1] = sw[1] + wb; sm[1] = sm[1] + wb * n
    return np.where(sw[0] > thr, sm[0] / sw[0], sm[1] / sw[1])


def bfc3x3(x, s, sigma2, uvs, thr):
    """GCam BilateralFilterChroma3x3 on YUV x (h, w, 3): Y unchanged."""
    c = x
    a0 = np.zeros(x.shape[:2] + (2,)); a1 = np.zeros_like(a0)
    s0 = np.zeros(x.shape[:2]); s1 = np.zeros_like(s0)
    for j in (-1, 0, 1):
        for i in (-1, 0, 1):
            n = shifted(x, i * s, j * s)
            d = n - c
            d2 = d[..., 0] ** 2 + (uvs[0] * d[..., 1]) ** 2 + (uvs[1] * d[..., 2]) ** 2
            w = float((2 - abs(i)) * (2 - abs(j)))
            m0 = d2 <= sigma2
            m1 = 0.25 * d2 <= sigma2
            a0 += np.where(m0, w, 0.0)[..., None] * n[..., 1:]; s0 += np.where(m0, w, 0.0)
            a1 += np.where(m1, w, 0.0)[..., None] * n[..., 1:]; s1 += np.where(m1, w, 0.0)
    uv = np.where((s0 > thr)[..., None], a0 / np.maximum(s0, 1e-30)[..., None], a1 / np.maximum(s1, 1e-6)[..., None])
    return np.concatenate([c[..., :1], uv], axis=-1)


def kvar(f):
    return np.where(f > 8, 0.35355339, np.where(f > 4, 0.5, np.where(f > 2, 0.70710678, 1.0)))


# ---------------------------------------------------------------- host arithmetic (LmcDenoiseTables)
LUMA_SNR = [5., 10., 20., 40., 80.]
LUMA_SABRE = [True, False, True, True, False]
LUMA_STRENGTH = [[1, 3, 0, 0, 0], [2, 1, .2, 0, 0], [1, 3, 0, 0, 0], [1, 3, 0, 0, 0], [3, .75, .38, 0, 0]]
LUMA_REVERT = [[9, 9, .08, .12, 0], [.75, .7, .7, .0625, 0], [1, 1, .1, .14, 0], [1, 1, .1, .1, 0], [.95, .95, .95, .1, 0]]
LUMA_OUTLIER = [[.6, .4, .2447, .2383, .3473], [1, 1, 1, .3731, .4647], [.7, .4, .322, .508, .582],
                [.6, .6, .4725, .363, .0777], [1, 1, 1, .363, .0777]]
LUMA_STOCK_STRENGTH = [[1.2, 1.1, 1.2, 1.1, 1.0], [1.5, 1.32, .8, .6, .7], [1.2, 1.0, 1.0, .7, .1], [1.2, .7, .401, .557, .379],
                       [1.0, .95, .36, .4, .28]]
LUMA_STOCK_REVERT = [[.15, .1, .08, .12, 0], [.1, .1, .075, .0625, 0], [.15, .1, .1, .14, 0], [.2, .1, .1, .1, 0], [.15, .1, .1, .1, 0]]
CHROMA_SNR = [1., 5., 10., 20.]
CHROMA_STRENGTH = [[5, 5, 5, 4, 4], [5, 5, 5, 1, 2], [1, 1, 1, 1, 1.5], [.5, .5, 0, 0, 0]]
CHROMA_OUTLIER = [[0, 5, 5, 4, 4]] * 4


def default_config(**kw):
    c = dict(luma_mult=1., sabre_mult=1., gid14_mult=1., chroma_mult=1., chroma_floor=2.75, revert_mult=1., revert_max=2.,
             coarse_stock=.5)
    c.update(kw)
    return c


def pick(keys, snr):
    order = sorted(range(len(keys)), key=lambda i: keys[i])
    upper = 0
    while upper < len(order) and not keys[order[upper]] > snr:
        upper += 1
    if upper == 0:
        return order[0], order[0], 0.0
    if upper == len(order):
        return order[-1], order[-1], 0.0
    lo, hi = order[upper - 1], order[upper]
    return lo, hi, (snr - keys[lo]) / (keys[hi] - keys[lo])


def luma_params(cfg, snr):
    t = []
    for i in range(5):
        mult = cfg['luma_mult'] * (cfg['sabre_mult'] if LUMA_SABRE[i] else cfg['gid14_mult'])
        tier = []
        for b in range(5):
            s, r = LUMA_STRENGTH[i][b], LUMA_REVERT[i][b]
            if b >= 2 and cfg['coarse_stock'] > 0:
                s = s + (LUMA_STOCK_STRENGTH[i][b] - s) * cfg['coarse_stock']
                r = r + (LUMA_STOCK_REVERT[i][b] - r) * cfg['coarse_stock']
            tier.append([max(0.0, s * mult), r, LUMA_OUTLIER[i][b]])
        t.append(tier)
    lo, hi, w = pick(LUMA_SNR, snr)
    out = [[t[lo][b][f] + (t[hi][b][f] - t[lo][b][f]) * w for f in range(3)] for b in range(5)]
    for b in range(5):
        out[b][1] = max(0.0, min(cfg['revert_max'], out[b][1] * cfg['revert_mult']))
    return out, (lo, hi, w)


def chroma_params(cfg, snr):
    lo, hi, w = pick(CHROMA_SNR, snr)
    out = []
    for b in range(5):
        s = CHROMA_STRENGTH[lo][b] + (CHROMA_STRENGTH[hi][b] - CHROMA_STRENGTH[lo][b]) * w
        if b <= 2:
            s = max(s, cfg['chroma_floor'])
        out.append([max(0.0, s * cfg['chroma_mult']), CHROMA_OUTLIER[lo][b] + (CHROMA_OUTLIER[hi][b] - CHROMA_OUTLIER[lo][b]) * w])
    return out, (lo, hi, w)


def snr_estimate(mu, g01, sG, rG, rho_s, rho_r):
    var = max(g01, 1e-6) * 0.5 * (rho_s * sG * max(mu, 0) + rho_r * rG)
    return mu / np.sqrt(var)


def block_stats(level, block, model):
    """Same statistics as lmcdn/stats (float64): per 16x16 block mean Y, texture T (variance of the 4x4 sub-block
    means) and, per channel and stride s = 1, 2, g = v / 0.945 / model_Y(mean Y), v = median over the 16 sub-blocks of
    the mean squared difference x(p + s e) - x(p) minus the block's linear gradient (plane fit of the sub-block means),
    horizontal and vertical pairs inside one sub-block."""
    x = level if level.ndim == 3 else np.stack([level, np.zeros_like(level), np.zeros_like(level)], -1)
    h, w = x.shape[:2]
    by, bx = h // block, w // block
    sub = max(block // 4, 3)
    c = np.array([-1.5, -.5, .5, 1.5])
    out = np.zeros((by, bx, 8))
    for j in range(by):
        for i in range(bx):
            blk = x[j * block:(j + 1) * block, i * block:(i + 1) * block]
            S = blk.reshape(4, sub, 4, sub, 3).transpose(0, 2, 1, 3, 4)   # sj, si, j, i, ch
            M = S.mean(axis=(2, 3))                                       # sj, si, ch
            meanY = M[..., 0].mean()
            var = max(model[0] * max(meanY, 0) + model[1], 1e-12)
            t = M[..., 0].var() * sub * sub / var
            sx = (M * c[None, :, None]).sum((0, 1)) / 20 / sub
            sy = (M * c[:, None, None]).sum((0, 1)) / 20 / sub
            g = []
            for s in (1, 2):
                dh = S[:, :, :, s:] - S[:, :, :, :-s] - s * sx
                dv = S[:, :, s:, :] - S[:, :, :-s, :] - s * sy
                v = ((dh ** 2).sum((2, 3)) + (dv ** 2).sum((2, 3))) / (2 * sub * (sub - s))
                g.append(np.median(v.reshape(16, 3), axis=0) / 0.945 / var)
            out[j, i] = [meanY, t, g[0][0], g[1][0], g[0][1], g[1][1], g[0][2], g[1][2]]
    return out.reshape(-1, 8)


def reduce_stats(blocks, clip=0.8):
    """LmcDenoiseTables.reduce."""
    ok = (blocks[:, 0] > 1e-5) & (blocks[:, 0] < clip) & (blocks[:, 2] > 0) & np.isfinite(blocks).all(1)
    v = blocks[ok]
    if len(v) < 16:
        return None
    v = v[np.argsort(v[:, 1], kind='stable')]
    m = max(16, len(v) // 4)
    sel = v[:m]
    out = [float(np.median(sel[:, 2 + k])) for k in range(6)]
    byY = sel[np.argsort(sel[:, 0], kind='stable')]
    d = max(8, m // 2)
    dark = byY[:d]
    uvs = []
    for c in range(2):
        for s in range(2):
            r = dark[:, 2 + s] / np.maximum(dark[:, 4 + 2 * c + s], 1e-6)
            uvs.append(float(np.sqrt(max(np.median(r), 1e-4))))
    return out + uvs + [m]


# ---------------------------------------------------------------- the whole noise reduction
def denoise(x0, G, UVS, luma, chroma, model, rho, strmap=None, out_rgb=True, base_rgb=None, dark_fade=False,
            dark_chroma=(0.0, 0.0), dark_noise=(0.0, 0.0)):
    """x0: level-0 YUV (h, w, 3). G: {'Y': [[g1, g2] per level], 'C': [...]}, UVS[L] = (uvsU1, uvsU2, uvsV1, uvsV2).
    luma[b] = (strength, revert, outlier), chroma[b] = (strength, outlier). model = (sY, rY), rho = (rho_s, rho_r).
    strmap: callable(level shape) -> f per pixel or None. Returns RGB (out_rgb) or the YUV change against yuv(base_rgb)."""
    sY, rY = model
    Y = [x0[..., 0]]
    for L in range(1, 5):
        Y.append(down_kd8(Y[-1]))
    f_of = (lambda shp: strmap(shp)) if strmap is not None else (lambda shp: np.ones(shp))
    # luma, levels 3 .. 0
    yin = x0[..., 0]
    den_next = None  # Den of the next coarser level
    for L in range(3, -1, -1):
        s0, s1 = luma[L + 1][0], luma[L][0]
        f = f_of(Y[L].shape)
        if den_next is None:
            recon = Y[L].copy()
            lf = None
        else:
            delta = den_next - Y[L + 1]
            lf = None
            if L == 0:
                lf = lp121(den_next)
                delta = delta - lf
            recon = Y[L] + up2(delta, Y[L].shape)
        d0 = recon
        if s0 > 0:
            k0 = s0 * s0 / 2.0
            yy = yin if L == 0 else recon
            sig = np.sqrt(np.maximum(f * k0 * G['Y'][L][1] * (rho[0] * sY * np.maximum(yy, 0) + rho[1] * rY), 0))
            d0 = bf3x3(recon, 2, sig, luma[L + 1][2] * 16)
        d1 = d0
        if s1 > 0:
            k1 = s1 * s1
            yy = yin if L == 0 else d0
            sig = np.sqrt(np.maximum(f * k1 * G['Y'][L][0] * (rho[0] * sY * np.maximum(yy, 0) + rho[1] * rY), 0))
            d1 = bf3x3(d0, 1, sig, luma[L][2] * 16)
        rf = luma[L][1] * kvar(f)
        den = d1 + rf * (recon - d1)
        if L == 0 and lf is not None:
            den = den + up2(lf, Y[0].shape)
        den_next = den
    xp = np.concatenate([den_next[..., None], x0[..., 1:]], -1)
    # chroma on (Y', U, V), levels 3 .. 0
    X = [xp]
    for L in range(1, 4):
        X.append(down_h4(X[-1]))
    den_c = None
    for L in range(3, -1, -1):
        s0, s1 = chroma[L + 1][0], chroma[L][0]
        f = f_of(X[L].shape[:2])
        recon = X[L].copy()
        if den_c is not None:
            recon[..., 1:] += up2(den_c[..., 1:] - X[L + 1][..., 1:], X[L].shape[:2])
        c0 = recon
        if s0 > 0:
            k0 = s0 * s0 / 2.0
            sig2 = f * k0 * G['C'][L][1] * (rho[0] * sY * np.maximum(recon[..., 0], 0) + rho[1] * rY)
            c0 = bfc3x3(recon, 2, sig2, (UVS[L][1], UVS[L][3]), float(int(chroma[L + 1][1])))
        c1 = c0
        if s1 > 0:
            k1 = s1 * s1
            sig2 = f * k1 * G['C'][L][0] * (rho[0] * sY * np.maximum(c0[..., 0], 0) + rho[1] * rY)
            c1 = bfc3x3(c0, 1, sig2, (UVS[L][0], UVS[L][2]), float(int(chroma[L][1])))
        den_c = c1
    if out_rgb:
        rgb = to_rgb(den_c)
        if dark_fade:
            t = np.clip((rgb.mean(-1) - 0.0008) / (0.003 - 0.0008), 0, 1)
            t = t * t * (3 - 2 * t)
            if dark_chroma[1] > 0:
                # a colour clearly stronger than a black-level tint is kept (lmcdn/cbf darkChromaU)
                # the floor follows the colour noise left (darkNoiseU: floor^2 = x mean + y), the ramp ends at max(hi, 2 floor)
                m = rgb.mean(-1)
                dev = np.linalg.norm(rgb - m[..., None], axis=-1)
                lo = np.maximum(dark_chroma[0], np.sqrt(np.maximum(dark_noise[0] * np.maximum(m, 0) + dark_noise[1], 0)))
                hi = np.maximum(dark_chroma[1], 2 * lo)
                k = np.clip((dev - lo) / (hi - lo), 0, 1)
                t = np.maximum(t, k * k * (3 - 2 * k))
            rgb = to_rgb(np.concatenate([den_c[..., :1], den_c[..., 1:] * t[..., None]], -1))
        return np.maximum(rgb, 0)
    return den_c - to_yuv(base_rgb)


def down2x(rgb2x):
    """Sensor-scale RGB from the 2x grid: Y through kD8, U and V through {1,3,3,1} (lmcdn/down2x)."""
    yuv = to_yuv(rgb2x)
    y = down_kd8(yuv[..., 0])
    uv = np.stack([down_h4(yuv[..., 1]), down_h4(yuv[..., 2])], -1)
    return to_rgb(np.concatenate([y[..., None], uv], -1))


def final2x(rgb2x, delta, base=None, keep=1.0):
    """Laplacian re-assembly on the 2x grid; the 2x-only colour residual uv(RGB_2x) - Up2(uv(base)) is kept at `keep`."""
    out = rgb2x + to_rgb(up2(delta, rgb2x.shape[:2]))
    if base is not None and keep < 1.0:
        res = to_yuv(rgb2x) - to_yuv(up2(base, rgb2x.shape[:2]))
        res[..., 0] = 0
        out = out - (1.0 - keep) * to_rgb(res)
    return np.maximum(out, 0)
