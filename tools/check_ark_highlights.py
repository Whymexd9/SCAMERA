#!/usr/bin/env python3
"""ARK tone highlights of saturated light (plan P11), on the production ark/combine.glsl.

Rows of hues (violet / magenta / deep blue / red from the OPPO Find X9 Ultra LED wall, then neutral and skin) are ramped
over 4 stops, every step as a +5 % / -5 % texture pair. The colour source is fed so that the fusion gain is 1 (the
linear input reaches the tone unchanged). Checks:
- monotone output along every ramp (texture off);
- the shoulder keeps at least 3 code values (sum of R, G, B, 8 bit) per 1/3 stop over the last 1.5 stops below the
  data ceiling (2 in the last 1/3 stop, where even a neutral reaches white);
- at least 80 % of the texture amplitude survives at the gamut boundary (against a neutral of the same input level);
- neutral and skin ramps stay within 2 code values of the reference render (--write-reference stores it);
- the violet band goes towards white: G of its brightest step >= 140.
Usage: check_ark_highlights.py [--report] [--write-reference]
"""
from pathlib import Path
import sys, json
import numpy as np
import moderngl

ROOT = Path(__file__).resolve().parents[1]
SH = ROOT / 'app/src/main/assets/shaders'
REF = ROOT / 'tools/fixtures/ark_highlights_reference.json'

HUES = {
    'violet': (1.14, 0.16, 1.71),   # ArkLow median of the X9U wall band (white-balanced linear)
    'magenta': (1.30, 0.20, 0.95),
    'deepblue': (0.12, 0.20, 1.60),
    'red': (1.50, 0.12, 0.10),
    'neutral': (0.50, 0.50, 0.50),
    'skin': (0.62, 0.45, 0.36),
}
STEPS = 48          # over 4 stops: 12 per stop
CLIP = 4.0          # data ceiling of the render (ArkAe clip)
TEX = 0.05
HL_WHITE = float(next((a.split('=')[1] for a in sys.argv if a.startswith('--white=')), 0.15))  # ark_hl_white default


def inverse_aces(y, toe=0.05):
    b = max(toe * 0.75, 0.005)
    e = max(0.14 - (toe - 0.04) * 0.8, 0.03)
    y = min(max(y, 0.0), 2.51 / 2.43 - 0.01)
    A = 2.43 * y - 2.51; B = 0.59 * y - b; C = e * y
    disc = B * B - 4 * A * C
    if disc < 0: return 0.0
    x = (-B - np.sqrt(disc)) / (2 * A - 1e-6)
    return max(x / 1.5, 0.0)


def fused_for(target):
    lo, hi = 0.0, 2.51 / 2.43 - 0.0101
    for _ in range(60):
        mid = (lo + hi) / 2
        if inverse_aces(mid) < target: lo = mid
        else: hi = mid
    return lo ** (1 / 2.2)


def source(name):
    s = (SH / name).read_text()
    for imp in ['interpolation', 'coords']:
        p = SH / ('utils/import_' + imp + '.glsl')
        if p.exists(): s = s.replace('#import ' + imp, p.read_text())
    return s


def render(combine_src, clip=CLIP, detail=1.0, headroom=1):
    try:
        ctx = moderngl.create_standalone_context(backend='egl', require=430)
    except Exception:
        ctx = moderngl.create_standalone_context(require=430)
    names = list(HUES)
    W, H = STEPS * 2, len(names)
    scales = 0.5 * 2.0 ** (np.arange(STEPS) / 12.0 - 1.0)      # 0.25 .. ~3.6 x the hue's base
    orig = np.zeros((H, W, 4), np.float32); full = np.zeros_like(orig); fused = np.zeros_like(orig)
    luma = np.array([0.2126, 0.7152, 0.0722])
    for r, n in enumerate(names):
        rgb = np.array(HUES[n])
        for i, s in enumerate(scales):
            c = rgb * s
            y = max(max(float(c @ luma), c.max() * 0.5), 1e-4)
            for k, t in enumerate((1 + TEX, 1 - TEX)):
                orig[r, 2 * i + k, :3] = c
                full[r, 2 * i + k, :3] = c * t
                fused[r, 2 * i + k, 0] = fused_for(y)
    def tex(a):
        t = ctx.texture((a.shape[1], a.shape[0]), 4, a.astype('f4').tobytes(), dtype='f4')
        t.filter = (moderngl.NEAREST, moderngl.NEAREST); return t
    ones = tex(np.ones((1, 1, 4), np.float32))
    t_full, t_orig, t_fused = tex(full), tex(orig), tex(fused)
    vs = '#version 300 es\nvoid main(){vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));gl_Position=vec4(p*2.-1.,0,1);}'
    prog = ctx.program(vertex_shader=vs, fragment_shader='#version 300 es\n' + combine_src)
    out = ctx.texture((W, H), 4, dtype='f4'); fb = ctx.framebuffer([out]); fb.use(); ctx.viewport = (0, 0, W, H)
    units = {'InputBuffer': t_full, 'GainMap': ones, 'ArkLow': t_orig, 'ArkColour': t_orig, 'ArkFused': t_fused,
             'ArkDetailRef': t_orig, 'ArkLumaS': t_orig}
    for i, (k, t) in enumerate(units.items()):
        if k in prog: t.use(i); prog[k].value = i
    eye = tuple(np.eye(3).flatten())
    vals = {'sensorToIntermediate': eye, 'intermediateToSRGB': eye, 'neutralPointU': (1, 1, 1), 'inScaleU': 1.0,
            'fU': 1, 'colourFU': 1, 'aeU': 1.0, 'clipU': clip, 'toeU': 0.05, 'gammaInvU': 1 / 2.2, 'macroU': 1.1,
            'vibU': (0.0, 0.4, 0.2), 'detailGainU': detail, 'deltaChromaU': 1.0, 'filmToeU': 0.1, 'ditherU': 0,
            'agxAU': (2.7, 1.35, 1.6, 1.0), 'agxBU': (-8.5, 3.5, 0.3, 4.0), 'headroomU': headroom, 'hlWhiteU': HL_WHITE}
    for k, v in vals.items():
        if k in prog: prog[k].value = v
    ctx.vertex_array(prog, []).render(vertices=3)
    img = np.frombuffer(out.read(), np.float32).reshape(H, W, 4)[:, :, :3] * 255.0
    ctx.release()
    return names, scales, img


