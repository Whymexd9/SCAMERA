"""Python reference of ArkCam 1.23 process_luma_fp16 (libfc_suppressor.so 0x76538) for the X8U 2.85 main-camera PRO settings.
Kernels follow fc_cl_source.cl (gaussian_blur_h/v, bilateral_blur, gf_downsample, box_blur_float2_h/v, gf_calc_ab, luma_compose,
rl_div, rl_mult, rl_blend, airy_blur); launch order/args = emulation (emu_plf.py). Buffers that are `half` in OpenCL are rounded to fp16."""
import math, numpy as np

H16 = lambda a: a.astype(np.float16).astype(np.float32)

X8U = dict(usm_radius=0.5, usm_amount=1.0, usm_thresh=50, sobel_thresh=50, sobel_amount=0.0,
           bil_radius=0.5, bil_amount=1.0, bil_color=10, protect_shadows=0, protect_highlights=100,
           halo_control=100, rl_halo_control=100, rl_halo_margin=0.15, rl_halo_macro=0.4,
           gf_radius=8, gf_eps=0.01, gf_lc=0.25, edge_thinning=0.0, film_grain=0.0,
           rl=[(2, 1.0, 1.0, 3, 0.0), (1, 0.0, 0.0, 0, 0.0), (0, 0.5, 1.0, 3, 0.0)])  # (kernel, rad, amount, iters, damping)


def pad(a, r):
    return np.pad(a, r, mode='edge')


def sep_conv(a, k1d, out_half=True):
    r = len(k1d) // 2
    p = np.pad(a, ((0, 0), (r, r)), mode='edge')
    h = sum(k1d[i] * p[:, i:i + a.shape[1]] for i in range(len(k1d)))          # float tmp
    p = np.pad(h, ((r, r), (0, 0)), mode='edge')
    v = sum(k1d[i] * p[i:i + a.shape[0], :] for i in range(len(k1d)))
    return H16(v) if out_half else v


def gauss1d(sigma):
    r = int(math.ceil(sigma * 3.5))
    i = np.arange(-r, r + 1, dtype=np.float32)
    w = np.exp(-i * i / (2 * sigma * sigma))
    return w / w.sum()


def box_float(a, rad):
    r = int(rad)
    k = np.ones(2 * r + 1, np.float32) / (2 * r + 1)
    return sep_conv(a, k, out_half=False)


def bilateral(L, rad, sigma_l):
    ri = int(math.ceil(rad)); ss = max(0.5, rad * 0.5); sv = 2 * ss * ss; lv = 2 * sigma_l * sigma_l
    p = pad(L, ri); H, W = L.shape
    num = np.zeros_like(L); den = np.zeros_like(L)
    for dy in range(-ri, ri + 1):
        for dx in range(-ri, ri + 1):
            v = p[ri + dy:ri + dy + H, ri + dx:ri + dx + W]
            w = math.exp(-(dx * dx + dy * dy) / sv) * np.exp(-((v - L) ** 2) / lv)
            num += v * w; den += w
    return H16(num / np.maximum(den, 1e-4))


def guided_ab(L, r, eps):
    m = box_float(L, r); c = box_float(L * L, r)          # gf_downsample is 1:1 (w_sub = w)
    var = np.maximum(c - m * m, 0); a = var / (var + max(eps, 1e-5)); b = m - a * m
    return box_float(a, r), box_float(b, r)


def nb3(L):
    p = pad(L, 1); H, W = L.shape
    return [p[1 + dy:1 + dy + H, 1 + dx:1 + dx + W] for dy in (-1, 0, 1) for dx in (-1, 0, 1)]


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0, 1)
    return t * t * (3 - 2 * t)


