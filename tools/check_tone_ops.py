#!/usr/bin/env python3
"""P144 «Кривая AgX и ACES»: ark/combine.glsl with TONE_OP 1 on the GPU (moderngl).

A ramp of greys goes through the shader with each operator and is compared with a numpy reference of the same chain (for a
grey: the operator on the AgX inset value, the outset, the display power 1/2.2 and the film toe below 0.15):
- ACES (ArkCam eval_acescg) and Uchimura (uchimura_curve) at ArkCam's defaults and with other values;
- the own curve: the straight line and ArkCam's S (processing.color.ToneCurve, the same monotone cubic);
- every operator rises with the input and stays in 0..1; TONE_OP 0 still renders the AgX of before (check_colour_output pins it).
"""
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from check_colour_output import SH, expand_imports, set_define  # noqa: E402

FAILURES = []


def check(cond, msg):
    if not cond:
        FAILURES.append(msg)
        print('FAIL: ' + msg)


def aces(v, toe=0.05, pre=1.0, a=2.61, d=0.59):
    x = np.maximum(v, 0) * pre
    b = max(toe * 0.75, 0.005)
    e = max(0.14 - (toe - 0.04) * 0.8, 0.03)
    return x * (a * x + b) / (x * (2.43 * x + d) + e)


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0, 1)
    return t * t * (3 - 2 * t)


def uchimura(v, pre=1.0, post=0.9, P=1.0, a=1.0, m=0.22, l=0.4, c=1.33, b=0.0):
    x = np.maximum(v, 0) * pre
    l0 = (P - m) * l / a
    s0, s1 = m + l0, m + a * l0
    c2 = a * P / (P - s1)
    cp = -c2 / P
    w0 = 1 - smoothstep(0, m, x)
    w2 = (x >= m + l0).astype(float)
    w1 = 1 - w0 - w2
    t = m * np.power(np.maximum(x / m, 0), c) + b
    s = P - (P - s1) * np.exp(cp * (x - s0))
    li = m + a * (x - m)
    return (t * w0 + li * w1 + s * w2) * post


def curve_samples(points, n=256):
    """processing.color.ToneCurve.eval: Fritsch-Carlson monotone cubic through the points."""
    xs = np.array([p[0] for p in points], float)
    ys = np.array([p[1] for p in points], float)
    k = len(xs)
    d = np.diff(ys) / np.maximum(np.diff(xs), 1e-6)
    m = np.zeros(k)
    m[0], m[-1] = d[0], d[-1]
    for i in range(1, k - 1):
        m[i] = 0 if d[i - 1] * d[i] <= 0 else (d[i - 1] + d[i]) / 2
    for i in range(k - 1):
        if abs(d[i]) < 1e-7:
            m[i] = m[i + 1] = 0
            continue
        al, be = m[i] / d[i], m[i + 1] / d[i]
        if al < 0:
            m[i] = 0
        if be < 0:
            m[i + 1] = 0
        s = al * al + be * be
        if s > 9:
            t = 3 / np.sqrt(s)
            m[i], m[i + 1] = t * al * d[i], t * be * d[i]
    out = []
    for x in np.linspace(0, 1, n):
        j = 0
        while j < k - 2 and x > xs[j + 1]:
            j += 1
        h = max(xs[j + 1] - xs[j], 1e-6)
        t = (x - xs[j]) / h
        v = ((2 * t ** 3 - 3 * t ** 2 + 1) * ys[j] + (t ** 3 - 2 * t ** 2 + t) * h * m[j]
             + (-2 * t ** 3 + 3 * t ** 2) * ys[j + 1] + (t ** 3 - t ** 2) * h * m[j + 1])
        out.append(min(max(v, 0), 1))
    return np.array(out, np.float32)


def curve_apply(samples, v):
    x = np.clip(0.5 + np.log2(np.maximum(v, 1e-7) / 0.18) * 0.1, 0, 1)
    f = x * 255
    i = np.minimum(f.astype(int), 254)
    disp = samples[i] + (samples[i + 1] - samples[i]) * (f - i)
    return np.power(np.maximum(disp, 0), 2.4)


OUTSET_ROW = 1.19687902425835 - 0.09802088114013 - 0.09902975707567   # a grey through the AgX outset


def display(lin, toe=0.1):
    out = np.power(np.maximum(lin, 1e-6), 1 / 2.2)
    low = out < 0.15
    out = np.where(low, np.power(np.maximum(out / 0.15, 1e-6), 1 + toe) * 0.15, out)
    return np.clip(out, 0, 1)


