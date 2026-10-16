#!/usr/bin/env python3
"""P154 «Мягкость засветов»: the highlight landing of ark/combine.glsl on the GPU (moderngl).

Ramps up to the data ceiling (a grey, an orange light, a slightly blue white) go through the shader with clipped data in
the frame (headroomU 1) without the landing (hlSoftU 0) and with it (0.5):
- the blown grey ends on pure white (1.0 in every channel; without the landing it stays a grey below 0.99);
- below the start of the landing (half the ceiling) nothing changes;
- the output rises with the input and the last steps before the ceiling get smaller (the slope fades into the plateau);
- the orange light keeps its hue on the way up (channel ratios as without the landing) and peaks at 1.0 in its max channel;
- a slightly blue near-white ends on pure white;
- without clipped data (headroomU 0) the landing does nothing.
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


def main():
    try:
        import moderngl
    except ImportError:
        print('moderngl missing: highlight landing check skipped')
        return 0
    try:
        ctx = moderngl.create_standalone_context(backend='egl', require=430)
    except Exception:
        ctx = moderngl.create_standalone_context(require=430)
    src = expand_imports((SH / 'ark/combine.glsl').read_text(encoding='utf-8'))
    src_off = src
    src = set_define(src, 'HL_LANDING', '1')
    clip = 2.5
    k = np.geomspace(0.02, 1.0, 96)                      # fraction of the ceiling
    tints = {'grey': np.array([1.0, 1.0, 1.0]), 'orange': np.array([1.0, 0.55, 0.2]), 'cool white': np.array([0.95, 0.97, 1.0])}
    rows = [np.outer(k * clip, t) for t in tints.values()]
    cols = np.concatenate(rows).astype(np.float32)
    n = len(cols)
    W = 32
    H = -(-n // W)
    img = np.zeros((H, W, 4), np.float32)
    img.reshape(-1, 4)[:n, :3] = cols
    # the fused display luma whose ACES inversion gives the luminance back (as check_tone_ops builds it)
    grid = np.linspace(0, 2.51 / 2.43 - 0.0101, 40000)
    b, e = 0.0375, 0.132
    A, B, C = 2.43 * grid - 2.51, 0.59 * grid - b, e * grid
    table = np.maximum((-B - np.sqrt(np.maximum(B * B - 4 * A * C, 0))) / (2 * A - 1e-6) / 1.5, 0)
    lum = cols @ np.array([0.2126, 0.7152, 0.0722])
    fused = np.zeros((H, W, 4), np.float32)
    fused.reshape(-1, 4)[:n, 0] = np.interp(np.minimum(lum, table.max()), table, grid) ** (1 / 2.2)

    def tex(a):
        t = ctx.texture((a.shape[1], a.shape[0]), 4, a.astype('f4').tobytes(), dtype='f4')
        t.filter = (moderngl.NEAREST, moderngl.NEAREST)
        return t
    ones = tex(np.ones((1, 1, 4), np.float32))
    t_img, t_fused = tex(img), tex(fused)
    vs = '#version 300 es\nvoid main(){vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));gl_Position=vec4(p*2.-1.,0,1);}'
    prog = ctx.program(vertex_shader=vs, fragment_shader='#version 300 es\n' + src)

    def render(soft, headroom=1):
        out = ctx.texture((W, H), 4, dtype='f4')
        fb = ctx.framebuffer([out])
        fb.use()
        ctx.viewport = (0, 0, W, H)
        units = {'InputBuffer': t_img, 'GainMap': ones, 'ArkLow': t_img, 'ArkColour': t_img, 'ArkFused': t_fused,
                 'ArkDetailRef': t_img, 'ArkLumaS': t_img}
        for i, (key, t) in enumerate(units.items()):
            if key in prog:
                t.use(i)
                prog[key].value = i
        eye = tuple(np.eye(3).flatten())
        vals = {'sensorToIntermediate': eye, 'intermediateToSRGB': eye, 'neutralPointU': (1, 1, 1), 'inScaleU': 1.0,
                'fU': 1, 'colourFU': 1, 'aeU': 1.0, 'clipU': clip, 'toeU': 0.05, 'gammaInvU': 1 / 2.2, 'macroU': 1.0,
                'vibU': (0.0, 0.0, 0.0), 'detailGainU': 0.0, 'deltaChromaU': 1.0, 'filmToeU': 0.1, 'ditherU': 0,
                'agxAU': (2.7, 1.35, 1.6, 1.0), 'agxBU': (-8.5, 3.5, 0.3, 4.0), 'headroomU': headroom, 'hlWhiteU': 0.0,
                'hlSoftU': soft}
        for key, v in vals.items():
            if key in prog:
                prog[key].value = v
        ctx.vertex_array(prog, []).render(vertices=3)
        res = np.frombuffer(out.read(), np.float32).reshape(H, W, 4)[:, :, :3].reshape(-1, 3)[:n]
        fb.release()
        out.release()
        return res.reshape(len(tints), len(k), 3)

    base, land = render(0.0), render(0.5)
    check(np.all(np.isfinite(land)), 'not finite')
    below = k < 0.5
    for i, name in enumerate(tints):
        b0, l0 = base[i], land[i]
        print('  %-10s ceiling: without %s  with %s' % (name, np.round(b0[-1], 4), np.round(l0[-1], 4)))
        err = np.abs(l0[below] - b0[below]).max()
        check(err < 1e-6, '%s: changed below the start of the landing (%.2e)' % (name, err))
        peak = l0.max(1)
        check(np.all(np.diff(peak) >= -1e-5), '%s: does not rise with the input' % name)
        check(abs(l0[-1].max() - 1.0) < 1e-3, '%s: the max channel at the ceiling is %.4f, not 1' % (name, l0[-1].max()))
        steps = np.diff(peak[k > 0.9])
        check(steps[-1] < steps[0] * 0.5 + 1e-4, '%s: the last steps before the ceiling do not get smaller' % name)
    check(base[0][-1].max() < 0.99, 'the test is moot: without the landing the blown grey is already white')
    check(np.all(land[0][-1] > 0.999), 'the blown grey is not pure white: %s' % land[0][-1])
    check(np.all(land[2][-1] > 0.999), 'the slightly blue near-white is not pure white: %s' % land[2][-1])
    # the orange light keeps its hue (channel ratios as without the landing) where the landing acts
    acting = k >= 0.6
    rb = base[1][acting] / np.maximum(base[1][acting].max(1, keepdims=True), 1e-6)
    rl = land[1][acting] / np.maximum(land[1][acting].max(1, keepdims=True), 1e-6)
    hue = np.abs(rb - rl).max()
    print('  orange: max change of the channel ratios %.4f' % hue)
    check(hue < 0.01, 'the orange light changed its hue (%.4f)' % hue)
    same = np.abs(render(0.5, headroom=0) - render(0.0, headroom=0)).max()
    print('  without clipped data: max |with - without| %.2e' % same)
    check(same < 1e-6, 'the landing acts without clipped data')
    if FAILURES:
        print('highlight landing check FAILED (%d)' % len(FAILURES))
        return 1
    print('highlight landing check PASS')
    return 0


if __name__ == '__main__':
    sys.exit(main())
