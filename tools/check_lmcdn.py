#!/usr/bin/env python3
"""Offline check of the LMC hybrid noise reduction (LmcDenoise + shaders lmcdn/*).

Runs the shipped fragment shaders with moderngl (GLSL ES 3.1 through ARB_ES3_1_compatibility; on Linux add
backend='egl' to create_standalone_context), driven by a Python port of LmcDenoise.process, and compares them with the
numpy reference tools/lmcdn_ref.py (written from denoise_port.md section 7):
  1. kernels: kD8 / {1,3,3,1} pyramids, BilateralFilter3x3 (also against research/hybrid5/ref/denoise/bf_sim.py),
     BilateralFilterChroma3x3, block statistics;
  2. the whole pipeline on the 1x grid and on the Sabre 2x grid (identity with zero strengths, GPU vs reference);
  3. per-band noise reduction factors (Laplacian bands b0..b4 of Y, U, V) on synthetic frames with a known noise model,
     white and correlated, for several SNR tiers and for the exact ArkCam emulation (revert_max 9, no safeguards);
  4. a crop of a real merged linear frame (research/cct/cap2/denoise-in.f32) when it is present.
Usage: python tools/check_lmcdn.py [--quick]
"""
import sys, math, time
from pathlib import Path
import numpy as np
import moderngl

ROOT = Path(__file__).resolve().parents[1]
SH = ROOT / 'app/src/main/assets/shaders'
sys.path.insert(0, str(Path(__file__).resolve().parent))
import lmcdn_ref as R

ctx = moderngl.create_standalone_context(require=430)
VS = "#version 310 es\nin vec2 vPos;\nvoid main(){ gl_Position = vec4(vPos, 0.0, 1.0); }\n"
_progs = {}
quad = ctx.buffer(np.array([-1, -1, 1, -1, -1, 1, 1, 1], 'f4').tobytes())


def prog(name):
    if name not in _progs:
        src = (SH / (name + '.glsl')).read_text(encoding='utf-8')
        p = ctx.program(vertex_shader=VS, fragment_shader='#version 310 es\n' + src)
        _progs[name] = (p, ctx.vertex_array(p, [(quad, '2f', 'vPos')]))
    return _progs[name]


def tex(a, dtype='f2', linear=True):
    a = np.asarray(a)
    if a.ndim == 2:
        a = a[..., None]
    h, w, c = a.shape
    if c == 3:
        a = np.concatenate([a, np.ones((h, w, 1), a.dtype)], -1); c = 4
    npt = {'f2': np.float16, 'f4': np.float32, 'u1': np.uint8, 'u4': np.uint32}[dtype]
    t = ctx.texture((w, h), c, np.ascontiguousarray(a.astype(npt)).tobytes(), dtype=dtype)
    t.filter = (moderngl.LINEAR, moderngl.LINEAR) if linear and dtype[0] == 'f' else (moderngl.NEAREST, moderngl.NEAREST)
    t.repeat_x = t.repeat_y = False
    return t


def empty(w, h, c, dtype='f2', linear=True):
    t = ctx.texture((w, h), c, dtype=dtype)
    t.filter = (moderngl.LINEAR, moderngl.LINEAR) if linear and dtype[0] == 'f' else (moderngl.NEAREST, moderngl.NEAREST)
    t.repeat_x = t.repeat_y = False
    return t


def run(name, target, textures=None, **uniforms):
    p, vao = prog(name)
    unit = 0
    for k, t in (textures or {}).items():
        if k in p:
            p[k].value = unit
            t.use(location=unit)
            unit += 1
    for k, v in uniforms.items():
        if k in p:
            p[k].value = v
    fbo = ctx.framebuffer(color_attachments=[target])
    fbo.use()
    ctx.viewport = (0, 0, target.width, target.height)
    vao.render(moderngl.TRIANGLE_STRIP)
    ctx.finish()
    fbo.release()
    return target


def read(t, dtype=np.float16):
    a = np.frombuffer(t.read(), dtype).reshape(t.height, t.width, t.components)
    return a.astype(np.float64) if dtype != np.uint32 else a


def half_size(w, h):
    return (w + 1) // 2, (h + 1) // 2


