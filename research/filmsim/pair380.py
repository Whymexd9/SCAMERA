"""pair380.py <ours.jpg> <theirs.jpg> <out dir> --cp <classpath> [--assets app/src/main/assets/film]

P380 (owner, 2026-10-28: «вот тебе парный пример, снято на нашу камеру и на unspektrawesome на одинаковых настройках симуляции
пленки»): the host check of our film against the owner's pair - our photo (the film at 100 %, Portra 160 printed on its maker's
Portra Endura, couplers on, as shot) inverted through our own print back to scene light, then printed again with the recipe their
JPEG stores (Portra 160, Endura Premier, IMAX 70, Pro-Mist 2.0, halation / softness off, DIR couplers 0) - every table and the
diffusion by the app's own code (research/filmsim/SceneDump.java on the classes Gradle compiled: FilmSim.lutScene, the photo's
FilmDiffusion plan on the CPU) - and compared with theirs over the static parts of the frame.

  1. half scale, their recipe + the film's diffusion at IMAX 70, no fit: the fine detail (band contrast of L*) of the muster
     list, the map and the clock against theirs - acceptance 0.8 .. 1.2 x on every band;
  2. quarter scale, the same + their -1.8 EV corners (their lens shading, not the film) + three global capture numbers fitted
     (exposure, R/G, B/G: their capture is not ours) - acceptance RMS <= 8 (8-bit) and mean dE76 <= 3.

The pair is two captures 18 s apart (the chair moved): the static mask leaves the chair, the clothes, our watermark and the top
75 px their frame does not cover. The shift (theirs = ours moved -76 / +12 px) is the research round's phase correlation. Panels:
<out>/pair380_full.jpg (ours as shot | re-rendered on their recipe | + vignette and capture fit | theirs) and crops of the list,
the lamp, the map. Nothing of the Unspektrawesome app is used: only the recipe stored in the owner's own JPEG.
"""
import argparse, os, subprocess, sys, tempfile, time
import numpy as np
from PIL import Image

N = 41
SHIFT = (76, -12)
# static parts (full-size aligned crop): below the top 75 px, above the chair, the wall beside it
REG = {'muster': (1950, 2200, 1720, 2600), 'map': (1700, 2300, 40, 740), 'clock': (1250, 1500, 1680, 1980)}
PATCH = {'list red': (1950, 2200, 2010, 2090), 'list green': (1950, 2200, 2110, 2170), 'list blue': (1960, 2120, 2420, 2520),
         'list yellow': (2000, 2200, 1720, 1800), 'paper fold': (1800, 1880, 2330, 2440), 'map ocean': (1900, 2000, 100, 250),
         'speaker black': (1300, 1440, 900, 1150), 'wall': (1200, 1600, 2400, 2800)}
CROPS = {'list': (1700, 2300, 1600, 2700), 'lamp': (0, 900, 0, 1200), 'map': (1600, 2400, 0, 900)}


def load_pair(ours, theirs):
    a = np.asarray(Image.open(ours).convert('RGB')).astype(np.float64)
    b = np.asarray(Image.open(theirs).convert('RGB')).astype(np.float64)
    dy, dx = SHIFT
    H, W = a.shape[:2]
    return a[dy:, :W + dx], b[:H - dy, -dx:], max(H, W)


def down(x, k):
    if k == 1: return x
    H, W = x.shape[0] // k, x.shape[1] // k
    return x[:H * k, :W * k].reshape(H, k, W, k, 3).mean((1, 3))


def look(T, u):
    """The table at grid coordinates u (..., 3: r, g, b in 0 .. N - 1), trilinear (the shader's FILM_SIM tap)."""
    u = np.clip(u, 0, N - 1 - 1e-6)
    i = np.floor(u).astype(np.int32)
    f = u - i
    out = 0
    for dz in (0, 1):
        wz = f[..., 2] if dz else 1 - f[..., 2]
        for dy in (0, 1):
            wy = f[..., 1] if dy else 1 - f[..., 1]
            for dx in (0, 1):
                wx = f[..., 0] if dx else 1 - f[..., 0]
                out = out + (wx * wy * wz)[..., None] * T[i[..., 2] + dz, i[..., 1] + dy, i[..., 0] + dx]
    return out