def luma_compose(L, usm_base, ab, bil_base, s):
    usm_amount, usm_thresh, bil_amount, gf_lc = s[2], s[3], s[4], s[5]
    sobel_amount, sobel_thresh, edge_thin, halo = s[7], s[8] * 4, s[9], s[10]
    ps, ph, grain = s[11], s[12], s[13]
    n = nb3(L); lmin = np.minimum.reduce(n); lmax = np.maximum.reduce(n); lc = lmax - lmin
    c = L; tot = np.zeros_like(L)
    if abs(usm_amount) > 1e-3:
        d = c - usm_base
        d = np.where(d < 0, np.maximum(d, -c * 0.35), d)
        mag = np.maximum(0, np.abs(d) - usm_thresh)
        if halo > 1e-3:
            mag = np.where(d < 0, mag * (1 - halo), mag)
        tot += np.sign(d) * mag * usm_amount
    if abs(bil_amount) > 1e-3:
        tot += (c - bil_base) * bil_amount
    gfd = 0
    if abs(gf_lc) > 1e-3:
        gfd = (c - (ab[0] * c + ab[1])) * gf_lc
    # sobel / edge thinning: amount 0 on X8U
    tot = tot + gfd
    ef = smoothstep(0.15, 0.40, lc); med = 0.30 + (0.0 - 0.30) * halo
    damp = 1 + (med - 1) * ef
    ext = smoothstep(0.40, 0.70, lc)
    damp = np.where(lc > 0.40, damp + (med * 0.5 - damp) * ext, damp)
    tot = tot * damp
    if halo > 1e-3:
        margin = (1 - ef) * (0.03 + lc * 0.15)
        fv = c + tot
        tot = np.where(fv > lmax + margin, tot - (fv - lmax - margin) * halo,
                       np.where(fv < lmin - margin, tot + (lmin - margin - fv) * halo, tot))
    if ps > 1e-3 or ph > 1e-3:
        sl = ps * 0.2; hl = 1 - ph * 0.2
        m = np.ones_like(c)
        if sl > 1e-3:
            m = np.where(c < sl, (c / sl) ** 2, m)
        if hl < 0.999:
            m = np.where((c >= sl) | (sl <= 1e-3), np.where(c > hl, np.where(c >= 1, 0, ((1 - c) / (1 - hl)) ** 2), m), m)
        tot = np.where(np.abs(tot) > 1e-4, tot * np.clip(m, 0, 1), tot)
    out = np.where(np.abs(tot) > 1e-4, np.maximum(0, c + tot), c)
    return H16(out)


def j1_over_x(x):
    """J1(x)/x exactly as coded in libfc_suppressor 0x7a844 (A&S 9.4.4-style poly with x/3.75, odd asymptotic branch)."""
    ax = abs(x)
    if ax < 1e-4:
        return 0.5
    if ax <= 3.75:
        y = (ax / 3.75) ** 2
        return 0.5 + y * (-0.56249982 + y * (0.21093573 + y * (-0.03954289 + y * (0.00443319 + y * -0.00031761))))
    z = 3.75 / ax; y = z * z
    P = 0.79788458 + y * (-7.7e-7 + y * (-0.0055274 + y * (9.512e-5 + y * -0.00137237)))
    Q = 0.046875 + y * (-0.00020033 + y * (0.00844919 + y * -0.00088126))
    return (P / math.sqrt(ax)) * math.cos(ax - 2.3561945 + z * Q) / ax


def airy_psf(rad):
    half = min(int(math.ceil(rad * 1.85)), 16)
    n = 2 * half + 1; k = np.zeros((n, n), np.float32)
    f = lambda d: (2 * j1_over_x(d / rad * 3.831706)) ** 2 if rad > 1e-4 else 0.0
    for dy in range(-half, half + 1):
        for dx in range(-half, half + 1):
            d = math.hypot(dx, dy)
            if d > 1.5:
                v = f(d)
            else:
                offs = (-0.375, -0.125, 0.125, 0.375)
                v = sum(f(math.hypot(dx + ox, dy + oy)) for oy in offs for ox in offs) / 16
            k[dy + half, dx + half] = v if v > 1e-5 else 0
    return k / k.sum()


def disk_psf(rad):
    half = min(int(math.ceil(rad + 0.5)), 16)
    n = 2 * half + 1; k = np.zeros((n, n), np.float32); offs = [(i + 0.5) / 8 - 0.5 for i in range(8)]
    for dy in range(-half, half + 1):
        for dx in range(-half, half + 1):
            d = math.hypot(dx, dy)
            if d + 0.7071 <= rad: v = 1.0
            elif d - 0.7071 >= rad: v = 0.0
            else: v = sum(1 for oy in offs for ox in offs if (dx + ox) ** 2 + (dy + oy) ** 2 <= rad * rad) / 64
            k[dy + half, dx + half] = v
    return k / k.sum()


