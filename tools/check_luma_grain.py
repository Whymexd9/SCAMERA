#!/usr/bin/env python3
"""P335: scamdn/lgrain (the share of the removed fine luma noise ScamDenoise puts back) against a numpy reference.

Checks: the GPU result equals keep x (r - its 5-tap binomial blur - its 2 px alternations), r = Y_in - Y_den, with clamped borders
(max |diff| < 1e-6);
nothing comes back where the denoise removed nothing or only a smooth (coarse) part (a constant / linear r gives 0 inside);
keep 0 gives 0; the put-back noise is a band below the removed rms; P336: a 2 px checker in r (the merge's Bayer lattice) comes
back at most 1 % of its amplitude.
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
    """P336: keep x (r - 5-tap binomial blur of r - the 2 px alternations of r over the 5 x 5 sites around
    (weights (1 2 2 2 1) / 8 per axis)), r = Y_in - Y_den,
    borders clamped."""
    r = orig.astype(np.float64) - den
    h, w = r.shape
    p = np.pad(r, 2, mode='edge')
    sh = lambda i, j: p[2 + j:2 + j + h, 2 + i:2 + i + w]
    k = [0.0625, 0.25, 0.375, 0.25, 0.0625]
    b5 = sum(k[j + 2] * k[i + 2] * sh(i, j) for j in range(-2, 3) for i in range(-2, 3))
    wv = [0.125, 0.25, 0.25, 0.25, 0.125]
    sg = lambda i: 1 if i % 2 == 0 else -1
    lat = sum((sg(i) * sg(j) + sg(j) + sg(i)) * wv[i + 2] * wv[j + 2] * sh(i, j) for j in range(-2, 3) for i in range(-2, 3))
    return keep * (r - b5 - lat)


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
    yy, xx = np.mgrid[0:h, 0:w]
    checker = 0.02 * np.where((xx + yy) % 2 == 0, 1.0, -1.0)
    lat = run(gl, base + checker, base, 1.0)[3:-3, 3:-3]
    print('2 px checker of 0.02 put back: max |out| %.2e' % np.abs(lat).max())
    if np.abs(lat).max() > 2e-4:
        fails.append('lattice')
    removed = orig - den
    back = run(gl, orig, den, 1.0)
    print('rms removed %.4f, put back (keep 1) %.4f' % (removed.std(), back.std()))
    if not back.std() < removed.std():
        fails.append('band')
    if fails:
        print('FAIL:', ', '.join(fails))
        sys.exit(1)
    print('PASS: lgrain = keep x the fine removed luma without its 2 px lattice, nothing from smooth parts')


if __name__ == '__main__':
    main()