def invert(T, y, iters=12, rows=256):
    """Scene light (grid coordinates) whose print is y: the grey axis to start, then damped Gauss-Newton on the trilinear table
    (in bands of rows: the full-size frame fits in memory)."""
    if y.shape[0] > rows:
        parts = [invert(T, y[r:r + rows], iters, rows) for r in range(0, y.shape[0], rows)]
        return np.concatenate([q[0] for q in parts]), np.concatenate([q[1] for q in parts])
    g = np.array([T[j, j, j] for j in range(N)])
    u = np.stack([np.interp(y[..., c], np.maximum.accumulate(g[:, c]) + np.arange(N) * 1e-7, np.arange(N)) for c in range(3)], -1)
    for _ in range(iters):
        f0 = look(T, u)
        r = y - f0
        J = np.empty(y.shape + (3,))
        for c in range(3):
            du = np.zeros(3)
            du[c] = 0.05
            J[..., :, c] = (look(T, u + du) - f0) / 0.05
        JT = np.swapaxes(J, -1, -2)
        step = np.linalg.solve(JT @ J + 1e-3 * np.eye(3), JT @ r[..., None])[..., 0]
        u = np.clip(u + step, 0, N - 1)
    return u, np.abs(y - look(T, u)).max(-1)


def u_to_scene(u): return 0.184 * 2.0 ** (-10 + 20 * u / (N - 1))
def scene_to_u(s): return (np.log2(np.maximum(s, 1e-12) / 0.184) + 10) * (N - 1) / 20


def dec(v):
    v = v / 255.0
    return np.where(v <= 0.04045, v / 12.92, ((v + 0.055) / 1.055) ** 2.4)


def lab(e8):
    l = dec(e8)
    M = np.array([[0.4124, 0.3576, 0.1805], [0.2126, 0.7152, 0.0722], [0.0193, 0.1192, 0.9505]])
    xyz = l @ M.T / np.array([0.95047, 1.0, 1.08883])
    f = np.where(xyz > 216 / 24389, np.cbrt(xyz), (24389 / 27 * xyz + 16) / 116)
    return np.stack([116 * f[..., 1] - 16, 500 * (f[..., 0] - f[..., 1]), 200 * (f[..., 1] - f[..., 2])], -1)


