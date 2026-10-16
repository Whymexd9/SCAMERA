#!/usr/bin/env python3
"""P134 «Плёночный LUT»: ark/combine.glsl with FILM_LUT 1 on the GPU (moderngl).

A ramp of colours goes through the shader without the LUT and with it; the LUT stage must
- leave the output alone with an identity LUT at full strength (17^3, the sampling of the texel centres);
- give the trilinear lookup of a warm look (red +0.1, blue -0.1, numpy reference of processing.color.FilmLut.sample);
- mix by the strength (0.5 = half of the look's shift);
- honour the file's domain (LUT_3D_INPUT_RANGE 0..2 halves the coordinate).
"""
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from check_colour_output import SH, expand_imports, set_define  # noqa: E402

FAILURES = []
# texture filtering interpolates with 8-bit sub-texel weights (GPU hardware): up to ~4e-4 against exact trilinear, a tenth
# of one 8-bit output step (1 / 255)
TOL = 1e-3


def check(cond, msg):
    if not cond:
        FAILURES.append(msg)
        print('FAIL: ' + msg)


def identity(n):
    b, g, r = np.meshgrid(np.arange(n), np.arange(n), np.arange(n), indexing='ij')
    return np.stack([r, g, b], -1).astype(np.float32) / (n - 1)   # [b][g][r][rgb]: red fastest, as a .cube


def warm(n):
    lut = identity(n)
    lut[..., 0] = np.minimum(lut[..., 0] + 0.1, 1)
    lut[..., 2] = np.maximum(lut[..., 2] - 0.1, 0)
    return lut


def trilinear(lut, rgb, lo=0.0, hi=1.0):
    n = lut.shape[0]
    v = np.clip((np.clip(rgb, 0, 1) - lo) / (hi - lo), 0, 1) * (n - 1)
    i0 = np.minimum(np.floor(v).astype(int), n - 2)
    p = v - i0
    out = np.zeros_like(rgb)
    for corner in range(8):
        dr, dg, db = corner & 1, (corner >> 1) & 1, corner >> 2
        w = (np.where(dr, p[:, 0], 1 - p[:, 0]) * np.where(dg, p[:, 1], 1 - p[:, 1]) * np.where(db, p[:, 2], 1 - p[:, 2]))
        out += w[:, None] * lut[i0[:, 2] + db, i0[:, 1] + dg, i0[:, 0] + dr]
    return out


def main():
    try:
        import moderngl
    except ImportError:
        print('moderngl missing: film LUT check skipped')
        return 0
    try:
        ctx = moderngl.create_standalone_context(backend='egl', require=430)
    except Exception:
        ctx = moderngl.create_standalone_context(require=430)
    src = expand_imports((SH / 'ark/combine.glsl').read_text(encoding='utf-8'))
    rng = np.random.default_rng(5)
    n = 512
    W = 32
    H = n // W
    cols = np.concatenate([np.repeat(np.geomspace(0.004, 1.2, 64)[:, None], 3, 1),
                           rng.uniform(0.0, 1.0, (n - 64, 3)) * rng.uniform(0.05, 1.2, (n - 64, 1))]).astype(np.float32)
    img = np.zeros((H, W, 4), np.float32)
    img.reshape(-1, 4)[:, :3] = cols
    fused = np.zeros((H, W, 4), np.float32)
    fused.reshape(-1, 4)[:, 0] = 0.5

    def tex(a):
        t = ctx.texture((a.shape[1], a.shape[0]), 4, a.astype('f4').tobytes(), dtype='f4')
        t.filter = (moderngl.NEAREST, moderngl.NEAREST)
        return t
    ones = tex(np.ones((1, 1, 4), np.float32))
    t_img, t_fused = tex(img), tex(fused)
    vs = '#version 300 es\nvoid main(){vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));gl_Position=vec4(p*2.-1.,0,1);}'

    def render(lut=None, strength=1.0, lo=0.0, hi=1.0):
        shader = set_define(src, 'FILM_LUT', '1' if lut is not None else '0')
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
        t3 = None
        if lut is not None:
            size = lut.shape[0]
            t3 = ctx.texture3d((size, size, size), 3, np.ascontiguousarray(lut, np.float32).tobytes(), dtype='f4')
            t3.filter = (moderngl.LINEAR, moderngl.LINEAR)
            t3.repeat_x = t3.repeat_y = t3.repeat_z = False
            t3.use(10)
            prog['FilmLut'].value = 10
            prog['lutU'].value = (strength, float(size))
            prog['lutMinU'].value = (lo, lo, lo)
            prog['lutScaleU'].value = (1 / (hi - lo),) * 3
        eye = tuple(np.eye(3).flatten())
        vals = {'sensorToIntermediate': eye, 'intermediateToSRGB': eye, 'neutralPointU': (1, 1, 1), 'inScaleU': 1.0,
                'fU': 1, 'colourFU': 1, 'aeU': 1.0, 'clipU': 1.0, 'toeU': 0.05, 'gammaInvU': 1 / 2.2, 'macroU': 1.0,
                'vibU': (0.0, 0.0, 0.0), 'detailGainU': 0.0, 'deltaChromaU': 1.0, 'filmToeU': 0.1, 'ditherU': 0,
                'agxAU': (2.7, 1.35, 1.6, 1.0), 'agxBU': (-8.5, 3.5, 0.3, 4.0), 'headroomU': 0, 'hlWhiteU': 0.0}
        for k, v in vals.items():
            if k in prog:
                prog[k].value = v
        ctx.vertex_array(prog, []).render(vertices=3)
        res = np.frombuffer(out.read(), np.float32).reshape(H, W, 4)[:, :, :3].reshape(-1, 3)
        fb.release()
        out.release()
        prog.release()
        if t3 is not None:
            t3.release()
        return res

    base = render()
    check(np.all(np.isfinite(base)), 'the output without a LUT is not finite')
    same = render(identity(17))
    err = np.abs(same - base).max()
    print('  identity 17^3 at full strength: max |with - without| %.6f' % err)
    check(err < TOL, 'the identity LUT changes the output (%.6f)' % err)
    look = warm(17)
    got = render(look)
    want = trilinear(look, base)
    err = np.abs(got - want).max()
    print('  warm look: max |GPU - numpy trilinear| %.6f' % err)
    check(err < TOL, 'the warm LUT differs from its trilinear lookup (%.6f)' % err)
    check(np.mean(got[:, 0] - base[:, 0]) > 0.05 and np.mean(got[:, 2] - base[:, 2]) < -0.05, 'the warm look is not warmer')
    half = render(look, 0.5)
    err = np.abs(half - (base + 0.5 * (want - base))).max()
    print('  strength 0.5: max |GPU - half the shift| %.6f' % err)
    check(err < TOL, 'the strength does not mix the look (%.6f)' % err)
    ranged = render(identity(17), 1.0, 0.0, 2.0)
    err = np.abs(ranged - trilinear(identity(17), base, 0.0, 2.0)).max()
    print('  input range 0..2: max |GPU - numpy| %.6f' % err)
    check(err < TOL, 'the LUT domain is not honoured (%.6f)' % err)
    if FAILURES:
        print('film LUT check FAILED (%d)' % len(FAILURES))
        return 1
    print('film LUT check PASS')
    return 0


if __name__ == '__main__':
    sys.exit(main())
