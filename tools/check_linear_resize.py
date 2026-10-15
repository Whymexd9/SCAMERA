#!/usr/bin/env python3
"""P145: the Ultra HDR linear snapshot resized on the GPU (assets/shaders/hybrid/final_resize.glsl, LINEAR_IN 1, "area").

The snapshot is linear light with values above 1 (the highlights the gain map exists for), so the resize must be a plain
weighted mean: no clamp, no sRGB coding. On the desktop GPU (moderngl) against a numpy box filter with partial coverage
(the shader's centre ((2x + 1) in - out) / (2 out) and radius scale / 2, separable), for the ratios of the 50 MP grid ->
20 MP (1.57) and the 200 MP grid -> 20 MP (3.16), on an RGBA16F image with values up to 8 and small negatives:
- the mean agrees within the FP16 output precision (relative 2e-3);
- a flat 4.0 area stays 4.0 (nothing is clamped to 1).
"""
import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
SHADER = ROOT / 'app/src/main/assets/shaders/hybrid/final_resize.glsl'
FAILURES = []
VS = '#version 430\nvoid main(){vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));gl_Position=vec4(p*2.-1.,0,1);}'


def check(cond, msg):
    if not cond:
        FAILURES.append(msg)
        print('FAIL: ' + msg)


def weights(n_in, n_out):
    """Row-normalised box weights of the shader along one axis: [n_out, n_in]."""
    scale = n_in / n_out
    r = 0.5 * scale
    w = np.zeros((n_out, n_in))
    i = np.arange(n_in)
    for x in range(n_out):
        c = ((2 * x + 1) * n_in - n_out) / (2 * n_out)       # the shader's base + frac
        w[x] = np.clip(np.minimum(i + 0.5, c + r) - np.maximum(i - 0.5, c - r), 0, 1)
    return w / w.sum(axis=1, keepdims=True)


def main():
    try:
        import moderngl
    except ImportError:
        print('moderngl missing: linear resize check skipped')
        return 0
    try:
        ctx = moderngl.create_standalone_context(backend='egl', require=430)
    except Exception:
        ctx = moderngl.create_standalone_context(require=430)
    src = SHADER.read_text(encoding='utf-8').replace('#define LINEAR_IN 0', '#define LINEAR_IN 1', 1)
    prog = ctx.program(vertex_shader=VS, fragment_shader='#version 430\n' + src)
    rng = np.random.default_rng(21)
    print('linear snapshot resize (area, LINEAR_IN 1):')
    for (wi, hi), (wo, ho) in [((816, 614), (518, 390)), ((1638, 1228), (518, 388))]:
        img = np.exp(rng.normal(np.log(0.2), 1.2, (hi, wi, 3))).astype(np.float32)
        img[rng.random((hi, wi)) < 0.01, 0] = -0.003
        img[40:120, 60:200, :] = 4.0                                     # a lamp: well above 1
        rgba = np.concatenate([img, np.ones((hi, wi, 1), np.float32)], axis=2).astype(np.float16)
        tex = ctx.texture((wi, hi), 4, rgba.tobytes(), dtype='f2')
        tex.filter = (moderngl.NEAREST, moderngl.NEAREST)
        tex.use(0)
        out = ctx.texture((wo, ho), 4, dtype='f2')
        fb = ctx.framebuffer([out])
        fb.use()
        ctx.viewport = (0, 0, wo, ho)
        prog['InputBuffer'] = 0
        prog['kernel'] = 2
        prog['yOffset'] = 0
        prog['inSize'] = (wi, hi)
        prog['outSize'] = (wo, ho)
        ctx.vertex_array(prog, []).render(vertices=3)
        got = np.frombuffer(out.read(), np.float16).reshape(ho, wo, 4)[..., :3].astype(np.float64)
        ref_in = rgba[..., :3].astype(np.float64)
        wy, wx = weights(hi, ho), weights(wi, wo)
        ref = np.stack([wy @ ref_in[..., c] @ wx.T for c in range(3)], axis=2)
        rel = np.abs(got - ref) / np.maximum(np.abs(ref), 0.05)
        check(rel.max() < 2e-3, '%dx%d -> %dx%d: max relative difference %.2e' % (wi, hi, wo, ho, rel.max()))
        y0, x0 = int(60 * ho / hi), int(100 * wo / wi)
        lamp = got[y0, x0]
        check(np.allclose(lamp, 4.0, rtol=2e-3), '%dx%d: the 4.0 lamp reads %s' % (wi, hi, lamp))
        print('  %4dx%-4d -> %dx%d (ratio %.2f)  max rel %.1e  lamp %.3f' % (wi, hi, wo, ho, wi / wo, rel.max(), lamp[1]))
        for o in (tex, out, fb):
            o.release()
    if FAILURES:
        print('linear resize check FAILED (%d)' % len(FAILURES))
        return 1
    print('linear resize check PASS')
    return 0


if __name__ == '__main__':
    sys.exit(main())
