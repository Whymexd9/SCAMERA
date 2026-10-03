#!/usr/bin/env python3
"""Run the ARK tone shaders (assets/shaders/ark/*) on the desktop GPU (moderngl, GL 4.3) and compare them with the
numpy reference of the ArkCam 1.23 / LMC 9.6 photo tone (research/hybrid5/ref/tone/ark_tone.py, itself checked against
the native code and a real LMC JPEG). The host logic between the passes mirrors ArkStats / ArkFusion / ArkCombine.

Usage: python tools/check_ark_tone.py [path/to/gclean.npy]   (default: the vivo G_CLEAN of the tone research, if present)
Pass criterion (tone_port.md 7.8): mean |difference| <= 1/255 after a sigma=3 blur, per case.
"""
import sys, re
from pathlib import Path
import numpy as np
import moderngl

ROOT = Path(__file__).resolve().parents[1]
SHADERS = ROOT / 'app/src/main/assets/shaders'
REF = ROOT.parent / 'research/hybrid5/ref/tone'
sys.path.insert(0, str(REF))
import ark_ae, ark_tone  # noqa: E402

# ArkCam 2.85 X8U effective settings (tone_port.md section 3) in the reference's key names.
S285 = {'pref_smart_hdr_ae_target_key': 0.15, 'pref_smart_hdr_ae_max_boost_key': 5.0, 'pref_smart_hdr_ae_min_limit_key': 0.5,
        'pref_ae_hl_overflow_key': 2.0, 'pref_ae_hl_blend_key': 0.33, 'pref_ae_night_thresh_key': 100.0, 'pref_ae_night_dim_key': 1.0,
        'pref_smart_hdr_ae_face_priority_key': 0.7, 'pref_smart_hdr_ae_metering_key': 0, 'pref_smart_hdr_bright_thresh_key': 0.8,
        'pref_smart_hdr_dark_thresh_key': 15.0, 'pref_smart_hdr_dark_pixel_thresh_key': 10.0, 'pref_smart_hdr_hl_boost_key': 1.0,
        'pref_smart_hdr_contrast_boost_key': 0.5, 'pref_sharp_ef_shadow_str_key': 2.0, 'pref_sharp_ef_highlight_str_key': 2.0,
        'pref_sharp_ef_enabled_key': 1, 'pref_agx_look_key': 4, 'pref_agx_slope_key': 2.7, 'pref_agx_sp_key': 1.35, 'pref_agx_tp_key': 1.6,
        'pref_agx_min_ev_key': -8.5, 'pref_agx_max_ev_key': 3.5, 'pref_agx_sat_boost_key': 1.0, 'pref_agx_ev_key': 0.3,
        'pref_ef_tonemap_operator_key': 0, 'pref_uchi_a_key': 1.0, 'pref_ef_aces_d_coeff_key': 0.59,
        'pref_ef_gamma_key': 2.2, 'pref_sharp_ef_weight_center_key': 0.6, 'pref_sharp_ef_blend_smoothness_key': 0.25,
        'pref_sharp_ef_weight_hl_key': 0.8, 'pref_sharp_ef_weight_mid_key': 1.0, 'pref_sharp_ef_weight_ext_hl_key': 0.7,
        'pref_sharp_ef_weight_shadow_key': 0.5, 'pref_ef_gf_radius_key': 32.0, 'pref_ef_gf_eps_key': 0.001, 'pref_ef_aces_toe_key': 0.05,
        'pref_bracket_denoise_key': 1.0, 'pref_sharp_ef_macro_contrast_key': 1.1, 'pref_vibrance_strength_key': 0.0,
        'pref_vibrance_sky_strength_key': 0.4, 'pref_vibrance_green_strength_key': 0.2, 'pref_sharp_ef_chroma_denoise_key': 0.0,
        'pref_sharp_ef_clarity_key': 0.0, 'pref_sharp_ef_flat_protect_key': 0.0, 'pref_ef_film_toe_key': 0.1}

ctx = moderngl.create_standalone_context(require=430)
VS = '#version 430\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.0-1.0,0,1);}'
_progs = {}


def source(name, defines):
    out = ['#version 430']
    for line in (SHADERS / (name + '.glsl')).read_text(encoding='utf-8').splitlines():
        m = re.match(r'\s*#import\s+(\S+)', line)
        if m:
            out.append((SHADERS / 'utils' / ('import_' + m.group(1) + '.glsl')).read_text(encoding='utf-8'))
            continue
        d = re.match(r'\s*#define\s+(\w+)\s+', line)
        if d and d.group(1) in defines:
            line = '#define %s %s' % (d.group(1), defines[d.group(1)])
        out.append(line)
    return '\n'.join(out)