def main():
    try:
        import moderngl
    except ImportError:
        print('moderngl missing: tone operator check skipped')
        return 0
    try:
        ctx = moderngl.create_standalone_context(backend='egl', require=430)
    except Exception:
        ctx = moderngl.create_standalone_context(require=430)
    src = expand_imports((SH / 'ark/combine.glsl').read_text(encoding='utf-8'))
    greys = np.geomspace(0.004, 2.5, 96)
    n = len(greys)
    W = 32
    H = -(-n // W)
    img = np.zeros((H, W, 4), np.float32)
    img.reshape(-1, 4)[:n, :3] = greys[:, None]
    # the fused display luma whose ACES inversion gives the grey back (as check_colour_look builds it)
    grid = np.linspace(0, 2.51 / 2.43 - 0.0101, 40000)
    b, e = 0.0375, 0.132
    A, B, C = 2.43 * grid - 2.51, 0.59 * grid - b, e * grid
    table = np.maximum((-B - np.sqrt(np.maximum(B * B - 4 * A * C, 0))) / (2 * A - 1e-6) / 1.5, 0)
    fused = np.zeros((H, W, 4), np.float32)
    fused.reshape(-1, 4)[:n, 0] = np.interp(np.minimum(greys, table.max()), table, grid) ** (1 / 2.2)
    reach = greys < table.max() * 0.98   # greys the inversion can carry exactly

    def tex(a):
        t = ctx.texture((a.shape[1], a.shape[0]), 4, a.astype('f4').tobytes(), dtype='f4')
        t.filter = (moderngl.NEAREST, moderngl.NEAREST)
        return t
    ones = tex(np.ones((1, 1, 4), np.float32))
    t_img, t_fused = tex(img), tex(fused)
    vs = '#version 300 es\nvoid main(){vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));gl_Position=vec4(p*2.-1.,0,1);}'

    def render(op, aces_u=(1.0, 2.61, 0.59, 0.05), uchi_a=(1.0, 0.9, 1.0, 1.0), uchi_b=(0.22, 0.4, 1.33, 0.0), curve=None):
        shader = set_define(src, 'TONE_OP', '1' if op else '0')
        prog = ctx.program(vertex_shader=vs, fragment_shader='#version 300 es\n' + shader)
        out = ctx.texture((W, H), 4, dtype='f4')
        fb = ctx.framebuffer([out])
        fb.use()
        ctx.viewport = (0, 0, W, H)
        units = {'InputBuffer': t_img, 'GainMap': ones, 'ArkLow': t_img, 'ArkColour': t_img, 'ArkFused': t_fused,
                 'ArkDetailRef': t_img, 'ArkLumaS': t_img}
        for i, (k, t) in enumerate(units.items()):
            if k in prog:
                t.use(i)
                prog[k].value = i
        eye = tuple(np.eye(3).flatten())
        vals = {'sensorToIntermediate': eye, 'intermediateToSRGB': eye, 'neutralPointU': (1, 1, 1), 'inScaleU': 1.0,
                'fU': 1, 'colourFU': 1, 'aeU': 1.0, 'clipU': 1.0, 'toeU': 0.05, 'gammaInvU': 1 / 2.2, 'macroU': 1.0,
                'vibU': (0.0, 0.0, 0.0), 'detailGainU': 0.0, 'deltaChromaU': 1.0, 'filmToeU': 0.1, 'ditherU': 0,
                'agxAU': (2.7, 1.35, 1.6, 1.0), 'agxBU': (-8.5, 3.5, 0.3, 4.0 if not op else 0.0), 'headroomU': 0, 'hlWhiteU': 0.0}
        if op:
            vals.update({'toneOpU': op, 'acesU': aces_u, 'uchiAU': uchi_a, 'uchiBU': uchi_b})
        for k, v in vals.items():
            if k in prog:
                prog[k].value = v
        if op and 'curveU' in prog:
            s = curve if curve is not None else curve_samples([(0, 0), (1, 1)])
            prog['curveU'].write(np.asarray(s, np.float32).tobytes())
        ctx.vertex_array(prog, []).render(vertices=3)
        res = np.frombuffer(out.read(), np.float32).reshape(H, W, 4)[:, :, :3].reshape(-1, 3)[:n]
        fb.release()
        out.release()
        prog.release()
        return res

    def compare(label, got, want_lin, tol=0.004):
        want = display(np.clip(want_lin, 0, 1) * OUTSET_ROW)
        g = got[:, 1]
        err = np.abs(g - want)[reach]
        check(np.all(np.isfinite(got)), label + ': not finite')
        check(np.all(np.diff(g) >= -1e-4), label + ': does not rise with the input')
        check(np.all((got >= 0) & (got <= 1)), label + ': out of 0..1')
        check(np.all(np.abs(got[:, 0] - got[:, 2]) < 2e-3), label + ': a grey got colour')
        check(err.max() < tol, '%s: max error %.4f against the reference' % (label, err.max()))
        print('  %-34s max |GPU - numpy| %.5f over %d greys' % (label, err.max(), int(reach.sum())))

    print('tone operators on the GPU (TONE_OP 1):')
    compare('ACES (ArkCam defaults)', render(1), aces(greys))
    compare('ACES (pre-gain 1.6, a 3, d 0.8)', render(1, aces_u=(1.6, 3.0, 0.8, 0.05)), aces(greys, pre=1.6, a=3.0, d=0.8))
    compare('Uchimura (ArkCam defaults)', render(2), uchimura(greys))
    compare('Uchimura (P 1.4, c 2, b 0.05)', render(2, uchi_a=(1.0, 0.9, 1.4, 1.0), uchi_b=(0.22, 0.4, 2.0, 0.05)),
            uchimura(greys, P=1.4, c=2.0, b=0.05))
    line = curve_samples([(0, 0), (1, 1)])
    compare('own curve: straight line', render(3, curve=line), curve_apply(line, greys))
    s = curve_samples([(0, 0), (0.25, 0.16), (0.5, 0.5), (0.75, 0.84), (1, 1)])
    s_out = render(3, curve=s)
    compare('own curve: ArkCam S', s_out, curve_apply(s, greys))
    line_out = render(3, curve=line)
    mid = np.argmin(np.abs(greys - 0.18))
    check(s_out[mid - 20, 1] < line_out[mid - 20, 1] and s_out[mid + 12, 1] > line_out[mid + 12, 1],
          'the S curve does not darken the shadows and brighten the highlights')
    agx = render(0)
    check(np.all(np.isfinite(agx)) and np.all(np.diff(agx[:, 1]) >= -1e-4), 'TONE_OP 0 (AgX) broken')
    check(not np.allclose(agx, render(1), atol=1e-3), 'TONE_OP 1 renders the same as AgX')
    if FAILURES:
        print('tone operator check FAILED (%d)' % len(FAILURES))
        return 1
    print('tone operator check PASS')
    return 0


if __name__ == '__main__':
    sys.exit(main())
