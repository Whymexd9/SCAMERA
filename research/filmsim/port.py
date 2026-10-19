"""port.py: our reimplementation from the exported arrays only (the Java port follows it line by line); compares with refs.json."""
import json, numpy as np, sys
from rd import read

D = 'out/'
SH = read(D + 'shared.bin')
FILMS = ['fujifilm_c200', 'fujifilm_pro_400h', 'fujifilm_provia_100f', 'fujifilm_velvia_100', 'fujifilm_xtra_400', 'kodak_ektachrome_100',
         'kodak_ektar_100', 'kodak_gold_200', 'kodak_kodachrome_64', 'kodak_portra_160', 'kodak_portra_400', 'kodak_portra_800',
         'kodak_portra_800_push1', 'kodak_portra_800_push2', 'kodak_ultramax_400', 'kodak_verita_200d', 'kodak_vision3_200t',
         'kodak_vision3_250d', 'kodak_vision3_500t', 'kodak_vision3_50d']
PRINTS = ['fujifilm_crystal_archive_typeii', 'kodak_2383', 'kodak_2393', 'kodak_ektacolor_edge', 'kodak_endura_premier',
          'kodak_portra_endura', 'kodak_supra_endura', 'kodak_ultra_endura']
POSITIVE = {'fujifilm_provia_100f', 'fujifilm_velvia_100', 'kodak_ektachrome_100', 'kodak_kodachrome_64'}


def interp(x, xp, fp):  # np.interp per value, clamped ends
    return np.interp(x, xp, fp)


def mitchell(t, B=1 / 3, C=1 / 3):
    x = abs(t)
    if x < 1: return (1 / 6) * ((12 - 9 * B - 6 * C) * x ** 3 + (-18 + 12 * B + 6 * C) * x ** 2 + (6 - 2 * B))
    if x < 2: return (1 / 6) * ((-B - 6 * C) * x ** 3 + (6 * B + 30 * C) * x ** 2 + (-12 * B - 48 * C) * x + (8 * B + 24 * C))
    return 0.0


def refl(i, L):
    return -i if i < 0 else (2 * (L - 1) - i if i >= L else i)


def cubic2d(lut, x, y):
    L = lut.shape[0]
    def bf(c):
        c = min(max(c, 0.0), L - 1.0)
        if c >= L - 1: return L - 2, 1.0
        b = int(np.floor(c)); return b, c - b
    xb, xf = bf(x * (L - 1)); yb, yf = bf(y * (L - 1))
    wx = [mitchell(xf + 1), mitchell(xf), mitchell(xf - 1), mitchell(xf - 2)]
    wy = [mitchell(yf + 1), mitchell(yf), mitchell(yf - 1), mitchell(yf - 2)]
    o = np.zeros(3); ws = 0
    for i in range(4):
        xi = refl(xb - 1 + i, L)
        for j in range(4):
            yj = refl(yb - 1 + j, L); w = wx[i] * wy[j]; ws += w; o += w * lut[xi, yj]
    return o / ws if ws != 0 else o


def trilinear(lut, lo, hi, v):
    n = lut.shape[0]
    p = np.clip((np.asarray(v) - lo) / (hi - lo), 0, 1) * (n - 1)
    i0 = np.minimum(np.floor(p).astype(int), n - 2); t = p - i0
    o = np.zeros(3)
    for dz in (0, 1):
        for dy in (0, 1):
            for dx in (0, 1):
                w = (t[0] if dx else 1 - t[0]) * (t[1] if dy else 1 - t[1]) * (t[2] if dz else 1 - t[2])
                o += w * lut[i0[2] + dz, i0[1] + dy, i0[0] + dx]
    return o