def prog(name, **defines):
    key = (name, tuple(sorted(defines.items())))
    if key not in _progs:
        _progs[key] = ctx.program(vertex_shader=VS, fragment_shader=source(name, defines))
    return _progs[key]


def tex(a, dtype='f4', linear=False):
    a = np.ascontiguousarray(a, dtype=np.float32 if dtype == 'f4' else np.float16)
    if a.ndim == 2:
        a = a[..., None]
    t = ctx.texture((a.shape[1], a.shape[0]), a.shape[2], a.tobytes(), dtype=dtype)
    t.filter = (moderngl.LINEAR, moderngl.LINEAR) if linear else (moderngl.NEAREST, moderngl.NEAREST)
    t.repeat_x = t.repeat_y = False
    return t


def run(name, size, textures, uniforms, dtype='f4', **defines):
    p = prog(name, **defines)
    out = ctx.texture(size, 4, dtype=dtype)
    out.filter = (moderngl.NEAREST, moderngl.NEAREST)
    fbo = ctx.framebuffer([out])
    fbo.use()
    ctx.viewport = (0, 0, size[0], size[1])
    for unit, (k, t) in enumerate(textures.items()):
        t.use(unit)
        if k in p:
            p[k].value = unit
    for k, v in uniforms.items():
        if k in p:
            p[k].value = v
    ctx.vertex_array(p, []).render(vertices=3)
    ctx.finish()
    fbo.release()
    return out


def read(t):
    dt = np.float16 if t.dtype == 'f2' else np.float32
    return np.frombuffer(t.read(), dt).astype(np.float32).reshape(t.height, t.width, 4)


IDENT = (1, 0, 0, 0, 1, 0, 0, 0, 1)


