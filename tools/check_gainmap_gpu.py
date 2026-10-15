#!/usr/bin/env python3
"""P145: the Ultra HDR gain-map normalisation on the GPU (assets/shaders/ultrahdr/gainmax.glsl, gainquant.glsl).

On the desktop GPU (moderngl, GL 4.3) over RGBA8 maps like ultrahdr/gainmap's output (R = G = B, odd sizes):
- gainmax finds the largest byte exactly (the first pass of GainMapComputer.compute);
- gainquant maps every byte through the table (GainMapComputer.requantisation) exactly, R = G = B, alpha 255.
That the table and the maximum give compute's map bit for bit is GainMapComputerTest.gpuRequantisationIsCompute.
"""
import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
SH = ROOT / 'app/src/main/assets/shaders/ultrahdr'
FAILURES = []
VS = '#version 430\nvoid main(){vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));gl_Position=vec4(p*2.-1.,0,1);}'


def check(cond, msg):
    if not cond:
        FAILURES.append(msg)
        print('FAIL: ' + msg)


def compute_source():
    text = (SH / 'gainmax.glsl').read_text(encoding='utf-8')
    text = text.replace('\nLAYOUT\n', '\nlayout(local_size_x = 16, local_size_y = 16, local_size_z = 1) in;\n', 1)
    return '#version 430\n' + text


def main():
    try:
        import moderngl
    except ImportError:
        print('moderngl missing: gain-map GPU check skipped')
        return 0
    try:
        ctx = moderngl.create_standalone_context(backend='egl', require=430)
    except Exception:
        ctx = moderngl.create_standalone_context(require=430)
    gmax = ctx.compute_shader(compute_source())
    quant = ctx.program(vertex_shader=VS, fragment_shader='#version 430\n' + (SH / 'gainquant.glsl').read_text(encoding='utf-8'))
    rng = np.random.default_rng(11)
    print('Ultra HDR gain map on the GPU:')
    for w, h, top in [(333, 211, 200), (1000, 17, 255), (64, 64, 0), (257, 129, 3)]:
        v = rng.integers(0, top + 1, (h, w), dtype=np.int32).astype(np.uint8)
        img = np.stack([v, v, v, np.full_like(v, 255)], axis=2)
        tex = ctx.texture((w, h), 4, img.tobytes())
        tex.filter = (moderngl.NEAREST, moderngl.NEAREST)
        tex.use(0)
        buf = ctx.buffer(np.zeros(1, np.uint32).tobytes())
        buf.bind_to_storage_buffer(1)
        gmax['InputBuffer'] = 0
        gmax['sizeU'] = (w, h)
        gmax.run((w + 15) // 16, (h + 15) // 16, 1)
        ctx.finish()
        got = int(np.frombuffer(buf.read(), np.uint32)[0])
        check(got == int(v.max()), '%dx%d: max %d instead of %d' % (w, h, got, v.max()))
        lut = rng.integers(0, 256, 256)
        out = ctx.texture((w, h), 4)
        fb = ctx.framebuffer([out])
        fb.use()
        ctx.viewport = (0, 0, w, h)
        quant['InputBuffer'] = 0
        quant['lutU'].write(lut.astype(np.float32).tobytes())
        ctx.vertex_array(quant, []).render(vertices=3)
        res = np.frombuffer(out.read(), np.uint8).reshape(h, w, 4)
        want = lut[v]
        ok = (res[..., 0] == want) & (res[..., 1] == want) & (res[..., 2] == want) & (res[..., 3] == 255)
        check(ok.all(), '%dx%d: %d pixels off the table' % (w, h, (~ok).sum()))
        print('  %4dx%-4d max %3d (exact: %s)  table: %s' % (w, h, got, got == int(v.max()), 'exact' if ok.all() else 'DIFFERS'))
        for o in (tex, buf, out, fb):
            o.release()
    if FAILURES:
        print('gain-map GPU check FAILED (%d)' % len(FAILURES))
        return 1
    print('gain-map GPU check PASS')
    return 0


if __name__ == '__main__':
    sys.exit(main())