class Film:
    def __init__(self, s):
        a = read(D + s + '.bin'); self.__dict__.update(a); self.id = s; self.positive = s in POSITIVE
        self.dcN = self.dc - self.dc.min(0)
        silver = (self.dcN.max(0) - self.dcN) if self.positive else self.dcN
        amount = silver @ self.dir
        self.dc0 = np.zeros_like(self.dcN)
        for i in range(3):
            le0 = self.le - amount[:, i]
            self.dc0[:, i] = -np.interp(self.le, le0, -self.dcN[:, i]) if self.positive else np.interp(self.le, le0, self.dcN[:, i])
        self.dmaxN = self.dcN.max(0)

    def raw(self, rgb):
        xyz = self.M @ rgb; b = xyz.sum(); xy = xyz[:2] / max(b, 1e-10)
        tx, ty = xy
        qy = min(max(ty / max(1 - tx, 1e-10), 0), 1); qx = min(max((1 - tx) ** 2, 0), 1)
        return cubic2d(self.tc, qx, qy) * (0 if not np.isfinite(b) else b)

    def develop(self, lograw, gamma=1.0):
        d = np.array([interp(lograw[c], self.le / gamma, self.dcN[:, c]) for c in range(3)])
        silver = (self.dmaxN - d) if self.positive else d
        lr0 = lograw - silver @ self.dir
        return np.array([interp(lr0[c], self.le / gamma, self.dc0[:, c]) for c in range(3)])

    def mid_density_spectral(self, ev):  # _simple_rgb_to_density_spectral: raw curves, no DIR
        raw = self.raw(np.array([0.184, 0.184, 0.184]) * 2 ** ev)
        lr = np.log10(raw + 1e-10)
        d = np.array([interp(lr[c], self.le, self.dc[:, c]) for c in range(3)])
        return self.cd @ d + self.bd


class Print:
    def __init__(self, s):
        self.__dict__.update(read(D + s + '.bin')); self.id = s


def filtered(cc):
    t = 10 ** (-np.asarray(cc) / 100.0)
    f = SH['dichroic']
    total = np.prod(1 - (1 - f) * (1 - t), axis=1)
    return SH['thkg3'] * total


def render(film, prnt, rgb, ev=0.0, print_exposure=1.0, m_shift=0.0, y_shift=0.0, gamma=1.0, scan_film=False):
    raw = film.raw(rgb) * 2 ** ev
    lr = np.log10(np.fmax(raw, 0) + 1e-10)
    d = film.develop(lr, gamma)
    if scan_film:
        return trilinear(film.scan, film.scanmin, film.scanmax, d)
    neu = SH['neutral'][PRINTS.index(prnt.id), FILMS.index(film.id)]
    ill = filtered([neu[0], neu[1] + m_shift, neu[2] + y_shift])
    def fac(ds):
        rm = np.fmax(np.nan_to_num(10 ** -ds * ill) @ prnt.sens, 1e-10)  # density_to_light: NaN density -> no light
        return 1 / np.exp(np.mean(np.log(rm)))
    factor = fac(film.mid_density_spectral(ev))  # normalize + compensation (non-lut default)
    ds = film.cd @ d + film.bd
    rp = np.nan_to_num(10 ** -ds * ill) @ prnt.sens * factor
    lrp = np.log10(np.fmax(rp, 0) + 1e-10)
    rp = 10 ** lrp * print_exposure
    lrp = np.log10(np.fmax(rp, 0) + 1e-10)
    dp = np.array([interp(lrp[c], prnt.le, prnt.dcm[:, c]) for c in range(3)])
    return trilinear(prnt.scan, prnt.scanmin, prnt.scanmax, dp)


if __name__ == '__main__':
    R = json.load(open('refs.json'))
    X = np.array(R['x'])
    for c in R['cases']:
        f, p, o = Film(c['film']), Print(c['print']), c['opts']
        Y = np.array(c['y'])
        out = np.array([render(f, p, x, o.get('ev', 0), o.get('print_exposure', 1), o.get('m_shift', 0), o.get('y_shift', 0),
                               scan_film=o.get('scan_film', False)) for x in X])
        e = np.abs(out - Y)
        print('%-24s %-32s %-40s max %.4f mean %.5f worst %s' % (c['film'], c['print'], o, e.max(), e.mean(), X[np.argmax(e.max(1))]))