# ------------------------------------------------------------------ Python port of LmcDenoise.process
def gpu_stats(level_tex, block, model):
    bw, bh = max(1, level_tex.width // block), max(1, level_tex.height // block)
    st = empty(bw, bh, 4, 'u4', False)
    run('lmcdn/stats', st, {'InputBuffer': level_tex}, blockU=block, modelU=tuple(model))
    raw = read(st, np.uint32).reshape(-1, 4)
    lo = (raw & 0xffff).astype(np.uint16).view(np.float16).astype(np.float64)
    hi = (raw >> 16).astype(np.uint16).view(np.float16).astype(np.float64)
    out = np.stack([lo, hi], -1).reshape(-1, 8)
    st.release()
    return out


def estimate_noise(noisy_tex):
    """NiceDenoise.estimateNoise at stride 1 (chromadn/noiseest, 25th percentile)."""
    bw, bh = (noisy_tex.width + 7) // 8, (noisy_tex.height + 7) // 8
    est = empty(bw, bh, 4)
    run('chromadn/noiseest', est, {'InputBuffer': noisy_tex}, blockU=8, pxStepU=1)
    v = read(est)[..., 0].ravel()
    v = np.sort(v[(v > 0) & np.isfinite(v)])
    est.release()
    return 0.0 if len(v) == 0 else float(v[len(v) // 4] / 0.8463 / math.sqrt(1.125))


def gpu_denoise(rgb_full, s, cfg, model, rho=(1.2, 6.0), snr_fixed=0.0, despeckle=False, dark_fade=False,
                eff=None, eff_max=3.0, wp=None, force_g=None, keep2x=0.0):
    """Mirror of LmcDenoise.process. rgb_full: (H, W, 3) linear WB-RGB on the output grid (s = 1 or 2).
    model = (S, O) per channel arrays of one base frame (normalised raw), wp = whitePoint."""
    info = {}
    H, W = rgb_full.shape[:2]
    w0, h0 = (W + s - 1) // s, (H + s - 1) // s
    inp = tex(rgb_full)
    S, O = model
    wp = np.ones(3) if wp is None else np.asarray(wp)
    wY = np.array([0.2126, 0.7152, 0.0721996])
    g = 1.0 / wp
    sY = float(np.sum(wY ** 2 * g * S)); rY = float(np.sum(wY ** 2 * g * g * O))
    sG = float(g[1] * S[1]); rG = float(g[1] ** 2 * O[1])
    info['sY'], info['rY'] = sY, rY
    owned = []
    def E(w, h, c, dtype='f2', linear=True):
        t = empty(w, h, c, dtype, linear); owned.append(t); return t
    base = inp
    if s == 2:
        base = run('lmcdn/down2x', E(w0, h0, 4), {'InputBuffer': inp})
    noisy = run('chromadn/luma', E(w0, h0, 1), {'InputBuffer': base}, offsetC=0.008)
    sigma_u = estimate_noise(noisy)
    clean = base
    if despeckle:
        clean = run('chromadn/despeckle', E(w0, h0, 4), {'InputBuffer': base}, sigma=sigma_u, offsetC=0.008, pxStepU=1)
    x0 = run('lmcdn/yuv', E(w0, h0, 4), {'InputBuffer': clean})
    info['x0'] = read(x0)[..., :3]
    Y = [x0]
    for L in range(1, 5):
        Y.append(run('lmcdn/down8', E(*half_size(Y[-1].width, Y[-1].height), 1), {'InputBuffer': Y[-1]}))
    XC = [x0]
    for L in range(1, 4):
        XC.append(run('lmcdn/down4', E(*half_size(XC[-1].width, XC[-1].height), 4), {'InputBuffer': XC[-1]}))
    info['pyr_luma'] = [read(t)[..., 0] for t in Y]
    info['pyr_chroma'] = [read(t)[..., :3] for t in XC]
    gL, gC, raws = [None] * 4, [None] * 4, [None] * 4
    raws[0] = gpu_stats(x0, 16, (sY, rY))
    gL[0] = gC[0] = R.reduce_stats(raws[0])
    for L in range(1, 4):
        gL[L] = R.reduce_stats(gpu_stats(Y[L], 16, (sY, rY)))
        gC[L] = R.reduce_stats(gpu_stats(XC[L], 16, (sY, rY)))
    GY = [[0, 0] for _ in range(4)]; GC = [[0, 0] for _ in range(4)]; UVS = [[1] * 4 for _ in range(4)]
    for L in range(4):
        for k in range(2):
            GY[L][k] = gL[L][k] if gL[L] else (gL[0][k] * 0.19269013 ** L if gL[0] else 2 * 0.19269013 ** L / 4)
            GC[L][k] = gC[L][k] if gC[L] else (gC[0][k] * 0.09765625 ** L if gC[0] else 2 * 0.09765625 ** L / 4)
        src = gC[L] if gC[L] else gC[0]
        UVS[L] = list(src[6:10]) if src else [1] * 4
    if force_g is not None:
        GY, GC, UVS = force_g
    info['G'] = {'Y': GY, 'C': GC}; info['UVS'] = UVS
    ys = raws[0][:, 0]; ys = np.sort(ys[(ys >= 0) & np.isfinite(ys)])
    p50 = ys[min(len(ys) - 1, len(ys) // 2)]; p90 = ys[min(len(ys) - 1, int(len(ys) * 0.9))]
    gain = max(1.0, min(128.0, max(math.sqrt(max(1, .05 / max(p50, 1e-5)) * max(1, .18 / max(p90, 1e-5))),
                                   max(1.0, min(2.2, .25 / max(p50, 1e-5))))))
    mu = 0.18 / gain
    snr_m = R.snr_estimate(mu, GY[0][0], sG, rG, rho[0], rho[1])
    snr = snr_fixed if snr_fixed > 0 else snr_m
    info.update(snr=snr, snr_measured=snr_m, mu=mu, gain=gain)
    luma, lp = R.luma_params(cfg, snr)
    chroma, cp = R.chroma_params(cfg, snr)
    info['luma'], info['chroma'] = luma, chroma
    strmap = None
    if eff is not None:
        codes = np.asarray(eff, np.uint8)
        nz = codes[codes > 0].ravel()[::7]
        ref = float(np.sort(nz)[len(nz) // 2 if len(nz) % 2 else (len(nz) - 1) // 2]) if len(nz) else 64.0
        # Java: first v with cumulative*2 >= count
        hist = np.bincount(nz, minlength=256); cum = np.cumsum(hist[1:]); ref = float(1 + np.argmax(cum * 2 >= len(nz)))
        et = tex(codes, 'u1', False); owned.append(et)
        strmap = run('lmcdn/strmap', E(*half_size(w0, h0), 1), {'EffMap': et}, factorU=2 * s, effRefU=ref, effMaxU=float(eff_max))
        info['strmap'] = read(strmap)[..., 0]; info['effRef'] = ref
    def lpass(target, inn, recon, stride, filt, nx, ny, thr, stage, rf, mode, base_t=None, low=None, chroma_t=None, use_yin=False, size=None):
        return run('lmcdn/lbf', target, {'InputBuffer': inn, 'Recon': recon, 'Yin': x0, 'Base': base_t or inn,
                                         'LowFreq': low or inn, 'Chroma': chroma_t or x0, 'StrMap': strmap or inn},
                   strideU=stride, filterU=int(filt), noiseU=(nx, ny), thrU=thr, stageU=stage, rfU=rf, modeU=mode,
                   useYinU=int(use_yin), useMapU=int(strmap is not None), mapInvU=(1.0 / size[0], 1.0 / size[1]))
    def low_pass(den1, y1):
        return run('lmcdn/llow', E(y1.width, y1.height, 2), {'InputBuffer': den1, 'Base': y1})
    luma_on = any(b[0] > 0 for b in luma)
    x0p = x0
    if luma_on:
        delta = None
        for L in range(3, -1, -1):
            s0, s1 = luma[L + 1][0], luma[L][0]
            f0, f1 = s0 > 0, s1 > 0
            sz = (Y[L].width, Y[L].height)
            if L >= 1 and not f0 and not f1 and delta is None:
                if L == 1:
                    delta = low_pass(Y[1], Y[1])
                continue
            recon = Y[L]
            if delta is not None:
                recon = run('lmcdn/lrecon', E(*sz, 1), {'Base': Y[L], 'Delta': delta})
            k0, k1 = s0 * s0 / 2, s1 * s1
            d0 = recon
            if f0:
                d0 = lpass(E(*sz, 1), recon, recon, 2, True, k0 * GY[L][1] * rho[0] * sY, k0 * GY[L][1] * rho[1] * rY,
                           luma[L + 1][2] * 16, 0, 0.0, 0, use_yin=(L == 0), size=sz)
            if L >= 2:
                out = lpass(E(*sz, 1), d0, recon, 1, f1, k1 * GY[L][0] * rho[0] * sY, k1 * GY[L][0] * rho[1] * rY,
                            luma[L][2] * 16, 1, luma[L][1], 1, base_t=Y[L], size=sz)
            elif L == 1:
                den1 = lpass(E(*sz, 1), d0, recon, 1, f1, k1 * GY[L][0] * rho[0] * sY, k1 * GY[L][0] * rho[1] * rY,
                             luma[L][2] * 16, 1, luma[L][1], 0, size=sz)
                out = low_pass(den1, Y[1])
            else:
                out = lpass(E(w0, h0, 4), d0, recon, 1, f1, k1 * GY[0][0] * rho[0] * sY, k1 * GY[0][0] * rho[1] * rY,
                            luma[0][2] * 16, 1, luma[0][1], 2, base_t=x0, low=delta, chroma_t=x0, use_yin=True, size=sz)
            delta = out
        x0p = delta
    info['x0p'] = read(x0p)[..., :3]
    XP = [x0p]
    for L in range(1, 4):
        XP.append(run('lmcdn/down4', E(*half_size(XP[-1].width, XP[-1].height), 4), {'InputBuffer': XP[-1]}))
    def cpass(target, inn, delta_uv, orig, stride, filt, nx, ny, uvs, thr, mode, fade, size, keep=0.0):
        return run('lmcdn/cbf', target, {'InputBuffer': inn, 'DeltaUV': delta_uv or inn, 'Orig': orig or inn, 'StrMap': strmap or inn},
                   strideU=stride, filterU=int(filt), useDeltaU=int(delta_uv is not None), useMapU=int(strmap is not None),
                   noiseU=(nx, ny), uvsU=tuple(uvs), thrU=thr, mapInvU=(1.0 / size[0], 1.0 / size[1]), modeU=mode,
                   keepU=float(keep), fadeU=int(fade), darkFadeU=(0.0008, 0.003))
    delta_uv = None
    output = None
    for L in range(3, -1, -1):
        s0, s1 = chroma[L + 1][0], chroma[L][0]
        f0, f1 = s0 > 0, s1 > 0
        sz = (XP[L].width, XP[L].height)
        if L >= 1 and not f0 and not f1 and delta_uv is None:
            continue
        k0, k1 = s0 * s0 / 2, s1 * s1
        c0 = XP[L]
        if delta_uv is not None:   # reconstruction pass of its own (integer Up2), as LmcDenoise
            c0 = cpass(E(*sz, 4), XP[L], delta_uv, None, 1, False, 0.0, 0.0, (1.0, 1.0), 0.0, 0, False, sz)
        if f0:
            c0 = cpass(E(*sz, 4), c0, None, None, 2, True, k0 * GC[L][1] * rho[0] * sY, k0 * GC[L][1] * rho[1] * rY,
                       (UVS[L][1], UVS[L][3]), float(int(chroma[L + 1][1])), 0, False, sz)
        if L >= 1:
            out = cpass(E(*sz, 2), c0, None, XP[L], 1, f1, k1 * GC[L][0] * rho[0] * sY, k1 * GC[L][0] * rho[1] * rY,
                        (UVS[L][0], UVS[L][2]), float(int(chroma[L][1])), 1, False, sz)
        elif s == 1:
            out = cpass(E(w0, h0, 4), c0, None, None, 1, f1, k1 * GC[0][0] * rho[0] * sY, k1 * GC[0][0] * rho[1] * rY,
                        (UVS[0][0], UVS[0][2]), float(int(chroma[0][1])), 2, dark_fade, sz)
            output = out
        else:
            out = cpass(E(w0, h0, 4), c0, None, base, 1, f1, k1 * GC[0][0] * rho[0] * sY, k1 * GC[0][0] * rho[1] * rY,
                        (UVS[0][0], UVS[0][2]), float(int(chroma[0][1])), 3, False, sz, keep=keep2x)
        delta_uv = out
    if s == 2:
        info['delta'] = read(delta_uv)[..., :3]
        info['base'] = read(base)[..., :3]
        output = run('lmcdn/final2x', E(W, H, 4), {'InputBuffer': inp, 'Delta': delta_uv}, keepU=float(keep2x),
                     fadeU=int(dark_fade), darkFadeU=(0.0008, 0.003))
    res = read(output)[..., :3]
    for t in owned:
        t.release()
    inp.release()
    return res, info


def ref_denoise(rgb_full, s, info, model_y, rho, eff_map=None, dark_fade=False, keep2x=0.0):
    """The reference with the GPU run's measured gains and tiers (isolates the filter arithmetic)."""
    if s == 2:
        base = R.down2x(rgb_full)
    else:
        base = rgb_full
    x0 = R.to_yuv(base)
    strmap = None
    if eff_map is not None:
        smap = eff_map
        def strmap(shape):
            h, w = shape
            # bilinear sample of the half-resolution map at (p + 0.5) / levelSize (as the shaders)
            yy = (np.arange(h) + 0.5) / h * smap.shape[0] - 0.5
            xx = (np.arange(w) + 0.5) / w * smap.shape[1] - 0.5
            y0 = np.clip(np.floor(yy).astype(int), 0, smap.shape[0] - 1); x0_ = np.clip(np.floor(xx).astype(int), 0, smap.shape[1] - 1)
            y1 = np.clip(y0 + 1, 0, smap.shape[0] - 1); x1 = np.clip(x0_ + 1, 0, smap.shape[1] - 1)
            fy = np.clip(yy - np.floor(yy), 0, 1)[:, None]; fx = np.clip(xx - np.floor(xx), 0, 1)[None, :]
            fy = np.where((yy < 0)[:, None], 0, fy); fx = np.where((xx < 0)[None, :], 0, fx)
            a = smap[y0][:, x0_]; b = smap[y0][:, x1]; c = smap[y1][:, x0_]; d = smap[y1][:, x1]
            return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy
    out = R.denoise(x0, info['G'], info['UVS'], info['luma'], info['chroma'], model_y, rho, strmap,
                    out_rgb=(s == 1), base_rgb=base, dark_fade=dark_fade)
    if s == 2:
        return R.final2x(rgb_full, out, base, keep2x)
    return out


# ------------------------------------------------------------------ measurements
def bands(img, n=5):
    """Laplacian bands b0..b4 (b0 finest) with the 5-tap binomial, per channel; band b4 = residual of level 4."""
    k = np.array([1, 4, 6, 4, 1], np.float64) / 16
    def blur(a):
        for ax in (0, 1):
            m = a.shape[ax]
            a = sum(w * np.take(a, np.clip(np.arange(m) + i - 2, 0, m - 1), axis=ax) for i, w in enumerate(k))
        return a
    out = []
    g = img
    for b in range(n):
        lo = blur(g)
        if b == n - 1:
            out.append(g - lo)
            break
        out.append(g - lo)
        g = lo[::2, ::2]
    return out


def band_std(res, margin=16):
    """Std of each Laplacian band of a residual (h, w, 3 YUV), per channel, ignoring the borders."""
    bs = bands(res)
    stds = []
    for b, a in enumerate(bs):
        m = max(1, margin >> b)
        stds.append(a[m:-m, m:-m].reshape(-1, a.shape[-1]).std(0))
    return np.array(stds)  # (5 bands, 3 channels)


def synth(h=768, w=1024, seed=1, corr=False, n_frames=8, S=1.1e-3, O=4.6e-6, wp=(0.5, 1.0, 0.625)):
    """Piecewise flat test chart (grey and coloured patches, edges, a fine texture) in WB-RGB with the noise of a
    merge of n_frames frames: raw variance (S x + O) / n per channel, WB gain g = 1 / wp."""
    rng = np.random.default_rng(seed)
    g = 1 / np.asarray(wp)
    clean = np.zeros((h, w, 3))
    levels = np.geomspace(0.003, 0.12, 8)
    tint = [(1, 1, 1), (1.3, 1, .8), (.8, 1, 1.3), (1, 1.15, .9), (1.2, .9, 1.1), (1, 1, 1)]
    ph, pw = h // 6, w // 8
    for j in range(6):
        for i in range(8):
            clean[j * ph:(j + 1) * ph, i * pw:(i + 1) * pw] = levels[i] * np.array(tint[j])
    yy, xx = np.mgrid[0:h, 0:w]
    tex_area = (yy > 2 * ph + 10) & (yy < 3 * ph - 10) & (xx > 5 * pw + 10) & (xx < 6 * pw - 10)
    clean[tex_area] *= (1 + 0.15 * np.sin(xx[tex_area] * 0.9) * np.sin(yy[tex_area] * 0.7))[:, None]
    raw = clean / g
    noise = rng.standard_normal((h, w, 3))
    if corr:
        k = np.array([1, 2, 1]) / 4.0
        for ax in (0, 1):
            noise = sum(wk * np.roll(noise, i - 1, axis=ax) for i, wk in enumerate(k))
        noise /= noise.std()
    raw_noisy = raw + noise * np.sqrt((S * np.maximum(raw, 0) + O) / n_frames)
    return clean, raw_noisy * g, tex_area


def flat_mask(h, w, ph, pw, margin=12):
    m = np.zeros((h, w), bool)
    for j in range(6):
        for i in range(8):
            if j == 2 and i == 5:
                continue
            m[j * ph + margin:(j + 1) * ph - margin, i * pw + margin:(i + 1) * pw - margin] = True
    return m


def flat_ratio(inp, out, win=64, share=0.15):
    """Band std out/in over the flattest windows (lowest band-2 energy of the input Y relative to its level)."""
    yi = R.to_yuv(inp); yo = R.to_yuv(out)
    bi = bands(yi); bo = bands(yo)
    h, w = inp.shape[:2]
    scores = []
    for y in range(0, h - win + 1, win):
        for x in range(0, w - win + 1, win):
            lvl = max(yi[y:y + win, x:x + win, 0].mean(), 1e-4)
            b2 = bi[2][y // 4:(y + win) // 4, x // 4:(x + win) // 4, 0]
            scores.append((b2.std() / lvl, y, x))
    scores.sort()
    sel = scores[:max(1, int(len(scores) * share))]
    ratios = np.zeros((5, 3))
    for b in range(5):
        f = 1 << b
        num = np.concatenate([bo[b][y // f:(y + win) // f, x // f:(x + win) // f].reshape(-1, 3) for _, y, x in sel])
        den = np.concatenate([bi[b][y // f:(y + win) // f, x // f:(x + win) // f].reshape(-1, 3) for _, y, x in sel])
        ratios[b] = num.std(0) / np.maximum(den.std(0), 1e-12)
    return ('Y ' + ' '.join(f'{v:4.2f}' for v in ratios[:, 0]) + ' | U ' + ' '.join(f'{v:4.2f}' for v in ratios[:, 1])
            + ' | V ' + ' '.join(f'{v:4.2f}' for v in ratios[:, 2]) + f'  ({len(sel)} windows)')


def report_factors(name, clean, noisy, out, s=1):
    rin = R.to_yuv(noisy - clean); rout = R.to_yuv(out - clean)
    a = band_std(rin); b = band_std(rout)
    f = b / np.maximum(a, 1e-12)
    print(f'  {name}: noise out/in per band b0..b4  Y ' + ' '.join(f'{v:5.2f}' for v in f[:, 0])
          + ' | U ' + ' '.join(f'{v:5.2f}' for v in f[:, 1]) + ' | V ' + ' '.join(f'{v:5.2f}' for v in f[:, 2]))
    return f


def main():
    quick = '--quick' in sys.argv
    fails = []
    def check(cond, msg):
        print(('  ok   ' if cond else '  FAIL ') + msg)
        if not cond:
            fails.append(msg)
    rng = np.random.default_rng(7)
    print('0. Adreno precision (static): every lmcdn shader declares highp int (the fragment default is mediump, and a float')
    print('   made from a mediump int is mediump = fp16 on Adreno: 2q + 0.5 loses the 0.5 above 1024 px, 4-px steps above 4096);')
    print('   no texture() coordinate built from a pixel index except the bilinear-pair down filters (highp, small offsets)')
    for f in sorted((SH / 'lmcdn').glob('*.glsl')):
        src = f.read_text(encoding='utf-8')
        check('precision highp int;' in src, f'{f.name}: precision highp int')
        if f.name in ('cbf.glsl', 'final2x.glsl', 'lrecon.glsl', 'lbf.glsl', 'llow.glsl'):
            calls = [l.strip() for l in src.splitlines() if 'texture(' in l and 'StrMap' not in l and not l.strip().startswith('//')]
            check(not calls, f'{f.name}: Up2 / neighbours by integer texelFetch only' + (f' (found {calls[0]})' if calls else ''))
    q = np.arange(0, 4097, dtype=np.int64)
    err = np.abs((np.float16(2 * q).astype(np.float32) + np.float32(0.5)).astype(np.float16).astype(np.float64) - (2 * q + 0.5))
    print(f'   fp16 emulation of vec2(2q) + 0.5 (what a mediump int gives): error 0 below 2q = {2 * q[np.argmax(err > 0)]}, '
          f'max {err.max():.1f} px up to 8192')
    print('1. kernels')
    a = rng.random((67, 93)).astype(np.float32)
    ta = tex(a)
    d8 = read(run('lmcdn/down8', empty(47, 34, 1), {'InputBuffer': ta}))[..., 0]
    check(np.abs(d8 - R.down_kd8(a.astype(np.float64))).max() < 3e-3, f'kD8 down (bilinear pairs) vs exact taps: max {np.abs(d8 - R.down_kd8(a)).max():.2e}')
    a3 = rng.random((67, 93, 3))
    t3 = tex(a3)
    d4 = read(run('lmcdn/down4', empty(47, 34, 4), {'InputBuffer': t3}))[..., :3]
    check(np.abs(d4 - R.down_h4(a3)).max() < 2e-3, f'{{1,3,3,1}} down vs exact taps: max {np.abs(d4 - R.down_h4(a3)).max():.2e}')
    # BilateralFilter3x3 vs the reference and vs bf_sim.py (wrap) in the interior
    img = rng.standard_normal((96, 128)) * 0.01 + 0.05
    sig = 0.012
    for stride in (1, 2):
        for st_out in (0.25, 0.6, 1.0):
            g_ = run('lmcdn/lbf', empty(128, 96, 1), {'InputBuffer': tex(img, 'f4'), 'Recon': tex(img, 'f4')},
                     strideU=stride, filterU=1, noiseU=(0.0, sig * sig), thrU=st_out * 16, stageU=0, modeU=0)
            gg = read(g_, np.float16)[..., 0]
            rr = R.bf3x3(img, stride, sig, st_out * 16)
            dif = np.abs(gg - rr)
            check(np.percentile(dif, 99.9) < 2e-4, f'BF3x3 stride {stride} outlier {st_out}: GPU vs reference p99.9 {np.percentile(dif, 99.9):.1e} (fp16 out)')
    sys.path.insert(0, str(ROOT.parent / 'research/hybrid5/ref/denoise'))
    try:
        import importlib.util
        spec = importlib.util.spec_from_file_location('bf_sim', ROOT.parent / 'research/hybrid5/ref/denoise/bf_sim.py')
        src = (ROOT.parent / 'research/hybrid5/ref/denoise/bf_sim.py').read_text()
        ns = {}
        exec(src.split("N=256")[0], ns)
        wn = rng.standard_normal((128, 128))
        for stride in (1, 2):
            sim = ns['bf3'](wn, stride, 1.0 * math.sqrt(2.0 / stride), 0.6 * 16)
            ref = R.bf3x3(wn, stride, 1.0 * math.sqrt(2.0 / stride), 0.6 * 16)
            m = 3 * stride
            check(np.abs(sim - ref)[m:-m, m:-m].max() < 1e-12, f'reference BF3x3 == bf_sim.bf3 (interior, stride {stride})')
        print('  bf_sim residuals (white noise, sigma 1, our reference, one pass): ' + ', '.join(
            f's{st}:{R.bf3x3(wn, 1, st * math.sqrt(2.0), 16 * 0.6)[8:-8, 8:-8].std():.2f}' for st in (0.3, 0.6, 1.0, 1.5)))
    except Exception as e:
        check(False, f'bf_sim cross-check: {e}')
    # chroma bilateral
    x = np.stack([rng.standard_normal((80, 96)) * .01 + .05, rng.standard_normal((80, 96)) * .02, rng.standard_normal((80, 96)) * .02], -1)
    for stride in (1, 2):
        g_ = run('lmcdn/cbf', empty(96, 80, 4), {'InputBuffer': tex(x, 'f4')}, strideU=stride, filterU=1, noiseU=(0.0, 4e-4),
                 uvsU=(0.7, 0.8), thrU=5.0, modeU=0)
        gg = read(g_)[..., :3]
        rr = R.bfc3x3(x, stride, 4e-4, (0.7, 0.8), 5.0)
        dif = np.abs(gg - rr)
        check(np.percentile(dif, 99.9) < 2e-4, f'BilateralFilterChroma3x3 stride {stride}: GPU vs reference p99.9 {np.percentile(dif, 99.9):.1e}')
    # statistics
    lvl = np.stack([rng.standard_normal((320, 384)) * .01 + .05, rng.standard_normal((320, 384)) * .02, rng.standard_normal((320, 384)) * .03], -1)
    gs = gpu_stats(tex(lvl, 'f4'), 16, (1e-3, 1e-5)); rs = R.block_stats(lvl, 16, (1e-3, 1e-5))
    rel = np.abs(gs - rs) / np.maximum(np.abs(rs), 1e-3)
    check(rel.max() < 5e-3, f'block statistics GPU vs reference: max rel {rel.max():.1e}')
    gw = R.reduce_stats(rs)
    print(f'  white noise sigma 0.01/0.02/0.03, model var 1e-3*0.05+1e-5 = 6e-5: gY1 {gw[0]:.2f} (expect {2e-4/6e-5:.2f}), '
          f'gU1 {gw[2]:.2f} (expect {8e-4/6e-5:.1f}), uvsU {gw[6]:.3f} (expect 0.5), uvsV {gw[8]:.3f} (expect 0.333)')
    check(abs(gw[0] / (2e-4 / 6e-5) - 1) < 0.05 and abs(gw[2] / (8e-4 / 6e-5) - 1) < 0.05 and abs(gw[6] - 0.5) < 0.03 and abs(gw[8] - 1 / 3) < 0.02, 'measured gains of white noise (480 blocks)')

    print('2. tables (spec 2.4 values, exact ArkCam emulation: revert_max 9, coarse_stock 0, chroma floor 0)')
    ark = R.default_config(revert_max=9, coarse_stock=0, chroma_floor=0)
    l78, _ = R.luma_params(ark, 7.80806); c78, _ = R.chroma_params(ark, 7.80806)
    check(abs(l78[0][0] - 1.56) < .01 and abs(l78[0][1] - 4.37) < .015 and abs(l78[1][1] - 4.34) < .015 and abs(l78[2][2] - 0.67) < .01,
          f'SNR 7.8 luma b0 {l78[0][0]:.2f}/{l78[0][1]:.2f}/{l78[0][2]:.2f} b1 {l78[1][0]:.2f}/{l78[1][1]:.2f}/{l78[1][2]:.2f} b2 {l78[2][0]:.2f}/{l78[2][1]:.2f}/{l78[2][2]:.2f}')
    check(all(abs(c78[b][0] - v) < .02 for b, v in enumerate([2.75, 2.75, 2.75, 1.0, 1.72])), 'SNR 7.8 chroma ' + '/'.join(f'{c[0]:.2f}' for c in c78))
    l15, _ = R.luma_params(ark, 15)
    check(abs(l15[1][0] - 2.0) < .01 and abs(l15[0][1] - .875) < .01, f'SNR 15 luma b0 {l15[0][0]:.2f}/{l15[0][1]:.3f} b1 {l15[1][0]:.2f}')

    print('3. whole pipeline, 1x grid')
    hh, ww = (384, 512) if quick else (768, 1024)
    S = np.array([1.1e-3] * 3); O = np.array([4.6e-6] * 3); wp = (0.5, 1.0, 0.625)
    clean, noisy, tex_area = synth(hh, ww, wp=wp)
    rho = (1.2, 6.0)
    zero = R.default_config(luma_mult=0, chroma_mult=0)
    out0, inf0 = gpu_denoise(noisy, 1, zero, (S, O), rho, wp=wp)
    dif = np.abs(out0 - np.maximum(noisy.astype(np.float16).astype(np.float64), 0))
    rel0 = dif / np.maximum(noisy.mean(-1, keepdims=True), 1e-3)
    check(np.percentile(rel0, 99.9) < 4e-3 and rel0.mean() < 1e-3, f'identity with zero strengths (YUV round trip through fp16 textures): mean rel {rel0.mean():.1e}, p99.9 rel {np.percentile(rel0, 99.9):.1e}')
    g_ref = {L: R.reduce_stats(R.block_stats(inf0['pyr_luma'][L] if L else inf0['x0'], 16, (inf0['sY'], inf0['rY']))) for L in range(4)}
    print('   measured luma gains (GPU | numpy stats on the same levels): ' + '  '.join(
        f'L{L} {inf0["G"]["Y"][L][0]:.3f},{inf0["G"]["Y"][L][1]:.3f} | {g_ref[L][0]:.3f},{g_ref[L][1]:.3f}' for L in range(4)))
    check(all(abs(inf0['G']['Y'][L][0] / g_ref[L][0] - 1) < 0.03 for L in range(4)), 'GPU statistics vs numpy statistics on the GPU pyramid')
    # expected white-noise gain at level 0: the merge noise / single-frame model = 2/n (difference) for an 8-frame merge
    print(f'   level-0 gain {inf0["G"]["Y"][0][0]:.3f} (white noise of an 8-frame merge: 2/8 = 0.25), uvs L0 U {inf0["UVS"][0][0]:.2f} V {inf0["UVS"][0][2]:.2f}')
    print(f'   SNR estimate {inf0["snr_measured"]:.2f} (mu {inf0["mu"]:.4f}, display gain {inf0["gain"]:.1f})')
    results = {}
    cases = [('SNR 3 (SabreLow, chroma Low/Med), defaults', 3.0, R.default_config()),
             ('SNR 7.8 defaults', 7.8, R.default_config()),
             ('SNR 15 defaults', 15.0, R.default_config()),
             ('SNR 40 defaults', 40.0, R.default_config()),
             ('SNR 3, exact ArkCam (rf 9, no safeguards)', 3.0, ark),
             ('SNR 7.8, exact ArkCam', 7.8, ark),
             ('SNR 15, exact ArkCam', 15.0, ark)]
    if quick:
        cases = cases[:2] + cases[4:5]
    for name, snr, cfg in cases:
        t0 = time.time()
        out, inf = gpu_denoise(noisy, 1, cfg, (S, O), rho, snr_fixed=snr, wp=wp)
        ref = ref_denoise(noisy, 1, inf, (inf['sY'], inf['rY']), rho)
        dif = np.abs(out - ref)
        lvl = np.maximum(clean.mean(-1, keepdims=True), 1e-3)
        print(f' {name}: luma str {"/".join(f"{b[0]:.2f}" for b in inf["luma"])} rev {"/".join(f"{b[1]:.2f}" for b in inf["luma"])}'
              f' | chroma str {"/".join(f"{b[0]:.2f}" for b in inf["chroma"])}  ({time.time() - t0:.1f} s)')
        tol = max(1.0, max(b[1] for b in inf['luma']))   # the revert multiplies fp16 differences and threshold flips
        check(np.percentile(dif / lvl, 99) < 0.025 * tol and np.mean(dif / lvl) < 4e-3 * tol,
              f'GPU vs reference: mean rel {np.mean(dif / lvl):.1e}, p99 rel {np.percentile(dif / lvl, 99):.1e} (fp16 textures, tolerance x{tol:.0f})')
        fg = report_factors('GPU', clean, noisy, out)
        fr = report_factors('ref', clean, noisy, ref)
        check(np.abs(fg - fr).max() < 0.06, f'band factors GPU vs reference: max |diff| {np.abs(fg - fr).max():.3f}')
        results[name] = fg
        # edges: the noise-free chart through the filter keeps its steps
        if name.startswith('SNR 3 (') and not quick:
            fg = (inf['G']['Y'], inf['G']['C'], inf['UVS'])
            bright = (clean.mean(-1) >= 0.01) & ~tex_area
            dark = (clean.mean(-1) < 0.01) & ~tex_area
            # the clean chart through the noisy chart's thresholds: the bilateral filters themselves keep the patch edges
            # (revert <= 1); a revert > 1 adds back the smoothed part of sub-threshold edges as a halo, strength-5 chroma
            # bleeds colour across colour edges below its 5-sigma threshold (GCam's stock behaviour at SNR <= 5)
            ok_cfg = R.default_config(chroma_mult=0, chroma_floor=0, revert_max=1)
            oc, _ = gpu_denoise(clean, 1, ok_cfg, (S, O), rho, snr_fixed=snr, wp=wp, force_g=fg)
            rel = np.abs(oc - clean) / np.maximum(clean, 1e-3)
            check(np.percentile(rel[bright], 99.9) < 0.03, f'clean chart, luma with revert <= 1: patch edges kept, p99.9 rel change {np.percentile(rel[bright], 99.9):.3f}')
            for label, c2 in (('defaults (rf 2 + chroma 5)', cfg), ('luma only, rf 2', R.default_config(chroma_mult=0, chroma_floor=0)),
                              ('chroma only', R.default_config(luma_mult=0))):
                oc, _ = gpu_denoise(clean, 1, c2, (S, O), rho, snr_fixed=snr, wp=wp, force_g=fg)
                rel = np.abs(oc - clean) / np.maximum(clean, 1e-3)
                print(f'   clean chart, {label}: rel change at edges p99 / p99.9: patches >= 0.01 {np.percentile(rel[bright], 99):.3f} / '
                      f'{np.percentile(rel[bright], 99.9):.3f}, darker {np.percentile(rel[dark], 99):.3f} / {np.percentile(rel[dark], 99.9):.3f}; '
                      f'texture +-15 % mean {rel[tex_area].mean():.3f}')
    print('   correlated noise ([1 2 1] blur): measured gains change, filter thresholds follow')
    cl2, no2, _ = synth(hh, ww, seed=3, corr=True, wp=wp)
    out, inf = gpu_denoise(no2, 1, R.default_config(), (S, O), rho, snr_fixed=7.8, wp=wp)
    print('   gains L0..L3 ' + ' '.join(f'{inf["G"]["Y"][L][0]:.3f}/{inf["G"]["Y"][L][1]:.3f}' for L in range(4)))
    ref = ref_denoise(no2, 1, inf, (inf['sY'], inf['rY']), rho)
    fg = report_factors('GPU corr SNR 7.8', cl2, no2, out); fr = report_factors('ref corr SNR 7.8', cl2, no2, ref)
    check(np.abs(fg - fr).max() < 0.06, f'correlated: band factors GPU vs reference max |diff| {np.abs(fg - fr).max():.3f}')

    print('4. strength map "frames" (a Bento-like region from ~1 frame with 8x the noise variance)')
    cl3, no3, _ = synth(hh, ww, seed=5, wp=wp)
    eff = np.full((hh, ww), 64, np.uint8)
    reg = (slice(hh // 3, hh // 3 + hh // 4), slice(ww // 2, ww // 2 + ww // 4))
    eff[reg] = 8
    extra = np.random.default_rng(9).standard_normal(no3[reg].shape) * np.sqrt(7 * ((1.1e-3 * np.maximum(cl3[reg] * np.array(wp), 0) + 4.6e-6) / 8)) / np.array(wp)
    no3[reg] += extra
    outm, infm = gpu_denoise(no3, 1, R.default_config(), (S, O), rho, snr_fixed=7.8, eff=eff, eff_max=3.0, wp=wp)
    outu, infu = gpu_denoise(no3, 1, R.default_config(), (S, O), rho, snr_fixed=7.8, wp=wp, force_g=(infm['G']['Y'], infm['G']['C'], infm['UVS']))
    refm = ref_denoise(no3, 1, infm, (infm['sY'], infm['rY']), rho, eff_map=infm['strmap'])
    lvl = np.maximum(cl3.mean(-1, keepdims=True), 1e-3)
    check(np.mean(np.abs(outm - refm) / lvl) < 3e-3, f'frames map: GPU vs reference mean rel {np.mean(np.abs(outm - refm) / lvl):.1e}')
    sub = (slice(reg[0].start + 8, reg[0].stop - 8), slice(reg[1].start + 8, reg[1].stop - 8))
    rin = R.to_yuv(no3 - cl3)[sub]; rmap = R.to_yuv(outm - cl3)[sub]; runi = R.to_yuv(outu - cl3)[sub]
    print(f'   in the region: Y noise in {rin[..., 0].std():.2e} -> uniform {runi[..., 0].std():.2e}, frames {rmap[..., 0].std():.2e}; '
          f'U {rin[..., 1].std():.2e} -> {runi[..., 1].std():.2e} / {rmap[..., 1].std():.2e}  (map f max {infm["strmap"].max():.2f}, ref code {infm["effRef"]:.0f})')
    check(rmap[..., 1].std() < runi[..., 1].std(), 'frames map removes more noise in the low-frame region')

    print('5. Sabre 2x grid (pyramid from the sensor scale, Laplacian re-assembly)')
    h2, w2 = hh, ww
    cl_s, no_s, _ = synth(h2 // 2, w2 // 2, seed=11, wp=wp)
    up = lambda a: np.repeat(np.repeat(a, 2, 0), 2, 1)
    clean2 = up(cl_s)
    # 2x grid noise: the sensor-scale noise spread over 2x2 plus a fine part (the kernel averages of the donors)
    fine = np.random.default_rng(12).standard_normal((h2, w2, 3)) * np.sqrt((1.1e-3 * np.maximum(clean2 * np.array(wp), 0) + 4.6e-6) / 8 * 0.3) / np.array(wp)
    noisy2 = up(no_s) + fine
    o0, _ = gpu_denoise(noisy2, 2, zero, (S, O), rho, wp=wp, keep2x=1.0)
    rel0 = np.abs(o0 - np.maximum(noisy2.astype(np.float16).astype(np.float64), 0)) / np.maximum(noisy2.mean(-1, keepdims=True), 1e-3)
    check(np.percentile(rel0, 99.9) < 4e-3, f'2x identity with zero strengths and keep 1: p99.9 rel {np.percentile(rel0, 99.9):.1e}')
    ds = lambda a: a.reshape(a.shape[0] // 2, 2, a.shape[1] // 2, 2, 3).mean((1, 3))
    out1, _ = gpu_denoise(no_s, 1, R.default_config(), (S, O), rho, snr_fixed=7.8, wp=wp)
    f1 = report_factors('1x output of the same sensor-scale noise', cl_s, no_s, out1)
    for keep in (1.0, 0.0):
        out2, inf2 = gpu_denoise(noisy2, 2, R.default_config(), (S, O), rho, snr_fixed=7.8, wp=wp, keep2x=keep)
        ref2 = ref_denoise(noisy2, 2, inf2, (inf2['sY'], inf2['rY']), rho, keep2x=keep)
        lvl = np.maximum(clean2.mean(-1, keepdims=True), 1e-3)
        check(np.mean(np.abs(out2 - ref2) / lvl) < 4e-3, f'2x keep {keep}: GPU vs reference mean rel {np.mean(np.abs(out2 - ref2) / lvl):.1e}')
        report_factors(f'2x keep {keep}, 2x2 mean to the sensor scale', cl_s, ds(noisy2), ds(out2))
        report_factors(f'2x keep {keep}, on the 2x grid', clean2, noisy2, out2)

    print('6. despeckle against hot pixels (GPU)')
    cl4, no4, _ = synth(hh, ww, seed=21, wp=wp)
    hot = np.random.default_rng(22).integers(16, min(hh, ww) - 16, (60, 2))
    no4h = no4.copy()
    for (y, x_) in hot:
        no4h[y, x_] += np.array([0.3, 0.05, 0.02])
    od, _ = gpu_denoise(no4h, 1, R.default_config(), (S, O), rho, snr_fixed=7.8, despeckle=True, wp=wp)
    on, _ = gpu_denoise(no4h, 1, R.default_config(), (S, O), rho, snr_fixed=7.8, despeckle=False, wp=wp)
    rd = np.mean([np.abs(od[y, x_] - cl4[y, x_]).max() for y, x_ in hot]); rn = np.mean([np.abs(on[y, x_] - cl4[y, x_]).max() for y, x_ in hot])
    print(f'   hot pixel residual (max channel) with despeckle {rd:.4f}, without {rn:.4f} (added 0.3)')
    check(rd < 0.25 * rn, 'despeckle removes the isolated hot pixels before the pyramids')

    real = ROOT.parent / 'research/cct/cap2/denoise-in.f32'
    if real.exists() and not quick:
        print('7. real merged linear frame (research/cct/cap2, vivo ISO 2699, crop 1024x768)')
        full = np.memmap(real, np.float32, 'r', shape=(3072, 4096, 3))
        crop = np.array(full[1200:1968, 1500:2524], np.float64)
        S_r = np.array([2.6e-4] * 3); O_r = np.array([1.0e-6] * 3); wp_r = (0.453125, 1.0, 0.6269531)
        for snr in (3.0, 7.8, 15.0):
            outr, infr = gpu_denoise(crop, 1, R.default_config(), (S_r, O_r), rho, snr_fixed=snr, wp=wp_r)
            refr = ref_denoise(crop, 1, infr, (infr['sY'], infr['rY']), rho)
            lvl = np.maximum(crop.mean(-1, keepdims=True), 1e-3)
            check(np.mean(np.abs(outr - refr) / lvl) < 3e-3, f'real crop SNR {snr}: GPU vs reference mean rel {np.mean(np.abs(outr - refr) / lvl):.1e}')
            print(f'   SNR {snr}: luma str {"/".join(f"{b_[0]:.2f}" for b_ in infr["luma"])} rev {"/".join(f"{b_[1]:.2f}" for b_ in infr["luma"])}'
                  f' chroma str {"/".join(f"{b_[0]:.2f}" for b_ in infr["chroma"])}; flat-window band std out/in ' + flat_ratio(crop, outr))
        print('   measured gains L0..L3 ' + ' '.join(f'{infr["G"]["Y"][L][0]:.3f}/{infr["G"]["Y"][L][1]:.3f}' for L in range(4))
              + '  uvs L0 ' + f'{infr["UVS"][0][0]:.2f}/{infr["UVS"][0][2]:.2f}')
    print('failures:', len(fails))
    for f in fails:
        print('  -', f)
    sys.exit(1 if fails else 0)


if __name__ == '__main__':
    main()
