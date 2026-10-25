#!/usr/bin/env python3
"""P335: scamdn/lgrain (the share of the removed fine luma noise ScamDenoise puts back) against a numpy reference.

Checks: the GPU result equals keep x (r - 1-2-1 blur of r), r = Y_in - Y_den, with clamped borders (max |diff| < 1e-6);
nothing comes back where the denoise removed nothing or only a smooth (coarse) part (a constant / linear r gives 0 inside);
keep 0 gives 0; the put-back noise has the removed noise's fine band only (its rms below the removed rms).
"""
import sys
from pathlib import Path

import moderngl
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
SRC = (ROOT / 'app/src/main/assets/shaders/scamdn/lgrain.glsl').read_text()
VS = '#version 310 es\nin vec2 vPosition;\nvoid main(){gl_Position=vec4(vPosition,0.0,1.0);}\n'


def ctx():
    try:
        return moderngl.create_standalone_context(backend='egl', require=310)
    except Exception:
        return moderngl.create_standalone_context(require=310)


def run(gl, orig, den, keep):
    h, w = orig.shape
    prog = gl.program(vertex_shader=VS, fragment_shader='#version 310 es\n' + SRC)

    def tex(a):
        t = gl.texture((w, h), 4, np.dstack([a, np.zeros_like(a), np.zeros_like(a), np.ones_like(a)]).astype('f4').tobytes(), dtype='f4')
        t.filter = (moderngl.NEAREST, moderngl.NEAREST)
        return t
    to, td = tex(orig), tex(den)
    out = gl.texture((w, h), 4, dtype='f4')
    fb = gl.framebuffer([out])
    fb.use()
    to.use(0)
    td.use(1)
    prog['Orig'].value, prog['Den'].value = 0, 1
    prog['keepU'].value = keep
    vbo = gl.buffer(np.array([-1, -1, 1, -1, -1, 1, 1, 1], 'f4').tobytes())
    gl.vertex_array(prog, [(vbo, '2f', 'vPosition')]).render(moderngl.TRIANGLE_STRIP)
    return np.frombuffer(out.read(), 'f4').reshape(h, w, 4)[..., 0]


def reference(orig, den, keep):
    r = orig.astype(np.float64) - den
    p = np.pad(r, 1, mode='edge')
    b = (4 * p[1:-1, 1:-1] + 2 * (p[1:-1, 2:] + p[1:-1, :-2] + p[2:, 1:-1] + p[:-2, 1:-1])
         + p[2:, 2:] + p[:-2, :-2] + p[2:, :-2] + p[:-2, 2:]) / 16
    return keep * (r - b)


def main():
    gl = ctx()
    rng = np.random.default_rng(5)
    h, w = 97, 131
    base = np.linspace(0.05, 0.6, w)[None, :] * np.ones((h, 1))
    noise = rng.normal(0, 0.02, (h, w))
    orig, den = base + noise, base + 0.3 * noise
    fails = []
    got, ref = run(gl, orig, den, 0.5), reference(orig, den, 0.5)
    d = np.abs(got - ref).max()
    print('reference: max |gpu - numpy| %.2e' % d)
    if d > 1e-6:
        fails.append('reference')
    smooth = run(gl, base + 0.01 * np.arange(w)[None, :] / w, base, 1.0)[2:-2, 2:-2]
    print('linear removed part: max |out| %.2e' % np.abs(smooth).max())
    if np.abs(smooth).max() > 1e-6:
        fails.append('linear')
    zero = run(gl, orig, den, 0.0)
    if np.abs(zero).max() != 0:
        fails.append('keep 0')
    removed = orig - den
    back = run(gl, orig, den, 1.0)
    print('rms removed %.4f, put back (keep 1) %.4f' % (removed.std(), back.std()))
    if not back.std() < removed.std():
        fails.append('band')
    if fails:
        print('FAIL:', ', '.join(fails))
        sys.exit(1)
    print('PASS: lgrain = keep x high band of the removed luma, nothing from smooth parts')


if __name__ == '__main__':
    main()