class Pair:
    def __init__(self, a, b, k, frame_long, cp, assets, tmp):
        self.k, self.cp, self.assets, self.tmp = k, cp, assets, tmp
        self.ao, self.bo = down(a, k), down(b, k)
        self.H, self.W = self.ao.shape[:2]
        self.frame_long = frame_long / k
        yy, xx = np.mgrid[0:self.H, 0:self.W] * k
        self.mask = (yy > 80) & ((yy < 2380) | ((yy < 3100) & ((xx < 850) | (xx > 2150))))
        rr = np.hypot(*(np.mgrid[0:self.H, 0:self.W] - np.array([self.H / 2, self.W / 2])[:, None, None])) / np.hypot(self.H / 2, self.W / 2)
        self.vig = 2.0 ** -(2.65 * rr ** 2 - 0.81 * rr ** 4)   # their corners, fitted by the research round (not film)
        self.tables = {}

    def java(self, *args):
        out = subprocess.run(['java', '-Xmx3g', '-cp', self.cp, 'com.particlesdevs.photoncamera.processing.color.SceneDump'] + [str(a) for a in args],
                             check=True, capture_output=True, text=True).stdout.strip()
        print('   ', out.splitlines()[-1])

    def table(self, stock, paper, dir_pct):
        key = (stock, paper, dir_pct)
        if key not in self.tables:
            p = os.path.join(self.tmp, 'T_%s_%s_%d.f32' % key)
            if not os.path.exists(p): self.java('table', self.assets, stock, paper, dir_pct, p)
            self.tables[key] = np.fromfile(p, dtype='<f4').reshape(N, N, N, 3).astype(np.float64)
        return self.tables[key]

    def diffuse(self, scene, family, grade, fmt):
        src = os.path.join(self.tmp, 'scene_%d.f32' % self.k)
        dst = os.path.join(self.tmp, 'diffused_%d_%s_%s_%s.f32' % (self.k, family, grade, fmt))
        scene.astype('<f4').tofile(src)
        self.java('diffuse', src, self.W, self.H, family, grade, fmt, int(round(self.frame_long)), dst)
        return np.fromfile(dst, dtype='<f4').reshape(self.H, self.W, 3).astype(np.float64)

    def bands(self, img, reg):
        y0, y1, x0, x1 = [v // self.k for v in reg]
        Y = lab(img[y0:y1, x0:x1])[..., 0]
        def gb(a, s):
            fy = np.fft.fftfreq(a.shape[0])[:, None]
            fx = np.fft.rfftfreq(a.shape[1])[None, :]
            return np.fft.irfft2(np.fft.rfft2(a) * np.exp(-2 * (np.pi * s) ** 2 * (fx ** 2 + fy ** 2)), s=a.shape)
        sig = [s / self.k for s in (0.7, 1.4, 2.8, 5.6, 11.2)]
        out, prev = [], Y
        for s in (sig[1:] if self.k > 1 else sig):
            b = gb(Y, s)
            out.append(np.std((prev - b)[4:-4, 4:-4]))
            prev = b
        return np.array(out)

    def report(self, name, img):
        d = img - self.bo
        rms = np.sqrt((d[self.mask] ** 2).mean())
        dE = np.linalg.norm(lab(img) - lab(self.bo), axis=-1)[self.mask]
        detail = {k: self.bands(img, r) / np.maximum(self.bands(self.bo, r), 1e-6) for k, r in REG.items()}
        lo = min(v.min() for v in detail.values())
        hi = max(v.max() for v in detail.values())
        print('%-58s RMS %5.1f  dE76 mean %4.1f p95 %4.1f | detail ours/theirs %.2f .. %.2f (list %s map %s clock %s)' % (
            name, rms, dE.mean(), np.percentile(dE, 95), lo, hi, np.round(detail['muster'], 2), np.round(detail['map'], 2),
            np.round(detail['clock'], 2)))
        pc = []
        for k, (y0, y1, x0, x1) in PATCH.items():
            L = lab(img[y0 // self.k:y1 // self.k, x0 // self.k:x1 // self.k]).reshape(-1, 3).mean(0)
            pc.append('%s L%.0f C%.1f' % (k, L[0], np.hypot(L[1], L[2])))
        print('    ' + ' | '.join(pc))
        return {'rms': rms, 'dE': dE.mean(), 'detail_lo': lo, 'detail_hi': hi}


def render(T, s):
    return np.clip(look(T, scene_to_u(s)) * 255.0, 0, 255)


def fit_capture(pair, T, s, vig):
    """Three global numbers of the capture (exposure, R/G, B/G in stops) by coordinate descent on the RMS."""
    def img(p):
        g = 2.0 ** np.array([p[0] + p[1], p[0], p[0] + p[2]])
        return render(T, s * vig[..., None] * g)
    def f(p): return np.sqrt(((img(p) - pair.bo)[pair.mask] ** 2).mean())
    x = np.array([0.5, 0.0, 0.0])
    fx, step = f(x), 0.4
    while step > 0.01:
        moved = False
        for i in range(3):
            for d in (step, -step):
                y = x.copy()
                y[i] += d
                fy = f(y)
                if fy < fx:
                    x, fx, moved = y, fy, True
                    break
        if not moved: step /= 2
    return x, img(x)


def main():
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('ours')
    ap.add_argument('theirs')
    ap.add_argument('out')
    ap.add_argument('--cp', required=True, help='classpath with the app classes and SceneDump')
    ap.add_argument('--assets', default='app/src/main/assets/film')
    ap.add_argument('--full', action='store_true', help='also the detail check at full size (the photo\'s own pixels)')
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)
    t0 = time.time()
    a, b, frame_long = load_pair(args.ours, args.theirs)
    ok = True
    with tempfile.TemporaryDirectory() as tmp:
        print('== half scale: their recipe and the film\'s diffusion, no fit')
        p2 = Pair(a, b, 2, frame_long, args.cp, args.assets, tmp)
        ours_print = p2.table('kodak_portra_160', 'kodak_portra_endura', 100)
        theirs_print = p2.table('kodak_portra_160', 'kodak_endura_premier', 0)
        u, err = invert(ours_print, p2.ao / 255.0)
        print('inverse of our print: residual p50 %.2f p99 %.2f / 255' % (255 * np.median(err), 255 * np.percentile(err, 99)))
        scene2 = u_to_scene(u)
        p2.report('ours as shot', p2.ao)
        p2.report('their recipe, no diffusion', render(theirs_print, scene2))
        pm36 = p2.diffuse(scene2, 'pearl', 2.0, 'off')
        p2.report('their recipe + Pro-Mist 2.0 on the 36 mm frame', render(theirs_print, pm36))
        pm2 = p2.diffuse(scene2, 'pearl', 2.0, 'imax70')
        after2 = render(theirs_print, pm2)
        r2 = p2.report('their recipe + Pro-Mist 2.0 at IMAX 70 (no fit)', after2)
        a1 = 0.8 <= r2['detail_lo'] and r2['detail_hi'] <= 1.2
        print('ACCEPT detail 0.8 .. 1.2 x: %s (%.2f .. %.2f)' % ('PASS' if a1 else 'FAIL', r2['detail_lo'], r2['detail_hi']))
        ok &= a1
        if args.full:
            print('== full size: their recipe and the film\'s diffusion, no fit (the photo\'s own pixels)')
            p1 = Pair(a, b, 1, frame_long, args.cp, args.assets, tmp)
            u1, err1 = invert(ours_print, p1.ao / 255.0)
            print('inverse of our print: residual p50 %.2f p99 %.2f / 255' % (255 * np.median(err1), 255 * np.percentile(err1, 99)))
            scene1 = u_to_scene(u1)
            del u1, err1
            p1.report('ours as shot', p1.ao)
            r1 = p1.report('their recipe + Pro-Mist 2.0 at IMAX 70 (no fit)', render(theirs_print, p1.diffuse(scene1, 'pearl', 2.0, 'imax70')))
            a0 = 0.8 <= r1['detail_lo'] and r1['detail_hi'] <= 1.2
            print('ACCEPT (full size) detail 0.8 .. 1.2 x: %s (%.2f .. %.2f)' % ('PASS' if a0 else 'FAIL', r1['detail_lo'], r1['detail_hi']))
            del scene1, p1
        print('== quarter scale: + their vignette and the capture fit')
        p4 = Pair(a, b, 4, frame_long, args.cp, args.assets, tmp)
        u4, _ = invert(ours_print, p4.ao / 255.0)
        scene4 = u_to_scene(u4)
        pm4 = p4.diffuse(scene4, 'pearl', 2.0, 'imax70')
        one = np.ones_like(p4.vig)
        x0, img0 = fit_capture(p4, ours_print, scene4, p4.vig)
        p4.report('control: our recipe, no diffusion, vignette + fit EV %+.2f R/G %+.2f B/G %+.2f' % tuple(x0), img0)
        x1, img1 = fit_capture(p4, theirs_print, pm4, one)
        p4.report('their recipe + diffusion + fit EV %+.2f R/G %+.2f B/G %+.2f' % tuple(x1), img1)
        x2, after4 = fit_capture(p4, theirs_print, pm4, p4.vig)
        r4 = p4.report('their recipe + diffusion + vignette + fit EV %+.2f R/G %+.2f B/G %+.2f' % tuple(x2), after4)
        a2 = r4['rms'] <= 8 and r4['dE'] <= 3
        print('ACCEPT RMS <= 8, dE76 <= 3: %s (RMS %.1f, dE76 %.2f)' % ('PASS' if a2 else 'FAIL', r4['rms'], r4['dE']))
        ok &= a2
        # the panels: ours as shot | re-rendered on their recipe (half scale) | + vignette and fit (quarter, enlarged) | theirs
        big = np.repeat(np.repeat(after4, 2, 0), 2, 1)[:p2.H, :p2.W]
        if big.shape != p2.ao.shape:
            pad = np.zeros_like(p2.ao)
            pad[:big.shape[0], :big.shape[1]] = big
            big = pad
        row = np.concatenate([p2.ao, after2, big, p2.bo], 1)
        Image.fromarray(row[::2, ::2].astype(np.uint8)).save(os.path.join(args.out, 'pair380_full.jpg'), quality=90)
        for name, (y0, y1, x0, x1) in CROPS.items():
            c = [v[y0 // 2:y1 // 2, x0 // 2:x1 // 2] for v in (p2.ao, after2, big, p2.bo)]
            Image.fromarray(np.concatenate(c, 1).astype(np.uint8)).save(os.path.join(args.out, 'pair380_%s.jpg' % name), quality=92)
    print('panels (ours | their recipe + diffusion IMAX 70 | + vignette and capture fit | theirs) in %s; %.0f s' % (args.out, time.time() - t0))
    sys.exit(0 if ok else 1)


if __name__ == '__main__':
    main()