def metrics(names, scales, img):
    luma = np.array([0.2126, 0.7152, 0.0722])
    res = {}
    for r, n in enumerate(names):
        plus, minus = img[r, 0::2], img[r, 1::2]
        mean = (plus + minus) / 2
        y = mean @ luma
        amp = (plus - minus) @ luma
        res[n] = {'mean': mean, 'y': y, 'amp': amp}
    return res


def check(report=False, write_reference=False):
    names, scales, img = render(source('ark/combine.glsl'))
    _, _, flat = render(source('ark/combine.glsl'), detail=0.0)
    m = metrics(names, scales, img); f = metrics(names, scales, flat)
    failures = []
    for n in names:
        s = f[n]['mean'].sum(1)
        drops = np.where(np.diff(f[n]['y']) < -0.25)[0]
        if len(drops): failures.append(f'{n}: output luminance falls at steps {drops[:5].tolist()}')
        # shoulder: the last 1.5 stops (18 steps) below the data ceiling (max input channel <= clip); per 1/3 stop
        # (4 steps) the channel sum must rise >= 3 codes
        inside = np.where(max(HUES[n]) * scales <= CLIP)[0]
        top = s[inside[-18:]] if len(inside) >= 22 else s[-18:]
        rise = top[4:] - top[:-4]
        # the last 1/3 stop reaches the ceiling itself (a neutral rises ~2 codes there): >= 2
        if n not in ('neutral', 'skin') and (rise[:-1].min() < 3.0 or rise[-1] < 2.0):
            failures.append(f'{n}: shoulder rises only {rise.min():.1f} codes per 1/3 stop')
    for n in ('violet', 'magenta', 'deepblue', 'red'):
        a, ref = m[n]['amp'][-1], m['neutral']['amp']
        # neutral of the same input luminance
        yin = np.array(HUES[n]) @ np.array([0.2126, 0.7152, 0.0722]) * scales[-1]
        k = int(np.argmin(np.abs(0.5 * scales - yin)))
        if a < 0.8 * ref[k]: failures.append(f'{n}: texture at the top {a:.2f} < 80 % of neutral {ref[k]:.2f}')
    # without real headroom (unclipped data, WB headroom only) the Bento shoulders are off: still monotone, still to white
    _, _, plain = render(source('ark/combine.glsl'), detail=0.0, headroom=0)
    p0 = metrics(names, scales, plain)
    for n in names:
        drops = np.where(np.diff(p0[n]['y']) < -0.25)[0]
        if len(drops): failures.append(f'{n} (no headroom): output luminance falls at steps {drops[:5].tolist()}')
    if p0['violet']['mean'][-1, 1] < 140: failures.append('violet (no headroom): no path to white')
    g = f['violet']['mean'][-1, 1]
    if g < 140: failures.append(f'violet top G {g:.0f} < 140 (no path to white)')
    if write_reference:
        REF.parent.mkdir(exist_ok=True)
        REF.write_text(json.dumps({n: f[n]['mean'].round(2).tolist() for n in ('neutral', 'skin')}))
    elif REF.exists():
        ref = json.loads(REF.read_text())
        for n in ('neutral', 'skin'):
            d = np.abs(np.array(ref[n]) - f[n]['mean']).max()
            if d > 2.0: failures.append(f'{n}: {d:.1f} codes away from the reference render')
    if report:
        for n in names:
            mm = f[n]['mean']
            print(f'{n:9s} low {mm[0].round(0)} mid {mm[24].round(0)} top {mm[-1].round(0)} amp(top) {m[n]["amp"][-1]:.2f}')
    if failures:
        print('ARK highlights FAIL:\n  ' + '\n  '.join(failures))
        return 1
    print('ARK highlights PASS: monotone ramps, shoulder code-value floor, texture at the gamut boundary, neutral/skin unchanged, path to white')
    return 0


if __name__ == '__main__':
    sys.exit(check('--report' in sys.argv, '--write-reference' in sys.argv))