def conv2(a, k):
    r = k.shape[0] // 2; p = pad(a, r); H, W = a.shape; o = np.zeros_like(a)
    for dy in range(k.shape[0]):
        for dx in range(k.shape[1]):
            if k[dy, dx]:
                o += k[dy, dx] * p[dy:dy + H, dx:dx + W]
    return H16(o)


def rl_pass(L, kernel, rad, amount, iters, damping, halo, ps, ph, margin, macro):
    if kernel == 0:
        g = gauss1d(rad); blur = lambda a: sep_conv(a, g)
    else:
        k = airy_psf(rad) if kernel == 2 else disk_psf(rad); blur = lambda a: conv2(a, k)
    est = L.copy()
    for _ in range(iters):
        B = blur(est)
        ratio = H16(np.clip(L / np.maximum(B, 1e-4), 0.05, 10))
        E = blur(ratio)
        est = H16(np.maximum(0, est * E))           # damping 0 on X8U (TV term off)
    o = L
    diff = (est - o) * amount * smoothstep(0.002, 0.02, o)
    if ps > 1e-3 or ph > 1e-3:
        sl = ps * 0.2; hl = 1 - ph * 0.2; m = np.ones_like(o)
        if hl < 0.999:
            m = np.where(o > hl, np.where(o >= 1, 0, ((1 - o) / (1 - hl)) ** 2), m)
        if sl > 1e-3:
            m = np.where(o < sl, (o / sl) ** 2, m)
        diff = diff * np.clip(m, 0, 1)
    n = nb3(o); lmin = np.minimum.reduce(n); lmax = np.maximum.reduce(n); lc = lmax - lmin
    ms = max(0.01, macro * 0.375); ef = smoothstep(ms, macro, lc); med = 0.25 * (1 - halo)
    damp = 1 + (med - 1) * ef
    damp = np.where(lc > macro, damp + (med * 0.5 - damp) * smoothstep(macro, macro * 1.75, lc), damp)
    fv = o + diff * damp
    if halo > 1e-3:
        mg = (1 - ef) * (0.03 + lc * margin); s = min(max(halo, 0), 1)
        fv = np.where(fv > lmax + mg, lmax + mg + (fv - lmax - mg) * (1 - s),
                      np.where(fv < lmin - mg, lmin - mg - (lmin - mg - fv) * (1 - s), fv))
    return H16(np.maximum(fv, 0))


def process_luma(L, P=X8U):
    L = H16(L.astype(np.float32))
    usm = sep_conv(L, gauss1d(P['usm_radius'])) if P['usm_radius'] > 0 else L
    ab = guided_ab(L, P['gf_radius'], P['gf_eps'])
    bil = bilateral(L, P['bil_radius'], max(P['bil_color'] / 100, 1e-3))
    s = [0, 0, P['usm_amount'] if P['usm_radius'] > 0 else 0, P['usm_thresh'] / 255, P['bil_amount'] if P['bil_radius'] > 0 else 0,
         P['gf_lc'], 0, P['sobel_amount'], P['sobel_thresh'] / 255, P['edge_thinning'], P['halo_control'] / 100,
         P['protect_shadows'] / 100, P['protect_highlights'] / 100, P['film_grain'] / 255, P['rl_halo_margin'], P['rl_halo_macro']]
    L = luma_compose(L, usm, ab, bil, s)
    for kern, rad, amt, it, damp in P['rl']:
        if rad > 0 and abs(amt) > 1e-3 and it >= 1:
            L = rl_pass(L, kern, rad, amt, it, damp, P['rl_halo_control'] / 100, P['protect_shadows'] / 100,
                        P['protect_highlights'] / 100, P['rl_halo_margin'], P['rl_halo_macro'])
    return L


if __name__ == '__main__':
    k = airy_psf(1.0)
    emu = [0.000598, 0.003352, 0.001576, 0.003352, 0.000598, 0.003352, 0.011861, 0.084271, 0.011861, 0.003352, 0.001576, 0.084271, 0.579961,
           0.084271, 0.001576]
    print('airy(1) 5x5 =', np.round(k, 6).ravel()[:15].tolist())
    print('max |ref-emu| =', float(np.max(np.abs(k.ravel()[:15] - np.array(emu)))))