def ark_gpu(full, f, S, iso=0, max_iso=0, detail=0.0, colour_from_mid=False, raw=False):
    """full: HxWx3 linear Rec.709 at the output grid. Returns (8-bit output, ae result) as ArkStats -> ArkCombine;
    raw=True: (float output RGBA, ae result, arkLow pixels)."""
    H, W = full.shape[:2]
    inp = tex(np.concatenate([full, np.ones((H, W, 1), np.float32)], -1))
    gm = tex(np.ones((1, 1, 4), np.float32), linear=True)
    colour = {'sensorToIntermediate': IDENT, 'intermediateToSRGB': IDENT, 'neutralPointU': (1.0, 1.0, 1.0), 'inScaleU': 1.0}
    lw, lh = (W + f - 1) // f, (H + f - 1) // f
    low = run('ark/low', (lw, lh), {'InputBuffer': inp, 'GainMap': gm}, dict(colour, factorU=f), dtype='f2')
    lowpix = read(low)[..., :3]
    ae, clip, S2, st = ark_ae.smart_hdr(lowpix, S, iso=iso, max_iso=max_iso)
    src = low
    if clip > 1.05 and S2['pref_bracket_denoise_key'] > 0.001:
        src = run('ark/bracket_dn', (lw, lh), {'InputBuffer': low}, {'strengthU': S2['pref_bracket_denoise_key']}, dtype='f2')
    effH, effS = S2['pref_sharp_ef_highlight_str_key'], S2['pref_sharp_ef_shadow_str_key']
    em = (2.0 ** (-1.5 * effH), 1.0, min(2.0 ** effS, 8.0), min(2.0 ** (2 * effS), 16.0))
    lw4 = (S2['pref_sharp_ef_weight_hl_key'], S2['pref_sharp_ef_weight_mid_key'], S2['pref_sharp_ef_weight_ext_hl_key'], S2['pref_sharp_ef_weight_shadow_key'])
    gfu = {'expU': em, 'layerU': lw4, 'aeU': ae, 'centerU': S2['pref_sharp_ef_weight_center_key'],
           'smoothU': S2['pref_sharp_ef_blend_smoothness_key'], 'gammaInvU': 1.0 / S2['pref_ef_gamma_key'], 'toeU': S2['pref_ef_aces_toe_key']}
    dw, dh = (lw + 1) // 2, (lh + 1) // 2
    r = int(S2['pref_ef_gf_radius_key']); r = max(r, 1); r = (r >> 1) if r > 1 else 1

    def box(t):
        h = run('ark/box', (dw, dh), {'InputBuffer': t}, {'dirU': (1, 0), 'radiusU': r})
        return run('ark/box', (dw, dh), {'InputBuffer': h}, {'dirU': (0, 1), 'radiusU': r})
    mII = box(run('ark/gf', (dw, dh), {'InputBuffer': src}, gfu, MODE=0))
    mP = box(run('ark/gf', (dw, dh), {'InputBuffer': src}, gfu, MODE=1))
    mIP = box(run('ark/gf', (dw, dh), {'InputBuffer': src}, gfu, MODE=2))
    eps = {'epsU': max(S2['pref_ef_gf_eps_key'], 1e-6)}
    a = box(run('ark/ab', (dw, dh), {'MeanII': mII, 'MeanP': mP, 'MeanIP': mIP}, eps, MODE=0))
    b = box(run('ark/ab', (dw, dh), {'MeanII': mII, 'MeanP': mP, 'MeanIP': mIP}, eps, MODE=1))
    fused = run('ark/gf', (lw, lh), {'InputBuffer': src, 'MeanA': a, 'MeanB': b}, gfu, MODE=3)
    colour_tex, fc = src, f
    if colour_from_mid:
        colour_tex = run('ark/low', ((W + 1) // 2, (H + 1) // 2), {'InputBuffer': inp, 'GainMap': gm}, dict(colour, factorU=2), dtype='f2')
        fc = 2
    # Bounded reference of the detail delta (ArkCombine, review_arktone F1): box mean of min(ae * Y709, 1).
    ref = run('ark/low', (lw, lh), {'InputBuffer': inp, 'GainMap': gm}, dict(colour, factorU=f, detailClipU=ae), DETAIL_REF=1) \
        if detail != 0.0 else low
    look = S2['pref_agx_look_key']
    comb = {'fU': f, 'colourFU': fc, 'aeU': ae, 'clipU': clip, 'toeU': S2['pref_ef_aces_toe_key'], 'gammaInvU': 1.0 / S2['pref_ef_gamma_key'],
            'detailRefU': 1 if detail != 0.0 else 0,
            'macroU': S2['pref_sharp_ef_macro_contrast_key'],
            'vibU': (S2['pref_vibrance_strength_key'], S2['pref_vibrance_sky_strength_key'], S2['pref_vibrance_green_strength_key']),
            'chromaDnU': S2['pref_sharp_ef_chroma_denoise_key'], 'clarityU': S2['pref_sharp_ef_clarity_key'],
            'flatProtectU': S2['pref_sharp_ef_flat_protect_key'], 'detailGainU': detail, 'filmToeU': S2['pref_ef_film_toe_key'],
            'agxAU': (S2['pref_agx_slope_key'], S2['pref_agx_sp_key'], S2['pref_agx_tp_key'], S2['pref_agx_sat_boost_key']),
            'agxBU': (S2['pref_agx_min_ev_key'], S2['pref_agx_max_ev_key'], S2['pref_agx_ev_key'], float(look)), 'ditherU': 0, 'guardU': 0.0}
    comb.update(colour)
    out = run('ark/combine', (W, H), {'InputBuffer': inp, 'GainMap': gm, 'ArkLow': low, 'ArkColour': colour_tex, 'ArkFused': fused,
                                      'ArkDetailRef': ref}, comb, dtype='f2')
    o = read(out)
    for t in {id(t): t for t in (inp, gm, low, src, mII, mP, mIP, a, b, fused, out, colour_tex, ref)}.values():
        t.release()
    if raw:
        return o, (ae, clip, st), lowpix
    return np.clip(np.round(o[..., :3] * 255), 0, 255).astype(np.uint8), (ae, clip, st)


def to_oklab_L(rgb):
    return ark_tone.to_oklab(np.maximum(rgb, 0))[..., 0]


def bspline_f(img, f, H, W):
    """B-spline sample of a low grid (box factor f) at every output pixel, with the integer mapping of combine.glsl."""
    def axis(n_out, n_low):
        x = np.arange(n_out); q = x // f; r = x - q * f
        c = (2 * r + 1 - f) / (2.0 * f)
        base = np.where(c < 0, q - 1, q); frac = np.where(c < 0, c + 1, c)
        w = np.stack([(1 - 3 * frac + 3 * frac ** 2 - frac ** 3) / 6, (4 - 6 * frac ** 2 + 3 * frac ** 3) / 6,
                      (1 + 3 * frac + 3 * frac ** 2 - 3 * frac ** 3) / 6, frac ** 3 / 6], -1)
        idx = np.clip(base[:, None] + np.arange(-1, 3)[None], 0, n_low - 1)
        return idx, w
    iy, wy = axis(H, img.shape[0]); ix, wx = axis(W, img.shape[1])
    out = np.zeros((H, W), np.float64)
    for j in range(4):
        for i in range(4):
            out += img[iy[:, j]][:, ix[:, i]] * wy[:, j][:, None] * wx[:, i][None, :]
    return out


def boxmean(a, f):
    """Box mean of factor f with the edge clamp of ark/low.glsl (the last box repeats the edge pixel)."""
    H, W = a.shape
    lh, lw = (H + f - 1) // f, (W + f - 1) // f
    p = np.pad(a, ((0, lh * f - H), (0, lw * f - W)), mode='edge')
    return p.reshape(lh, f, lw, f).mean(axis=(1, 3))


def detail_check():
    """The detail delta of the full-size input (the part ArkCam takes from Google's guide): output OKLab L with the
    detail minus without it must equal delta * s^2 * (1 - smoothstep(0.75, 1, L) / 2), delta = cbrt(c) -
    cbrt(B-spline(box mean c)), c = min(ae Y_full, 1) (bounded like the kernel's a3), s = min(1, L / cbrt(reference)),
    wherever no clamp, film toe or channel clip interferes."""
    H, W = 384, 512
    y, x = np.mgrid[0:H, 0:W].astype(np.float64)
    base = 0.02 + 0.25 * (x / W) * (0.5 + 0.5 * y / H)
    tex_ = 1.0 + 0.15 * np.sin(x * 1.3) * np.cos(y * 0.9) + 0.1 * ((x.astype(int) + y.astype(int)) % 2)
    full = np.stack([base * tex_ * 0.9, base * tex_, base * tex_ * 0.8], -1).astype(np.float32)
    o0, (ae, clip, st), low = ark_gpu(full, 2, dict(S285), detail=0.0, raw=True)
    o1, _, _ = ark_gpu(full, 2, dict(S285), detail=1.0, raw=True)
    yfull = full.astype(np.float64) @ np.array([0.2126, 0.7152, 0.0722])
    c = np.minimum(np.maximum(yfull, 0) * ae, 1.0)
    ref = np.cbrt(np.maximum(bspline_f(np.maximum(boxmean(c, 2), 1e-6), 2, H, W), 1e-6))
    delta = np.cbrt(np.maximum(c, 1e-6)) - ref
    g = 2.2
    L0 = to_oklab_L(o0[..., :3].astype(np.float64) ** g); L1 = to_oklab_L(o1[..., :3].astype(np.float64) ** g)
    s = np.minimum(1.0, L0 / ref)
    t = np.clip((L0 - 0.75) / 0.25, 0, 1)
    expected = delta * s * s * (1 - t * t * (3 - 2 * t) * 0.5)
    luma = o0[..., :3] @ np.array([0.2126, 0.7152, 0.0722])
    valid = (luma > 0.17) & (o1[..., :3].max(-1) < 0.97) & (o0[..., :3].max(-1) < 0.97) & (L0 + expected < 0.99)
    valid[:4] = valid[-4:] = False; valid[:, :4] = valid[:, -4:] = False
    err = np.abs((L1 - L0) - expected)[valid]
    ok = valid.sum() > 1000 and np.percentile(err, 99) < 3e-3 and np.median(err) < 1e-3
    print('detail delta f=2: ae %.3f, %d px, |delta| p50 %.4f p99 %.4f, error p50 %.5f p99 %.5f  %s' % (
        ae, valid.sum(), np.median(np.abs(expected[valid])), np.percentile(np.abs(expected[valid]), 99),
        np.median(err), np.percentile(err, 99), 'PASS' if ok else 'FAIL'))
    return ok


def lamp_edge_check():
    """Regression of review_arktone F1: a light far above white (Bento headroom) on a dim textured background. The
    detail delta must not draw a dark ring outside the light: the 5th percentile of the 8-bit luma in a 6 px ring
    with the detail has to stay above 70 % of the background (an ordinary edge undershoot is ~20 %), on the 1x and
    2x grids. The unbounded delta gave 0..7 against a background of ~108 (a black ring around every lamp)."""
    H, W = 384, 512
    y, x = np.mgrid[0:H, 0:W].astype(np.float64)
    Y = (0.05 + 0.10 * x / W) * (1 + 0.03 * np.sin(x * 1.7) * np.cos(y * 1.1))
    r = np.hypot(x - 256, y - 192)
    Y[r < 40] = 3.5
    full = np.repeat(Y[..., None], 3, -1).astype(np.float32)
    ring = (r > 40) & (r < 47)
    far = (r > 80) & (r < 110)
    ok = True
    for f in (2, 4):
        p5, bg = [], 0.0
        for detail in (0.0, 1.0):
            o, (ae, clip, st), _ = ark_gpu(full, f, dict(S285), detail=detail, raw=True, colour_from_mid=(f == 4))
            luma = 255.0 * np.clip(o[..., :3], 0, 1) @ np.array([0.2126, 0.7152, 0.0722])
            p5.append(np.percentile(luma[ring], 5))
            bg = np.median(luma[far])
        good = p5[1] >= 0.7 * bg
        ok &= good
        print('lamp edge f=%d: background %.1f, ring luma p5 without detail %.1f, with detail %.1f  %s' % (
            f, bg, p5[0], p5[1], 'PASS' if good else 'FAIL'))
    return ok


def colour_and_guard_check():
    """ark/low.glsl colour chain (matrices, white point, LSC gain, input scale, box mean) and ark/guard.glsl."""
    rng = np.random.default_rng(3)
    H, W = 37, 53
    cam = rng.uniform(0.0, 2.0, (H, W, 3)).astype(np.float32)
    m1 = rng.uniform(-0.3, 1.2, (3, 3)); m2 = rng.uniform(-0.3, 1.2, (3, 3)); wp = np.array([0.55, 1.0, 0.45]); scale = 1.7
    inp = tex(np.concatenate([cam, np.ones((H, W, 1), np.float32)], -1))
    gm = tex(np.full((3, 3, 4), 1.3, np.float32), linear=True)
    u = {'sensorToIntermediate': tuple(m1.T.ravel()), 'intermediateToSRGB': tuple(m2.T.ravel()), 'neutralPointU': tuple(wp),
         'inScaleU': scale, 'factorU': 4}
    low = read(run('ark/low', ((W + 3) // 4, (H + 3) // 4), {'InputBuffer': inp, 'GainMap': gm}, u))[..., :3]
    lin = (cam.astype(np.float64) * wp * 1.3 * scale) @ m1.T @ m2.T
    pad = np.pad(lin, ((0, 3 - (H - 1) % 4), (0, 3 - (W - 1) % 4), (0, 0)), mode='edge')
    exp = pad.reshape(pad.shape[0] // 4, 4, pad.shape[1] // 4, 4, 3).mean(axis=(1, 3))
    e1 = np.abs(low - exp).max() / np.abs(exp).max()
    pre = rng.uniform(0, 1, (H, W, 4)).astype(np.float32); sharp = rng.uniform(0, 1, (H, W, 4)).astype(np.float32)
    out = read(run('ark/guard', (W, H), {'InputBuffer': tex(sharp), 'PreBuffer': tex(pre)}, {}))
    e2 = np.abs(out[..., :3] - (pre[..., :3] + pre[..., 3:] * (sharp[..., :3] - pre[..., :3]))).max()
    ok = e1 < 1e-4 and e2 < 1e-5 and np.all(out[..., 3] == 1)
    print('arkLow colour chain rel. error %.2e, guard error %.2e  %s' % (e1, e2, 'PASS' if ok else 'FAIL'))
    return ok


def blur3(a):
    from scipy.ndimage import gaussian_filter
    return gaussian_filter(a.astype(np.float32), sigma=(3, 3, 0))


def compare(name, ours, ref, limit=1.0):
    d = np.abs(ours.astype(np.float32) - ref.astype(np.float32))
    db = np.abs(blur3(ours) - blur3(ref))
    ok = db.mean() <= limit
    print('%-34s raw MAE %.3f max %3d p99.9 %.1f | sigma3 MAE %.4f max %.3f  %s' % (
        name, d.mean(), int(d.max()), np.percentile(d, 99.9), db.mean(), db.max(), 'PASS' if ok else 'FAIL'))
    return ok


def synthetic(H=384, W=512):
    y, x = np.mgrid[0:H, 0:W].astype(np.float64)
    t = x / (W - 1); u = y / (H - 1)
    base = 0.0005 * np.power(2.0, 13.0 * t)                       # 0.0005 .. 4 (13 stops)
    img = np.stack([base * (0.8 + 0.4 * u), base * (1.0 - 0.2 * u), base * (0.6 + 0.6 * (1 - u))], -1)
    img[40:120, 40:200] = [0.30, 0.05, 0.03]                      # saturated red
    img[150:230, 40:200] = [0.03, 0.20, 0.30]                     # sky blue
    img[260:340, 40:200] = [0.04, 0.25, 0.03]                     # foliage green
    img[60:160, 300:420] *= 3.0                                   # Bento-like headroom above 1
    img += (0.002 * np.sin(x * 0.9) * np.cos(y * 0.7))[..., None]  # fine texture
    return np.maximum(img, 0).astype(np.float32)


def main():
    ok = colour_and_guard_check()
    ok &= detail_check()
    ok &= lamp_edge_check()
    syn = synthetic()
    for iso in (0, 6400):
        ref, st, _ = ark_tone.ark_photo_tone(syn, dict(S285), iso=iso, max_iso=6400)
        ours, (ae, clip, st2) = ark_gpu(syn, 1, dict(S285), iso=iso, max_iso=6400)
        print('synthetic iso %d: ae %.4f clip %.2f effS %.3f effH %.3f' % (iso, ae, clip, st2.get('eff_S', 0), st2.get('eff_H', 0)))
        ok &= compare('synthetic f=1 iso %d' % iso, ours, ref)
    g_path = Path(sys.argv[1]) if len(sys.argv) > 1 else Path('C:/Users/MECHREVO/AppData/Local/Temp/claude/C--Users-MECHREVO-Downloads-x200u/016880ac-3f01-4443-9f4b-35ee907b6811/scratchpad/h5_tone/vivo_gclean.npy')
    if g_path.is_file():
        g = np.load(g_path).astype(np.float32)
        for iso in (12800, 0):
            ref, st, _ = ark_tone.ark_photo_tone(g, dict(S285), iso=iso, max_iso=12800)
            ours, (ae, clip, st2) = ark_gpu(g, 1, dict(S285), iso=iso, max_iso=12800)
            print('vivo G_CLEAN iso %d: ae %.4f clip %.2f effS %.3f effH %.3f' % (iso, ae, clip, st2.get('eff_S', 0), st2.get('eff_H', 0)))
            ok &= compare('vivo G_CLEAN f=1 iso %d' % iso, ours, ref)
        # 1x grid of the app: the output is twice arkLow (nearest-upsampled crop, so arkLow == G_CLEAN crop exactly);
        # base only (detail 0), compared at the G_CLEAN size after a 2x2 mean.
        crop = g[384:1152, 512:1536]
        ref, st, _ = ark_tone.ark_photo_tone(crop, dict(S285), iso=12800, max_iso=12800)
        up = np.repeat(np.repeat(crop, 2, 0), 2, 1)
        for detail in (0.0, 1.0):
            ours, (ae, clip, st2) = ark_gpu(up, 2, dict(S285), iso=12800, max_iso=12800, detail=detail)
            o = ours.astype(np.float32).reshape(crop.shape[0], 2, crop.shape[1], 2, 3).mean(axis=(1, 3))
            res = compare('vivo crop f=2 detail %.0f (2x2 mean)' % detail, o, ref)
            if detail == 0.0:
                ok &= res
        # 2x grid of the app: arkLow is a quarter of the output, the colour comes from the 2x2 mean (arkMid).
        quarter = crop[:384, :512]
        ref4, _, _ = ark_tone.ark_photo_tone(quarter, dict(S285), iso=12800, max_iso=12800)
        ours, _ = ark_gpu(np.repeat(np.repeat(quarter, 4, 0), 4, 1), 4, dict(S285), iso=12800, max_iso=12800, colour_from_mid=True)
        o = ours.astype(np.float32).reshape(384, 4, 512, 4, 3).mean(axis=(1, 3))
        ok &= compare('vivo crop f=4, colour arkMid (4x4 mean)', o, ref4)
    else:
        print('no G_CLEAN at', g_path, '- synthetic cases only')
    print('ARK tone shaders:', 'PASS' if ok else 'FAIL')
    sys.exit(0 if ok else 1)


if __name__ == '__main__':
    main()
